// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import app.kaiwa.proto.UserData
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

/** DataStore [Serializer] backing the persisted [UserData] proto. */
object UserDataSerializer : Serializer<UserData> {
  override val defaultValue: UserData = UserData.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): UserData =
    try {
      UserData.parseFrom(input)
    } catch (e: InvalidProtocolBufferException) {
      throw CorruptionException("Cannot read proto.", e)
    }

  override suspend fun writeTo(t: UserData, output: OutputStream) = t.writeTo(output)
}
