// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat.tools

import android.util.Log
import androidx.datastore.core.DataStore
import app.kaiwa.BuildConfig
import app.kaiwa.proto.McpAuth
import app.kaiwa.proto.McpServer
import app.kaiwa.proto.McpServers
import app.kaiwa.proto.McpTool
import app.kaiwa.proto.UserData
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private const val TAG = "AGChatMcpManager"

/** A connected (or failed) MCP server together with its live client handle. */
data class McpServerState(val mcpServer: McpServer, val client: Client?, val error: String? = null)

data class McpManagerState(
  val mcpServers: List<McpServerState> = emptyList(),
  val loading: Boolean = false,
  val error: String? = null,
)

/** Outcome of an MCP tool invocation, shaped for the tool dispatcher. */
sealed interface McpCallResult {
  data class Success(val text: String) : McpCallResult

  data class Failure(val error: String) : McpCallResult
}

/**
 * App-scoped owner of MCP server connections and tool discovery. It connects to / discovers /
 * persists / invokes / toggles MCP servers, owning its own [CoroutineScope] so it can be a Hilt
 * `@Singleton` rather than a ViewModel.
 */
@Singleton
class McpManager
@Inject
constructor(
  private val mcpServersDataStore: DataStore<McpServers>,
  private val userDataDataStore: DataStore<UserData>,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val httpClient = HttpClient(Android) { install(SSE) }

  private val _state = MutableStateFlow(McpManagerState())
  val state = _state.asStateFlow()

  @Volatile private var loaded = false

  /** Loads persisted servers and (re)connects their clients. Safe to call more than once. */
  suspend fun load() {
    if (loaded) return
    _state.update { it.copy(loading = true) }
    withContext(Dispatchers.IO) {
      try {
        val savedServers = mcpServersDataStore.data.first().mcpServerList
        val loadedStates =
          savedServers.map { serverProto ->
            try {
              val savedToolsMap = serverProto.toolsList.associate { it.name to it.enabled }
              val savedAlwaysAllowMap = serverProto.toolsList.associate { it.name to it.alwaysAllow }
              val (client, mcpTools) =
                initializeClientAndLoadTools(serverProto.url, savedToolsMap, savedAlwaysAllowMap)
              val serverVersion = client.serverVersion
              val updatedServerProto =
                serverProto
                  .toBuilder()
                  .clearTools()
                  .addAllTools(mcpTools)
                  .setEnabled(serverProto.enabled)
                  .apply {
                    serverVersion?.name?.let { setName(it) }
                    serverVersion?.version?.let { setVersion(it) }
                    val desc = mcpTools.joinToString(", ") { it.name }
                    if (desc.isNotEmpty()) setDescription("Tools: $desc")
                  }
                  .build()
              McpServerState(mcpServer = updatedServerProto, client = client, error = null)
            } catch (e: Exception) {
              if (e is CancellationException) throw e
              Log.e(TAG, "Error loading MCP server: ${serverProto.url}", e)
              McpServerState(
                mcpServer = serverProto.toBuilder().setEnabled(false).build(),
                client = null,
                error = e.message ?: "Failed to connect",
              )
            }
          }
        _state.update { it.copy(mcpServers = loadedStates, loading = false) }
        // Persist any newly discovered tool schemas, but keep the original proto for servers that
        // failed to connect so they can be retried on the next launch.
        mcpServersDataStore.updateData {
          McpServers.newBuilder()
            .addAllMcpServer(
              loadedStates.mapIndexed { index, st ->
                if (st.error != null) savedServers[index] else st.mcpServer
              }
            )
            .build()
        }
        loaded = true
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "Error reading saved MCP servers", e)
        _state.update { it.copy(loading = false) }
      }
    }
  }

  /** Connects to [url], discovers its tools, and persists the server (deduplicated by URL). */
  fun addServer(url: String, auth: McpAuth) {
    _state.update { it.copy(loading = true, error = null) }
    scope.launch {
      try {
        val (client, mcpTools) = initializeClientAndLoadTools(url, mcpAuth = auth)
        val serverVersion = client.serverVersion
        val mcpServerProto =
          McpServer.newBuilder()
            .setUrl(url)
            .addAllTools(mcpTools)
            .setEnabled(true)
            .apply {
              serverVersion?.name?.let { setName(it) }
              serverVersion?.version?.let { setVersion(it) }
              val desc = mcpTools.joinToString(", ") { it.name }
              if (desc.isNotEmpty()) setDescription("Tools: $desc")
            }
            .build()
        val newState = McpServerState(mcpServer = mcpServerProto, client = client, error = null)

        mcpServersDataStore.updateData { current ->
          val filtered = current.mcpServerList.filter { it.url != url }
          McpServers.newBuilder().addAllMcpServer(filtered + mcpServerProto).build()
        }
        userDataDataStore.updateData { it.toBuilder().putMcpAuths(url, auth).build() }

        _state.update { current ->
          val filtered = current.mcpServers.filter { it.mcpServer.url != url }
          current.copy(mcpServers = filtered + newState, loading = false)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error adding MCP server: $url", e)
        _state.update { it.copy(error = e.message ?: "Failed to connect", loading = false) }
      }
    }
  }

  fun removeServer(url: String) {
    scope.launch {
      mcpServersDataStore.updateData { current ->
        McpServers.newBuilder()
          .addAllMcpServer(current.mcpServerList.filter { it.url != url })
          .build()
      }
      _state.update { it.copy(mcpServers = it.mcpServers.filter { s -> s.mcpServer.url != url }) }
    }
  }

  fun setServerEnabled(url: String, enabled: Boolean) =
    mutateServer(url) { it.toBuilder().setEnabled(enabled).build() }

  fun setToolEnabled(url: String, toolName: String, enabled: Boolean) =
    mutateTool(url, toolName) { it.toBuilder().setEnabled(enabled).build() }

  fun setToolAlwaysAllow(url: String, toolName: String, alwaysAllow: Boolean) =
    mutateTool(url, toolName) { it.toBuilder().setAlwaysAllow(alwaysAllow).build() }

  /** Whether a tool is configured to bypass the permission gate. */
  fun isToolAlwaysAllow(toolName: String): Boolean =
    _state.value.mcpServers
      .flatMap { it.mcpServer.toolsList }
      .firstOrNull { it.name == toolName }
      ?.alwaysAllow == true

  /** The schema text for every enabled tool of every enabled server, for the system prompt. */
  fun getToolsPrompt(): String =
    _state.value.mcpServers
      .filter { it.mcpServer.enabled }
      .flatMap { it.mcpServer.toolsList }
      .filter { it.enabled }
      .joinToString("\n\n") { tool ->
        "MCP tool name: \"${tool.name}\"\n- Description: ${tool.description}\n- Input schema: ${tool.inputSchema}"
      }

  /** Invokes [toolName] on whichever connected server exports it. */
  suspend fun callTool(toolName: String, inputJson: String): McpCallResult =
    withContext(Dispatchers.IO) {
      val serverState =
        _state.value.mcpServers.find { st ->
          st.mcpServer.toolsList.any { it.name == toolName }
        }
          ?: run {
            Log.w(TAG, "MCP server or tool not found for: $toolName")
            return@withContext McpCallResult.Failure("Tool not found")
          }
      val client =
        serverState.client
          ?: return@withContext McpCallResult.Failure("Client not initialized")
      try {
        val result =
          client.callTool(
            request =
              CallToolRequest(
                CallToolRequestParams(
                  name = toolName,
                  arguments = Json.parseToJsonElement(inputJson).jsonObject,
                )
              )
          ) ?: return@withContext McpCallResult.Failure("Null result")

        val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text ?: "" }
        if (result.isError == true) {
          Log.e(TAG, "MCP tool \"$toolName\" failed: $text")
          McpCallResult.Failure(text)
        } else {
          Log.d(TAG, "MCP tool \"$toolName\" succeeded:\n$text")
          McpCallResult.Success(text)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error calling MCP tool", e)
        McpCallResult.Failure(e.message ?: "Unknown error")
      }
    }

  private fun mutateServer(url: String, transform: (McpServer) -> McpServer) {
    _state.update { current ->
      val updated =
        current.mcpServers.map { st ->
          if (st.mcpServer.url == url) st.copy(mcpServer = transform(st.mcpServer)) else st
        }
      persist(updated)
      current.copy(mcpServers = updated)
    }
  }

  private fun mutateTool(url: String, toolName: String, transform: (McpTool) -> McpTool) {
    mutateServer(url) { server ->
      val tools =
        server.toolsList.map { if (it.name == toolName) transform(it) else it }
      server.toBuilder().clearTools().addAllTools(tools).build()
    }
  }

  private fun persist(updatedServers: List<McpServerState>) {
    scope.launch {
      mcpServersDataStore.updateData { current ->
        val protos =
          updatedServers.map { st ->
            if (st.error != null) current.mcpServerList.find { it.url == st.mcpServer.url } ?: st.mcpServer
            else st.mcpServer
          }
        McpServers.newBuilder().addAllMcpServer(protos).build()
      }
    }
  }

  private suspend fun initializeClientAndLoadTools(
    url: String,
    savedToolsMap: Map<String, Boolean>? = null,
    savedAlwaysAllowMap: Map<String, Boolean>? = null,
    mcpAuth: McpAuth? = null,
  ): Pair<Client, List<McpTool>> {
    Log.d(TAG, "Initializing MCP for $url...")
    val client =
      Client(
        clientInfo =
          Implementation(name = "google-ai-edge-gallery", version = BuildConfig.VERSION_NAME)
      )
    val resolvedAuth = mcpAuth ?: userDataDataStore.data.first().mcpAuthsMap[url]
    val transport =
      if (resolvedAuth != null && resolvedAuth.authMethodCase == McpAuth.AuthMethodCase.REQUEST_HEADER) {
        val reqHeader = resolvedAuth.requestHeader
        StreamableHttpClientTransport(
          client = httpClient,
          url = url,
          requestBuilder = { headers.append(reqHeader.headerName, reqHeader.headerValue) },
        )
      } else {
        StreamableHttpClientTransport(client = httpClient, url = url)
      }
    client.connect(transport)
    val toolsResponse = client.listTools()
    val mcpTools =
      toolsResponse?.tools.orEmpty().map { tool ->
        val isEnabled = savedToolsMap?.get(tool.name) ?: true
        val isAlwaysAllow = savedAlwaysAllowMap?.get(tool.name) ?: false
        val propertiesJson = tool.inputSchema.properties.toString()
        val requiredJson =
          tool.inputSchema.required?.joinToString(prefix = "[", postfix = "]") { "\"$it\"" } ?: "[]"
        val schemaJson = """{"type":"object","properties":$propertiesJson,"required":$requiredJson}"""
        McpTool.newBuilder()
          .setName(tool.name)
          .setDescription(tool.description ?: "")
          .setInputSchema(schemaJson)
          .setEnabled(isEnabled)
          .setAlwaysAllow(isAlwaysAllow)
          .build()
      }
    Log.d(TAG, "Loaded ${mcpTools.size} tools from $url: ${mcpTools.joinToString { it.name }}")
    return Pair(client, mcpTools)
  }
}
