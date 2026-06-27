// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import app.kaiwa.proto.Settings
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

/** DataStore [Serializer] backing the persisted [Settings] proto. */
object SettingsSerializer : Serializer<Settings> {
  override val defaultValue: Settings = Settings.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): Settings =
    try {
      Settings.parseFrom(input)
    } catch (e: InvalidProtocolBufferException) {
      throw CorruptionException("Cannot read proto.", e)
    }

  override suspend fun writeTo(t: Settings, output: OutputStream) = t.writeTo(output)
}
