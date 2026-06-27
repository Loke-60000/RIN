// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.data

import com.google.gson.annotations.SerializedName

/** One entry in the featured-skill list, as parsed from the bundled allowlist JSON. */
data class AllowedSkill(
  val name: String,
  val description: String,
  @SerializedName("skillUrl") val skillUrl: String,
  @SerializedName("attributionLabel") val attributionLabel: String? = null,
  @SerializedName("attributionUrl") val attributionUrl: String? = null,
)

/** Top-level container holding the set of featured skills. */
data class SkillAllowlist(@SerializedName("featuredSkills") val featuredSkills: List<AllowedSkill>)
