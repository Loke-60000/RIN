// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.ui.modelmanager

import android.content.Context
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.core.net.toUri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Mms
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaiwa.AppLifecycleProvider
import app.kaiwa.BuildConfig
import app.kaiwa.R
import app.kaiwa.common.ProjectConfig
import app.kaiwa.common.SystemPromptHelper
import app.kaiwa.common.getJsonResponse
import app.kaiwa.common.isAICoreSupported
import app.kaiwa.customtasks.common.CustomTask
import app.kaiwa.data.Accelerator
import app.kaiwa.data.AllowedModel
import app.kaiwa.data.BuiltInTaskId
import app.kaiwa.data.Category
import app.kaiwa.data.CategoryInfo
import app.kaiwa.data.Config
import app.kaiwa.data.ConfigKeys
import app.kaiwa.data.DataStoreRepository
import app.kaiwa.data.DownloadRepository
import app.kaiwa.data.EMPTY_MODEL
import app.kaiwa.data.IMPORTS_DIR
import app.kaiwa.data.Model
import app.kaiwa.data.ModelAllowlist
import app.kaiwa.data.ModelCapability
import app.kaiwa.data.ModelDownloadStatus
import app.kaiwa.data.ModelDownloadStatusType
import app.kaiwa.data.RuntimeType
import app.kaiwa.data.SOC
import app.kaiwa.data.SystemPromptRepository
import app.kaiwa.data.TMP_FILE_EXT
import app.kaiwa.data.Task
import app.kaiwa.data.createLlmChatConfigs
import app.kaiwa.proto.AccessTokenData
import app.kaiwa.proto.ImportedModel
import app.kaiwa.proto.Theme
import app.kaiwa.runtime.aicore.AICoreModelHelper
import com.google.ai.edge.litertlm.Contents
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.ResponseTypeValues

private const val TAG = "AGModelManagerViewModel"

private const val MAX_TEXT_INPUT_HISTORY = 50

private const val ALLOWLIST_FILE = "model_allowlist.json"
private const val ALLOWLIST_TEST_FILE = "model_allowlist_test.json"

private const val ALLOWLIST_REMOTE_ROOT =
  "https://raw.githubusercontent.com/google-ai-edge/gallery/refs/heads/main/model_allowlists"

// Inline override for local testing only; empty means "use the normal sources".
private const val ALLOWLIST_INLINE_OVERRIDE = ""

/** Order in which LLM tasks are presented within the LLM category. */
private val LLM_TASK_DISPLAY_ORDER =
  listOf(
    BuiltInTaskId.LLM_ASK_IMAGE,
    BuiltInTaskId.LLM_ASK_AUDIO,
    BuiltInTaskId.LLM_CHAT,
    BuiltInTaskId.LLM_AGENT_CHAT,
    BuiltInTaskId.LLM_PROMPT_LAB,
    BuiltInTaskId.LLM_MOBILE_ACTIONS,
  )

/** Task ids that an imported LLM may be offered to, gated by its declared capabilities. */
private val IMPORT_TARGET_TASK_IDS =
  setOf(
    BuiltInTaskId.LLM_CHAT,
    BuiltInTaskId.LLM_ASK_IMAGE,
    BuiltInTaskId.LLM_ASK_AUDIO,
    BuiltInTaskId.LLM_PROMPT_LAB,
    BuiltInTaskId.LLM_MOBILE_ACTIONS,
    BuiltInTaskId.LLM_AGENT_CHAT,
  )

data class ModelInitializationStatus(
  val status: ModelInitializationStatusType,
  var error: String = "",
  var initializedBackends: Set<String> = setOf(),
) {
  fun isFirstInitialization(model: Model): Boolean {
    val backend =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    return backend !in initializedBackends
  }
}

enum class ModelInitializationStatusType {
  NOT_INITIALIZED,
  INITIALIZING,
  INITIALIZED,
  ERROR,
}

enum class TokenStatus {
  NOT_STORED,
  EXPIRED,
  NOT_EXPIRED,
}

enum class TokenRequestResultType {
  FAILED,
  SUCCEEDED,
  USER_CANCELLED,
}

data class TokenStatusAndData(val status: TokenStatus, val data: AccessTokenData?)

data class TokenRequestResult(val status: TokenRequestResultType, val errorMessage: String? = null)

data class ModelManagerUiState(
  /** A list of tasks available in the application. */
  val tasks: List<Task>,

  /** Tasks grouped by category. */
  val tasksByCategory: Map<String, List<Task>>,

  /** A map that tracks the download status of each model, indexed by model name. */
  val modelDownloadStatus: Map<String, ModelDownloadStatus>,

  /** A map that tracks the initialization status of each model, indexed by model name. */
  val modelInitializationStatus: Map<String, ModelInitializationStatus>,

  /** Whether the app is loading and processing the model allowlist. */
  val loadingModelAllowlist: Boolean = true,

  /** The error message when loading the model allowlist. */
  val loadingModelAllowlistError: String = "",

  /** The currently selected model. */
  val selectedModel: Model = EMPTY_MODEL,

  /** The history of text inputs entered by the user. */
  val textInputHistory: List<String> = listOf(),
  val configValuesUpdateTrigger: Long = 0L,
  // Updated when model is imported of an imported model is deleted.
  val modelImportingUpdateTrigger: Long = 0L,
) {
  fun isModelInitialized(model: Model): Boolean =
    modelInitializationStatus[model.name]?.status == ModelInitializationStatusType.INITIALIZED

  fun isModelInitializing(model: Model): Boolean =
    modelInitializationStatus[model.name]?.status == ModelInitializationStatusType.INITIALIZING
}

/**
 * Owns the catalog of tasks and models and tracks per-model download and initialization state.
 *
 * The allowlist (remote, with on-disk and bundled fallbacks) plus any user-imported models are
 * folded into the task list, and the resulting [ModelManagerUiState] is exposed through [uiState].
 * Download, deletion, initialization and cleanup all funnel their progress back into that state.
 */
