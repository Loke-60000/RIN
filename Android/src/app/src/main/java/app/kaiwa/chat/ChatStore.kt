// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import tech.turso.libsql.Database
import tech.turso.libsql.Libsql

private const val TAG = "AGChatStore"

/**
 * Persists conversations in a local Turso/libSQL embedded database (`ok_gemma.db`). Messages are
 * stored as a JSON blob per conversation. The libSQL handle is opened lazily and all access is
 * guarded so that, if the native library is unavailable on a given device/ABI, chat simply runs
 * without persistence instead of crashing.
 *
 * The same libSQL handle can later be opened as an embedded replica
 * (`Libsql.open(path, url, authToken)`) to sync chat history to a Turso cloud database.
 */
class ChatStore(context: Context) {
  private val gson = Gson()
  private val dbPath = File(context.filesDir, "ok_gemma.db").absolutePath
  private val messageListType = object : TypeToken<MutableList<ChatMessage>>() {}.type

  private val db: Database? by lazy {
    try {
      Libsql.open(path = dbPath).also { database ->
        database.connect().use { conn ->
          conn.execute(
            """
            CREATE TABLE IF NOT EXISTS conversations (
              id TEXT PRIMARY KEY,
              title TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL,
              messages_json TEXT NOT NULL
            )
            """
              .trimIndent()
          )
        }
      }
    } catch (e: Throwable) {
      Log.e(TAG, "libSQL unavailable; chat history disabled", e)
      null
    }
  }

  val isAvailable: Boolean
    get() = db != null

  fun listConversations(): List<Conversation> {
    val database = db ?: return emptyList()
    return try {
      database.connect().use { conn ->
        conn
          .query(
            "SELECT id, title, created_at, updated_at, messages_json FROM conversations ORDER BY updated_at DESC"
          )
          .use { rows ->
            rows.mapNotNull { row ->
              try {
                Conversation(
                  id = row[0] as String,
                  title = row[1] as String,
                  createdAt = row[2] as Long,
                  updatedAt = row[3] as Long,
                  messages = gson.fromJson(row[4] as String, messageListType),
                )
              } catch (e: Exception) {
                Log.w(TAG, "Skipping unreadable conversation row", e)
                null
              }
            }
          }
      }
    } catch (e: Throwable) {
      Log.e(TAG, "Failed to list conversations", e)
      emptyList()
    }
  }

  fun save(conversation: Conversation) {
    val database = db ?: return
    try {
      database.connect().use { conn ->
        conn.execute(
          "INSERT OR REPLACE INTO conversations (id, title, created_at, updated_at, messages_json) VALUES (?, ?, ?, ?, ?)",
          conversation.id,
          conversation.title,
          conversation.createdAt,
          conversation.updatedAt,
          gson.toJson(conversation.messages),
        )
      }
    } catch (e: Throwable) {
      Log.e(TAG, "Failed to save conversation ${conversation.id}", e)
    }
  }

  fun delete(id: String) {
    val database = db ?: return
    try {
      database.connect().use { conn -> conn.execute("DELETE FROM conversations WHERE id = ?", id) }
    } catch (e: Throwable) {
      Log.e(TAG, "Failed to delete conversation $id", e)
    }
  }

  fun deleteAll() {
    val database = db ?: return
    try {
      database.connect().use { conn -> conn.execute("DELETE FROM conversations") }
    } catch (e: Throwable) {
      Log.e(TAG, "Failed to delete all conversations", e)
    }
  }
}
