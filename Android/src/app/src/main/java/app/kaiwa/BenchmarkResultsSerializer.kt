// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import app.kaiwa.proto.BenchmarkResults
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

/** DataStore [Serializer] backing the persisted [BenchmarkResults] proto. */
object BenchmarkResultsSerializer : Serializer<BenchmarkResults> {
  override val defaultValue: BenchmarkResults = BenchmarkResults.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): BenchmarkResults =
    try {
      BenchmarkResults.parseFrom(input)
    } catch (e: InvalidProtocolBufferException) {
      throw CorruptionException("Cannot read proto.", e)
    }

  override suspend fun writeTo(t: BenchmarkResults, output: OutputStream) = t.writeTo(output)
}
