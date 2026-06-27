// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.data

import androidx.annotation.StringRes
import app.kaiwa.R

/**
 * Describes a category, i.e. a home-screen tab grouping a set of tasks. A task declares which
 * category it belongs to.
 *
 * The display label comes from [label] when present, otherwise from [labelStringRes].
 */
data class CategoryInfo(
  val id: String,
  @StringRes val labelStringRes: Int? = null,
  val label: String? = null,
)

/** The fixed set of categories the app ships with. */
object Category {
  val LLM = CategoryInfo(id = "llm", labelStringRes = R.string.category_llm)
  val CLASSICAL_ML = CategoryInfo(id = "classical_ml", labelStringRes = R.string.category_llm)
  val EXPERIMENTAL =
    CategoryInfo(id = "experimental", labelStringRes = R.string.category_experimental)
}
