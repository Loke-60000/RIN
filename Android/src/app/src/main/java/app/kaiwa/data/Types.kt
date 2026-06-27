// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.data

/** Hardware backends a model can be assigned to run on. */
enum class Accelerator(val label: String) {
  CPU("CPU"),
  GPU("GPU"),
  NPU("NPU"),
  TPU("TPU"),
}