@HiltViewModel
open class ModelManagerViewModel
@Inject
constructor(
  private val downloadRepository: DownloadRepository,
  val dataStoreRepository: DataStoreRepository,
  private val lifecycleProvider: AppLifecycleProvider,
  private val customTasks: Set<@JvmSuppressWildcards CustomTask>,
  private val systemPromptRepository: SystemPromptRepository,
  @ApplicationContext private val context: Context,
) : ViewModel() {
  private val externalFilesDir = context.getExternalFilesDir(null)

  protected val _uiState = MutableStateFlow(emptyUiState())
  open val uiState = _uiState.asStateFlow()

  private val _allowlistModels: MutableList<Model> = mutableListOf()
  val allowlistModels: List<Model>
    get() = _allowlistModels

  val authService = AuthorizationService(context)
  var curAccessToken: String = ""

  override fun onCleared() {
    authService.dispose()
  }

  // ---- Task / model lookups --------------------------------------------------------------------

  // The OK Gemma assistant drives inference through ChatEngine, not the legacy custom-task screens,
  // but the model layer still groups downloadable models by task. These stable built-in tasks give
  // the allowlist models somewhere to attach (one shared on-device LLM, exposed by capability).
  private fun buildInTask(id: String, label: String, icon: ImageVector, description: String) =
    Task(
      id = id,
      label = label,
      category = Category.LLM,
      icon = icon,
      models = mutableListOf(),
      description = description,
    )

  private val builtInTasks: List<Task> =
    listOf(
      buildInTask(BuiltInTaskId.LLM_CHAT, "AI Chat", Icons.Outlined.Forum, "Chat with on-device LLMs"),
      buildInTask(BuiltInTaskId.LLM_ASK_IMAGE, "Ask Image", Icons.Outlined.Mms, "Ask about images"),
      buildInTask(BuiltInTaskId.LLM_ASK_AUDIO, "Ask Audio", Icons.Outlined.Mic, "Ask about audio"),
      buildInTask(
        BuiltInTaskId.LLM_MOBILE_ACTIONS,
        "Mobile Actions",
        Icons.Outlined.TouchApp,
        "Run on-device actions",
      ),
    )

  private fun activeTasks(): List<Task> = customTasks.map { it.task } + builtInTasks

  fun getActiveCustomTasks(): List<CustomTask> = customTasks.toList()

  fun getTaskById(id: String): Task? = uiState.value.tasks.find { it.id == id }

  fun getTasksByIds(ids: Set<String>): List<Task> = uiState.value.tasks.filter { it.id in ids }

  fun getCustomTaskByTaskId(id: String): CustomTask? =
    getActiveCustomTasks().find { it.task.id == id }

  fun getSelectedModel(): Model? = uiState.value.selectedModel

  fun getModelByName(name: String): Model? {
    for (task in uiState.value.tasks) {
      task.models.find { it.name == name }?.let { return it }
    }
    return null
  }

  fun getAllModels(): List<Model> {
    val models = mutableSetOf<Model>()
    for (task in uiState.value.tasks) {
      models.addAll(task.models)
    }
    return models.toList().sortedBy { it.displayName.ifEmpty { it.name } }
  }

  fun getAllDownloadedModels(): List<Model> =
    getAllModels().filter {
      uiState.value.modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED &&
        it.isLlm
    }

  /** Pre-processes every model and floats each task's preferred model to the top of its list. */
  fun processTasks() {
    for (task in activeTasks()) {
      task.models.forEach { it.preProcess() }
      task.models.find { it.bestForTaskIds.contains(task.id) }?.let { best ->
        task.models.remove(best)
        task.models.add(0, best)
      }
    }
  }

  // ---- Selection / config triggers -------------------------------------------------------------

  fun selectModel(model: Model) {
    if (_uiState.value.selectedModel.name != model.name) {
      _uiState.update { it.copy(selectedModel = model) }
    }
  }

  fun updateConfigValuesUpdateTrigger() {
    _uiState.update { it.copy(configValuesUpdateTrigger = System.currentTimeMillis()) }
  }

  // ---- Download lifecycle ----------------------------------------------------------------------

  open fun downloadModel(task: Task?, model: Model) {
    setDownloadStatus(
      curModel = model,
      status = ModelDownloadStatus(status = ModelDownloadStatusType.IN_PROGRESS),
    )

    // TODO: b/494029782 - Both litertlm and aicore download and storage should be unified into a
    // model repository.
    if (model.runtimeType == RuntimeType.AICORE) {
      downloadAICoreModel(model)
      return
    }

    // Clear any leftover files for this model before requesting a fresh download.
    deleteModel(model = model, removeImportedFromModelList = false)

    downloadRepository.downloadModel(task = task, model = model, onStatusUpdated = ::setDownloadStatus)
  }

  private fun downloadAICoreModel(model: Model) {
    AICoreModelHelper.downloadModel(
      context = context,
      coroutineScope = viewModelScope,
      model = model,
      onProgress = { downloaded, total ->
        setDownloadStatus(
          curModel = model,
          status =
            ModelDownloadStatus(
              status = ModelDownloadStatusType.IN_PROGRESS,
              receivedBytes = downloaded,
              totalBytes = total,
            ),
        )
      },
      onDone = {
        setDownloadStatus(
          curModel = model,
          status =
            ModelDownloadStatus(
              status = ModelDownloadStatusType.SUCCEEDED,
              receivedBytes = model.sizeInBytes,
              totalBytes = model.sizeInBytes,
            ),
        )
      },
      onError = { error ->
        setDownloadStatus(
          curModel = model,
          status = ModelDownloadStatus(status = ModelDownloadStatusType.FAILED, errorMessage = error),
        )
      },
    )
  }

  fun cancelDownloadModel(model: Model) {
    // TODO: b/494029782 - Both litertlm and aicore download and storage should be unified into a
    // model repository.
    // AICore models cannot be deleted from the download repository within the app.
    if (model.runtimeType == RuntimeType.AICORE) {
      return
    }
    downloadRepository.cancelDownloadModel(model)
    deleteModel(model = model, removeImportedFromModelList = false)
  }

  fun deleteModel(model: Model, removeImportedFromModelList: Boolean = true) {
    // An updatable model points at a previous version; deleting it resets the model back to its
    // latest declared version and clears the updatable flag.
    if (model.updatable) {
      model.updatable = false
      model.latestModelFile?.let {
        model.version = it.commitHash
        model.downloadFileName = it.fileName
      }
    }

    if (model.imported) {
      deleteFilesFromImportDir(model.downloadFileName)
    } else {
      deleteDirFromExternalFilesDir(model.normalizedName)
    }

    val downloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
    downloadStatus[model.name] = ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED)

    val dropFromList = model.imported && removeImportedFromModelList
    if (dropFromList) {
      removeImportedModelFromTasks(model)
      downloadStatus.remove(model.name)
      removeImportedModelFromStore(model)
    }

    _uiState.update {
      it.copy(
        modelDownloadStatus = downloadStatus,
        tasks = it.tasks.toList(),
        modelImportingUpdateTrigger = System.currentTimeMillis(),
      )
    }
  }

  private fun removeImportedModelFromTasks(model: Model) {
    for (task in uiState.value.tasks) {
      val index = task.models.indexOf(model)
      if (index >= 0) {
        task.models.removeAt(index)
      }
      task.updateTrigger.value = System.currentTimeMillis()
    }
  }

  private fun removeImportedModelFromStore(model: Model) {
    val importedModels = dataStoreRepository.readImportedModels().toMutableList()
    val index = importedModels.indexOfFirst { it.fileName == model.name }
    if (index >= 0) {
      importedModels.removeAt(index)
    }
    dataStoreRepository.saveImportedModels(importedModels = importedModels)
  }

  // ---- Initialization / cleanup lifecycle ------------------------------------------------------

  fun initializeModel(
    context: Context,
    task: Task,
    model: Model,
    force: Boolean = false,
    onDone: () -> Unit = {},
    onError: (String) -> Unit = {},
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      val alreadyInitialized =
        uiState.value.modelInitializationStatus[model.name]?.status ==
          ModelInitializationStatusType.INITIALIZED
      if (!force && alreadyInitialized) {
        Log.d(TAG, "Model '${model.name}' has been initialized. Skipping.")
        return@launch
      }

      if (model.initializing) {
        // Another initialization is already running; cancel any pending cleanup it scheduled.
        model.cleanUpAfterInit = false
        Log.d(TAG, "Model '${model.name}' is being initialized. Skipping.")
        return@launch
      }

      cleanupModel(context = context, task = task, model = model)

      Log.d(TAG, "Initializing model '${model.name}'...")
      model.initializing = true
      updateModelInitializationStatus(model, ModelInitializationStatusType.INITIALIZING)

      val systemPrompt = SystemPromptHelper.getEffectiveSystemPrompt(systemPromptRepository, task)
      getCustomTaskByTaskId(id = task.id)
        ?.initializeModelFn(
          context = context,
          coroutineScope = viewModelScope,
          model = model,
          systemInstruction = Contents.of(systemPrompt),
          onDone = onInitializeDone(context, task, model, onDone, onError),
        )
    }
  }

  private fun onInitializeDone(
    context: Context,
    task: Task,
    model: Model,
    onDone: () -> Unit,
    onError: (String) -> Unit,
  ): (error: String) -> Unit = { error ->
    model.initializing = false
    if (model.instance != null) {
      Log.d(TAG, "Model '${model.name}' initialized successfully")
      updateModelInitializationStatus(model, ModelInitializationStatusType.INITIALIZED)
      if (model.cleanUpAfterInit) {
        Log.d(TAG, "Model '${model.name}' needs cleaning up after init.")
        cleanupModel(context = context, task = task, model = model)
      }
      onDone()
    } else if (error.isNotEmpty()) {
      Log.d(TAG, "Model '${model.name}' failed to initialize")
      updateModelInitializationStatus(model, ModelInitializationStatusType.ERROR, error = error)
      onError(error)
    }
  }

  fun cleanupModel(
    context: Context,
    task: Task,
    model: Model,
    instanceToCleanUp: Any? = model.instance,
    onDone: () -> Unit = {},
  ) {
    if (instanceToCleanUp != null && instanceToCleanUp !== model.instance) {
      Log.d(TAG, "Stale cleanup request for ${model.name}. Aborting.")
      onDone()
      return
    }

    if (model.instance != null) {
      model.cleanUpAfterInit = false
      Log.d(TAG, "Cleaning up model '${model.name}'...")
      val onDoneFn: () -> Unit = {
        model.instance = null
        model.initializing = false
        updateModelInitializationStatus(model, ModelInitializationStatusType.NOT_INITIALIZED)
        Log.d(TAG, "Clean up model '${model.name}' done")
        onDone()
      }
      getCustomTaskByTaskId(id = task.id)
        ?.cleanUpModelFn(
          context = context,
          coroutineScope = viewModelScope,
          model = model,
          onDone = onDoneFn,
        )
    } else if (model.initializing) {
      // Cleanup arrived mid-initialization; defer it until that finishes.
      Log.d(
        TAG,
        "Model '${model.name}' is still initializing.. Will clean up after it is done initializing",
      )
      model.cleanUpAfterInit = true
    }
  }

  // ---- Status mutators -------------------------------------------------------------------------

  fun setDownloadStatus(curModel: Model, status: ModelDownloadStatus) {
    val downloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
    downloadStatus[curModel.name] = status
    // A failed or reset download leaves no useful file behind.
    if (
      status.status == ModelDownloadStatusType.FAILED ||
        status.status == ModelDownloadStatusType.NOT_DOWNLOADED
    ) {
      deleteFileFromExternalFilesDir(curModel.downloadFileName)
    }
    _uiState.update { it.copy(modelDownloadStatus = downloadStatus) }
  }

  fun setInitializationStatus(model: Model, status: ModelInitializationStatus) {
    val statuses = uiState.value.modelInitializationStatus.toMutableMap()
    if (statuses.containsKey(model.name)) {
      val existingBackends = statuses[model.name]?.initializedBackends ?: setOf()
      statuses[model.name] =
        status.copy(initializedBackends = mergedBackends(model, status.status, existingBackends))
      _uiState.update { it.copy(modelInitializationStatus = statuses) }
    }
  }

  private fun updateModelInitializationStatus(
    model: Model,
    status: ModelInitializationStatusType,
    error: String = "",
  ) {
    val statuses = uiState.value.modelInitializationStatus.toMutableMap()
    val existingBackends = statuses[model.name]?.initializedBackends ?: setOf()
    statuses[model.name] =
      ModelInitializationStatus(
        status = status,
        error = error,
        initializedBackends = mergedBackends(model, status, existingBackends),
      )
    _uiState.update { it.copy(modelInitializationStatus = statuses) }
  }

  private fun mergedBackends(
    model: Model,
    status: ModelInitializationStatusType,
    existing: Set<String>,
  ): Set<String> {
    if (status != ModelInitializationStatusType.INITIALIZED) {
      return existing
    }
    val backend =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    return existing + backend
  }

  // ---- Text input history ----------------------------------------------------------------------

  fun addTextInputHistory(text: String) {
    if (uiState.value.textInputHistory.indexOf(text) < 0) {
      val history = uiState.value.textInputHistory.toMutableList()
      history.add(0, text)
      if (history.size > MAX_TEXT_INPUT_HISTORY) {
        history.removeAt(history.size - 1)
      }
      persistTextInputHistory(history)
    } else {
      promoteTextInputHistoryItem(text)
    }
  }

  fun promoteTextInputHistoryItem(text: String) {
    val index = uiState.value.textInputHistory.indexOf(text)
    if (index >= 0) {
      val history = uiState.value.textInputHistory.toMutableList()
      history.removeAt(index)
      history.add(0, text)
      persistTextInputHistory(history)
    }
  }

  fun deleteTextInputHistory(text: String) {
    val index = uiState.value.textInputHistory.indexOf(text)
    if (index >= 0) {
      val history = uiState.value.textInputHistory.toMutableList()
      history.removeAt(index)
      persistTextInputHistory(history)
    }
  }

  fun clearTextInputHistory() {
    persistTextInputHistory(mutableListOf())
  }

  private fun persistTextInputHistory(history: List<String>) {
    _uiState.update { it.copy(textInputHistory = history) }
    dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
  }

  // ---- Theme -----------------------------------------------------------------------------------

  fun readThemeOverride(): Theme = dataStoreRepository.readTheme()

  fun saveThemeOverride(theme: Theme) {
    dataStoreRepository.saveTheme(theme = theme)
  }

  // ---- Network probe ---------------------------------------------------------------------------

  fun getModelUrlResponse(model: Model, accessToken: String? = null): Int {
    return try {
      val connection = URL(model.url).openConnection() as HttpURLConnection
      if (accessToken != null) {
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
      }
      connection.connect()
      connection.responseCode
    } catch (e: Exception) {
      Log.e(TAG, "$e")
      -1
    }
  }

  // ---- Imported models -------------------------------------------------------------------------

  fun addImportedLlmModel(info: ImportedModel) {
    Log.d(TAG, "adding imported llm model: $info")

    File(context.getExternalFilesDir(null), IMPORTS_DIR).let { dir ->
      if (!dir.exists()) dir.mkdirs()
    }

    val model = createModelFromImportedModelInfo(info = info)
    attachImportedModelToTasks(info = info, model = model)

    val downloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
    val initStatus = uiState.value.modelInitializationStatus.toMutableMap()
    downloadStatus[model.name] = importedModelDownloadStatus(model, info.fileSize)
    initStatus[model.name] = ModelInitializationStatus(ModelInitializationStatusType.NOT_INITIALIZED)

    _uiState.update {
      it.copy(
        tasks = it.tasks.toList(),
        modelDownloadStatus = downloadStatus,
        modelInitializationStatus = initStatus,
        modelImportingUpdateTrigger = System.currentTimeMillis(),
      )
    }

    persistImportedModel(info)
  }

  private fun attachImportedModelToTasks(info: ImportedModel, model: Model) {
    for (task in getTasksByIds(ids = IMPORT_TARGET_TASK_IDS)) {
      // Replace any earlier import of the same file.
      val existing = task.models.indexOfFirst { info.fileName == it.name && it.imported }
      if (existing >= 0) {
        Log.d(TAG, "duplicated imported model found in task. Removing it first")
        task.models.removeAt(existing)
      }
      if (importedModelFitsTask(task.id, model)) {
        task.models.add(model)
      }
      task.updateTrigger.value = System.currentTimeMillis()
    }
  }

  private fun importedModelFitsTask(taskId: String, model: Model): Boolean =
    when (taskId) {
      BuiltInTaskId.LLM_ASK_IMAGE -> model.llmSupportImage
      BuiltInTaskId.LLM_ASK_AUDIO -> model.llmSupportAudio
      BuiltInTaskId.LLM_MOBILE_ACTIONS -> model.llmSupportMobileActions
      else -> true
    }

  private fun importedModelDownloadStatus(model: Model, fileSize: Long): ModelDownloadStatus =
    if (model.url.isNotEmpty()) {
      getModelDownloadStatus(model = model)
    } else {
      ModelDownloadStatus(
        status = ModelDownloadStatusType.SUCCEEDED,
        receivedBytes = fileSize,
        totalBytes = fileSize,
      )
    }

  private fun persistImportedModel(info: ImportedModel) {
    val importedModels = dataStoreRepository.readImportedModels().toMutableList()
    val existing = importedModels.indexOfFirst { info.fileName == it.fileName }
    if (existing >= 0) {
      Log.d(TAG, "duplicated imported model found in data store. Removing it first")
      importedModels.removeAt(existing)
    }
    importedModels.add(info)
    dataStoreRepository.saveImportedModels(importedModels = importedModels)
  }

  private fun createModelFromImportedModelInfo(info: ImportedModel): Model {
    val accelerators =
      info.llmConfig.compatibleAcceleratorsList
        .mapNotNull { label ->
          when (label.trim()) {
            Accelerator.GPU.label -> Accelerator.GPU
            Accelerator.CPU.label -> Accelerator.CPU
            Accelerator.NPU.label -> Accelerator.NPU
            else -> null
          }
        }
        .toMutableList()

    val cfg = info.llmConfig
    val configs: MutableList<Config> =
      createLlmChatConfigs(
          defaultMaxToken = cfg.defaultMaxTokens,
          defaultTopK = cfg.defaultTopk,
          defaultTopP = cfg.defaultTopp,
          defaultTemperature = cfg.defaultTemperature,
          accelerators = accelerators,
          supportThinking = cfg.supportThinking,
          supportSpeculativeDecoding = cfg.supportSpeculativeDecoding,
        )
        .toMutableList()

    val capabilities = mutableListOf<ModelCapability>()
    val capabilityToTaskTypes = mutableMapOf<ModelCapability, List<String>>()
    if (cfg.supportThinking) {
      capabilities.add(ModelCapability.LLM_THINKING)
      capabilityToTaskTypes[ModelCapability.LLM_THINKING] =
        listOf(BuiltInTaskId.LLM_CHAT, BuiltInTaskId.LLM_ASK_IMAGE, BuiltInTaskId.LLM_ASK_AUDIO)
    }
    if (cfg.supportSpeculativeDecoding) {
      capabilities.add(ModelCapability.SPECULATIVE_DECODING)
      capabilityToTaskTypes[ModelCapability.SPECULATIVE_DECODING] =
        listOf(
          BuiltInTaskId.LLM_CHAT,
          BuiltInTaskId.LLM_ASK_IMAGE,
          BuiltInTaskId.LLM_ASK_AUDIO,
          BuiltInTaskId.LLM_PROMPT_LAB,
        )
    }

    return Model(
        name = info.fileName,
        url = info.url,
        configs = configs,
        sizeInBytes = info.fileSize,
        downloadFileName = info.fileName,
        showBenchmarkButton = false,
        showRunAgainButton = false,
        imported = true,
        llmSupportImage = cfg.supportImage,
        llmSupportAudio = cfg.supportAudio,
        llmSupportMobileActions = cfg.supportMobileActions,
        capabilities = capabilities.toList(),
        capabilityToTaskTypes = capabilityToTaskTypes.toMap(),
        llmMaxToken = cfg.defaultMaxTokens,
        accelerators = accelerators,
        // Imported models are assumed to be LLMs.
        isLlm = true,
        runtimeType = RuntimeType.LITERT_LM,
      )
      .also { it.preProcess() }
  }

  // ---- OAuth token handling --------------------------------------------------------------------

  fun getTokenStatusAndData(): TokenStatusAndData {
    Log.d(TAG, "Reading token data from data store...")
    val tokenData = dataStoreRepository.readAccessTokenData()

    if (tokenData == null || tokenData.accessToken.isEmpty()) {
      Log.d(TAG, "Token doesn't exists.")
      return TokenStatusAndData(status = TokenStatus.NOT_STORED, data = tokenData)
    }

    Log.d(TAG, "Token exists and loaded.")
    val now = System.currentTimeMillis()
    // Expire 5 minutes early to avoid races near the boundary.
    val expiresAt = tokenData.expiresAtMs - 5 * 60
    Log.d(TAG, "Checking whether token has expired or not. Current ts: $now, expires at: $expiresAt")

    val status =
      if (now >= expiresAt) {
        Log.d(TAG, "Token expired!")
        TokenStatus.EXPIRED
      } else {
        Log.d(TAG, "Token not expired.")
        curAccessToken = tokenData.accessToken
        TokenStatus.NOT_EXPIRED
      }
    return TokenStatusAndData(status = status, data = tokenData)
  }

  fun getAuthorizationRequest(): AuthorizationRequest =
    AuthorizationRequest.Builder(
        ProjectConfig.authServiceConfig,
        ProjectConfig.clientId,
        ResponseTypeValues.CODE,
        ProjectConfig.redirectUri.toUri(),
      )
      .setScope("read-repos")
      .build()

  fun handleAuthResult(result: ActivityResult, onTokenRequested: (TokenRequestResult) -> Unit) {
    val dataIntent = result.data
    if (dataIntent == null) {
      onTokenRequested(
        TokenRequestResult(status = TokenRequestResultType.FAILED, errorMessage = "Empty auth result")
      )
      return
    }

    val response = AuthorizationResponse.fromIntent(dataIntent)
    val exception = AuthorizationException.fromIntent(dataIntent)

    when {
      response?.authorizationCode != null -> exchangeAuthorizationCode(response, onTokenRequested)
      exception != null ->
        onTokenRequested(
          TokenRequestResult(
            status =
              if (exception.message == "User cancelled flow") TokenRequestResultType.USER_CANCELLED
              else TokenRequestResultType.FAILED,
            errorMessage = exception.message,
          )
        )
      else -> onTokenRequested(TokenRequestResult(status = TokenRequestResultType.USER_CANCELLED))
    }
  }

  private fun exchangeAuthorizationCode(
    response: AuthorizationResponse,
    onTokenRequested: (TokenRequestResult) -> Unit,
  ) {
    authService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, tokenEx ->
      val errorMessage: String? =
        when {
          tokenResponse != null && tokenResponse.accessToken == null -> "Empty access token"
          tokenResponse != null && tokenResponse.refreshToken == null -> "Empty refresh token"
          tokenResponse != null && tokenResponse.accessTokenExpirationTime == null ->
            "Empty expiration time"
          tokenResponse != null -> {
            Log.d(TAG, "Token exchange successful. Storing tokens...")
            saveAccessToken(
              accessToken = tokenResponse.accessToken!!,
              refreshToken = tokenResponse.refreshToken!!,
              expiresAt = tokenResponse.accessTokenExpirationTime!!,
            )
            curAccessToken = tokenResponse.accessToken!!
            Log.d(TAG, "Token successfully saved.")
            null
          }
          tokenEx != null -> "Token exchange failed: ${tokenEx.message}"
          else -> "Token exchange failed"
        }

      if (errorMessage == null) {
        onTokenRequested(TokenRequestResult(status = TokenRequestResultType.SUCCEEDED))
      } else {
        onTokenRequested(
          TokenRequestResult(status = TokenRequestResultType.FAILED, errorMessage = errorMessage)
        )
      }
    }
  }

  fun saveAccessToken(accessToken: String, refreshToken: String, expiresAt: Long) {
    dataStoreRepository.saveAccessTokenData(
      accessToken = accessToken,
      refreshToken = refreshToken,
      expiresAt = expiresAt,
    )
  }

  fun clearAccessToken() {
    dataStoreRepository.clearAccessTokenData()
  }

  // ---- AICore / pending download bootstrap -----------------------------------------------------

  // TODO: b/494029782 - Both litertlm and aicore download and storage should be unified into a
  // model repository.
  private fun checkAICoreModelStatuses() {
    viewModelScope.launch(Dispatchers.Main) {
      val aicoreModels =
        uiState.value.tasks
          .flatMap { it.models }
          .filter { it.runtimeType == RuntimeType.AICORE }
          .distinctBy { it.name }
      // Eagerly kick off AICore downloads at startup.
      for (model in aicoreModels) {
        downloadModel(task = null, model = model)
      }
    }
  }

  private fun processPendingDownloads() {
    downloadRepository.cancelAll {
      Log.d(TAG, "All workers are cancelled.")

      viewModelScope.launch(Dispatchers.Main) {
        val tokenStatusAndData = getTokenStatusAndData()
        val seen = mutableSetOf<String>()
        for (task in uiState.value.tasks) {
          for (model in task.models) {
            if (!seen.add(model.name)) {
              continue
            }
            if (
              uiState.value.modelDownloadStatus[model.name]?.status ==
                ModelDownloadStatusType.PARTIALLY_DOWNLOADED
            ) {
              if (
                tokenStatusAndData.status == TokenStatus.NOT_EXPIRED &&
                  tokenStatusAndData.data != null
              ) {
                model.accessToken = tokenStatusAndData.data.accessToken
              }
              Log.d(TAG, "Sending a new download request for '${model.name}'")
              downloadRepository.downloadModel(
                task = task,
                model = model,
                onStatusUpdated = ::setDownloadStatus,
              )
            }
          }
        }
      }
    }
  }

  // ---- Allowlist loading -----------------------------------------------------------------------

  fun loadModelAllowlist() {
    _uiState.update { it.copy(loadingModelAllowlist = true, loadingModelAllowlistError = "") }

    viewModelScope.launch(Dispatchers.IO) {
      try {
        _allowlistModels.clear()

        val allowlist = resolveModelAllowlist()
        if (allowlist == null) {
          _uiState.update { it.copy(loadingModelAllowlistError = "Failed to load model list") }
          return@launch
        }

        Log.d(TAG, "Allowlist: $allowlist")
        populateTasksFromAllowlist(allowlist)

        processTasks()
        _uiState.update {
          createUiState()
            .copy(
              loadingModelAllowlist = false,
              tasks = activeTasks(),
              tasksByCategory = groupTasksByCategory(),
            )
        }

        processPendingDownloads()
        checkAICoreModelStatuses()
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }
  }

  /** Resolves the allowlist from, in order: a test file, an inline override, the network, then on-disk and bundled fallbacks. */
  private fun resolveModelAllowlist(): ModelAllowlist? {
    Log.d(TAG, "Loading test model allowlist.")
    var allowlist = readModelAllowlistFromDisk(fileName = ALLOWLIST_TEST_FILE)

    if (ALLOWLIST_INLINE_OVERRIDE.isNotEmpty()) {
      Log.d(TAG, "Loading local model allowlist for testing.")
      try {
        allowlist = Gson().fromJson(ALLOWLIST_INLINE_OVERRIDE, ModelAllowlist::class.java)
      } catch (e: JsonSyntaxException) {
        Log.e(TAG, "Failed to parse local test json", e)
      }
    }

    if (allowlist == null) {
      val version = BuildConfig.VERSION_NAME.replace(".", "_")
      val url = allowlistUrlFor(version)
      Log.d(TAG, "Loading model allowlist from internet. Url: $url")
      val response = getJsonResponse<ModelAllowlist>(url = url)
      allowlist = response?.jsonObj
      if (allowlist == null) {
        Log.w(TAG, "Failed to load model allowlist from internet. Trying to load it from disk")
        allowlist = readModelAllowlistFromDisk()
      } else {
        Log.d(TAG, "Done: loading model allowlist from internet")
        saveModelAllowlistToDisk(modelAllowlistContent = response?.textContent ?: "{}")
      }
    }

    // Final fallback: a catalog bundled with the app so Gemma works even when the versioned
    // allowlist URL is missing (e.g. HTTP 404) or the network is blocked.
    if (allowlist == null) {
      Log.w(TAG, "Falling back to the model allowlist bundled in assets")
      allowlist = readModelAllowlistFromAssets()
    }

    return allowlist
  }

  private fun populateTasksFromAllowlist(allowlist: ModelAllowlist) {
    val aicoreAvailable by lazy {
      val allowedDeviceModels =
        allowlist.aicoreRequirements
          ?.allowedDeviceGroups
          ?.asSequence()
          ?.flatMap { it.deviceModels }
          ?.map { it.lowercase() }
          ?.toSet()
      isAICoreSupported(allowedDeviceModels)
    }

    val tasks = activeTasks()
    val byName = mutableMapOf<String, Model>()

    for (allowedModel in allowlist.models) {
      if (allowedModel.disabled == true) continue
      if (allowedModel.runtimeType == RuntimeType.AICORE && !aicoreAvailable) continue
      if (isNpuOnlyUnsupported(allowedModel)) {
        Log.d(
          TAG,
          "Ignoring model '${allowedModel.name}' because it's NPU-only and not supported on SOC: $SOC",
        )
        continue
      }

      val model = allowedModel.toModel()
      _allowlistModels.add(model)
      byName[model.name] = model
      for (taskType in allowedModel.taskTypes) {
        tasks.find { it.id == taskType }?.models?.add(model)
      }
    }

    // Resolve tasks that pin specific model names.
    for (task in tasks) {
      if (task.modelNames.isEmpty()) continue
      for (modelName in task.modelNames) {
        val model = byName[modelName]
        if (model == null) {
          Log.w(TAG, "Model '$modelName' in task '${task.label}' not found in allowlist.")
          continue
        }
        task.models.add(model)
      }
    }
  }

  private fun isNpuOnlyUnsupported(allowedModel: AllowedModel): Boolean {
    val accelerators =
      (allowedModel.defaultConfig.accelerators ?: "")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (accelerators.size != 1 || accelerators[0] != "npu") {
      return false
    }
    val socToModelFiles = allowedModel.socToModelFiles
    return socToModelFiles != null && !socToModelFiles.containsKey(SOC)
  }

  fun clearLoadModelAllowlistError() {
    processTasks()
    _uiState.update {
      createUiState()
        .copy(
          loadingModelAllowlist = false,
          tasks = activeTasks(),
          loadingModelAllowlistError = "",
          tasksByCategory = groupTasksByCategory(),
        )
    }
  }

  fun setAppInForeground(foreground: Boolean) {
    lifecycleProvider.isAppInForeground = foreground
  }

  // ---- Allowlist persistence -------------------------------------------------------------------

  private fun saveModelAllowlistToDisk(modelAllowlistContent: String) {
    try {
      Log.d(TAG, "Saving model allowlist to disk...")
      File(externalFilesDir, ALLOWLIST_FILE).writeText(modelAllowlistContent)
      Log.d(TAG, "Done: saving model allowlist to disk.")
    } catch (e: Exception) {
      Log.e(TAG, "failed to write model allowlist to disk", e)
    }
  }

  private fun readModelAllowlistFromAssets(): ModelAllowlist? =
    try {
      val content = context.assets.open(ALLOWLIST_FILE).bufferedReader().use { it.readText() }
      Gson().fromJson(content, ModelAllowlist::class.java)
    } catch (e: Exception) {
      Log.e(TAG, "failed to read model allowlist from assets", e)
      null
    }

  private fun readModelAllowlistFromDisk(fileName: String = ALLOWLIST_FILE): ModelAllowlist? {
    return try {
      Log.d(TAG, "Reading model allowlist from disk: $fileName")
      val baseDir =
        if (fileName == ALLOWLIST_TEST_FILE) File("/data/local/tmp") else externalFilesDir
      val file = File(baseDir, fileName)
      if (!file.exists()) {
        return null
      }
      val content = file.readText()
      Log.d(TAG, "Model allowlist content from local file: $content")
      Gson().fromJson(content, ModelAllowlist::class.java)
    } catch (e: Exception) {
      Log.e(TAG, "failed to read model allowlist from disk", e)
      null
    }
  }

  // ---- UI state construction -------------------------------------------------------------------

  private fun emptyUiState(): ModelManagerUiState =
    ModelManagerUiState(
      tasks = listOf(),
      tasksByCategory = mapOf(),
      modelDownloadStatus = mapOf(),
      modelInitializationStatus = mapOf(),
    )

  private fun createUiState(): ModelManagerUiState {
    val downloadStatus = mutableMapOf<String, ModelDownloadStatus>()
    val initStatus = mutableMapOf<String, ModelInitializationStatus>()
    val taskById = mutableMapOf<String, Task>()
    val seen = mutableSetOf<String>()

    for (task in activeTasks()) {
      taskById[task.id] = task
      for (model in task.models) {
        if (!seen.add(model.name)) {
          continue
        }
        downloadStatus[model.name] = getModelDownloadStatus(model = model)
        initStatus[model.name] = ModelInitializationStatus(ModelInitializationStatusType.NOT_INITIALIZED)
      }
    }

    for (importedModel in dataStoreRepository.readImportedModels()) {
      Log.d(TAG, "stored imported model: $importedModel")
      val model = createModelFromImportedModelInfo(info = importedModel)

      taskById[BuiltInTaskId.LLM_CHAT]?.models?.add(model)
      taskById[BuiltInTaskId.LLM_PROMPT_LAB]?.models?.add(model)
      taskById[BuiltInTaskId.LLM_AGENT_CHAT]?.models?.add(model)
      if (model.llmSupportImage) taskById[BuiltInTaskId.LLM_ASK_IMAGE]?.models?.add(model)
      if (model.llmSupportAudio) taskById[BuiltInTaskId.LLM_ASK_AUDIO]?.models?.add(model)
      if (model.llmSupportMobileActions) {
        taskById[BuiltInTaskId.LLM_MOBILE_ACTIONS]?.models?.add(model)
      }

      downloadStatus[model.name] = importedModelDownloadStatus(model, importedModel.fileSize)
    }

    val textInputHistory = dataStoreRepository.readTextInputHistory()
    Log.d(TAG, "text input history: $textInputHistory")
    Log.d(TAG, "model download status: $downloadStatus")

    return ModelManagerUiState(
      tasks = activeTasks().toList(),
      tasksByCategory = mapOf(),
      modelDownloadStatus = downloadStatus,
      modelInitializationStatus = initStatus,
      textInputHistory = textInputHistory,
    )
  }

  private fun groupTasksByCategory(): Map<String, List<Task>> {
    val tasks = activeTasks()
    val categoryById: Map<String, CategoryInfo> =
      tasks.associateBy { it.category.id }.mapValues { it.value.category }

    val grouped = tasks.groupBy { it.category.id }
    val result = mutableMapOf<String, List<Task>>()
    for (categoryId in grouped.keys) {
      val sorted =
        grouped.getValue(categoryId).sortedWith(taskComparatorFor(categoryId, categoryById))
      sorted.forEachIndexed { index, task -> task.index = index }
      result[categoryId] = sorted
    }
    return result
  }

  /**
   * LLM tasks follow [LLM_TASK_DISPLAY_ORDER], falling back to category-label order for anything
   * not in that list; every other category is sorted alphabetically by task label.
   */
  private fun taskComparatorFor(
    categoryId: String,
    categoryById: Map<String, CategoryInfo>,
  ): Comparator<Task> = Comparator { a, b ->
    if (categoryId != Category.LLM.id) {
      return@Comparator a.label.compareTo(b.label)
    }
    val indexA = LLM_TASK_DISPLAY_ORDER.indexOf(a.id)
    val indexB = LLM_TASK_DISPLAY_ORDER.indexOf(b.id)
    when {
      indexA != -1 && indexB != -1 -> indexA.compareTo(indexB)
      indexA != -1 -> -1
      indexB != -1 -> 1
      else -> {
        val labelA = getCategoryLabel(context, categoryById.getValue(a.id))
        val labelB = getCategoryLabel(context, categoryById.getValue(b.id))
        labelA.compareTo(labelB)
      }
    }
  }

  private fun getCategoryLabel(context: Context, category: CategoryInfo): String {
    category.labelStringRes?.let { return context.getString(it) }
    category.label?.let { return it }
    return context.getString(R.string.category_unlabeled)
  }

  // ---- Download status / file inspection -------------------------------------------------------

  /**
   * Reports whether [model] is fully downloaded, partially downloaded, or not present, including
   * received/total byte counts for partial downloads.
   */
  private fun getModelDownloadStatus(model: Model): ModelDownloadStatus {
    Log.d(TAG, "Checking model ${model.name} download status...")

    if (model.localFileRelativeDirPathOverride.isNotEmpty()) {
      Log.d(TAG, "Model has localFileRelativeDirPathOverride set. Set status to SUCCEEDED")
      return ModelDownloadStatus(
        status = ModelDownloadStatusType.SUCCEEDED,
        receivedBytes = 0,
        totalBytes = 0,
      )
    }

    if (isModelPartiallyDownloaded(model = model)) {
      val tmpFile = File(tmpFilePath(model))
      val receivedBytes = tmpFile.length()
      val totalBytes = model.totalBytes
      Log.d(TAG, "${model.name} is partially downloaded. $receivedBytes/$totalBytes")
      return ModelDownloadStatus(
        status = ModelDownloadStatusType.PARTIALLY_DOWNLOADED,
        receivedBytes = receivedBytes,
        totalBytes = totalBytes,
      )
    }

    if (isModelDownloaded(model = model)) {
      Log.d(TAG, "${model.name} has been downloaded.")
      return ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED)
    }

    Log.d(TAG, "${model.name} has not been downloaded.")
    return ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED)
  }

  private fun tmpFilePath(model: Model): String =
    model.getPath(context = context, fileName = "${model.downloadFileName}.$TMP_FILE_EXT")

  private fun isModelPartiallyDownloaded(model: Model): Boolean {
    if (model.localModelFilePathOverride.isNotEmpty()) {
      return false
    }
    // The presence of the tmp file marks an interrupted download.
    return File(tmpFilePath(model)).exists()
  }

  fun isModelDownloaded(model: Model): Boolean {
    model.updatable = false
    // First check the current (latest) version.
    if (checkIfModelDownloaded(model, model.version)) {
      return true
    }
    // Otherwise look for any previously-downloaded updatable version on the device.
    for (updatableFile in model.updatableModelFiles) {
      if (updatableFile.commitHash.isEmpty()) {
        continue
      }
      if (checkIfModelDownloaded(model, updatableFile.commitHash, updatableFile.fileName)) {
        model.version = updatableFile.commitHash
        model.downloadFileName = updatableFile.fileName
        model.updatable = true
        return true
      }
    }
    return false
  }

  private fun checkIfModelDownloaded(
    model: Model,
    version: String,
    fileName: String = model.downloadFileName,
  ): Boolean {
    val relativePath =
      if (model.imported) {
        listOf(IMPORTS_DIR, fileName).joinToString(File.separator)
      } else {
        listOf(model.normalizedName, version, fileName).joinToString(File.separator)
      }

    val fileExists =
      fileName.isNotEmpty() &&
        ((model.localModelFilePathOverride.isEmpty() && isFileInExternalFilesDir(relativePath)) ||
          (model.localModelFilePathOverride.isNotEmpty() &&
            File(model.localModelFilePathOverride).exists()))

    val unzippedDirExists =
      model.isZip &&
        model.unzipDir.isNotEmpty() &&
        isFileInExternalFilesDir(
          listOf(model.normalizedName, version, model.unzipDir).joinToString(File.separator)
        )

    return fileExists || unzippedDirExists
  }

  // ---- File helpers ----------------------------------------------------------------------------

  private fun isFileInExternalFilesDir(fileName: String): Boolean {
    val dir = externalFilesDir ?: return false
    return File(dir, fileName).exists()
  }

  private fun deleteFileFromExternalFilesDir(fileName: String) {
    if (isFileInExternalFilesDir(fileName)) {
      File(externalFilesDir, fileName).delete()
    }
  }

  /** Deletes any files in the imports directory whose absolute path starts with [fileName]'s prefix. */
  private fun deleteFilesFromImportDir(fileName: String) {
    val dir = context.getExternalFilesDir(null) ?: return
    val prefix =
      "${context.getExternalFilesDir(null)}${File.separator}$IMPORTS_DIR${File.separator}$fileName"
    val matches =
      File(dir, IMPORTS_DIR).listFiles { dirFile, name ->
        File(dirFile, name).absolutePath.startsWith(prefix)
      } ?: arrayOf()
    for (file in matches) {
      Log.d(TAG, "Deleting file: ${file.name}")
      file.delete()
    }
  }

  private fun deleteDirFromExternalFilesDir(dir: String) {
    if (isFileInExternalFilesDir(dir)) {
      File(externalFilesDir, dir).deleteRecursively()
    }
  }

  private fun allowlistUrlFor(version: String): String = "$ALLOWLIST_REMOTE_ROOT/$version.json"
}
