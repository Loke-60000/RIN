// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import app.kaiwa.proto.CutoutCollection
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

/** DataStore [Serializer] backing the persisted [CutoutCollection] proto. */
object CutoutsSerializer : Serializer<CutoutCollection> {
  override val defaultValue: CutoutCollection = CutoutCollection.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): CutoutCollection =
    try {
      CutoutCollection.parseFrom(input)
    } catch (e: InvalidProtocolBufferException) {
      throw CorruptionException("Cannot read proto.", e)
    }

  override suspend fun writeTo(t: CutoutCollection, output: OutputStream) = t.writeTo(output)
}
