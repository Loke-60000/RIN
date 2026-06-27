// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import app.kaiwa.proto.Skills
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

/** DataStore [Serializer] backing the persisted [Skills] proto. */
object SkillsSerializer : Serializer<Skills> {
  override val defaultValue: Skills = Skills.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): Skills =
    try {
      Skills.parseFrom(input)
    } catch (e: InvalidProtocolBufferException) {
      throw CorruptionException("Cannot read proto.", e)
    }

  override suspend fun writeTo(t: Skills, output: OutputStream) = t.writeTo(output)
}
