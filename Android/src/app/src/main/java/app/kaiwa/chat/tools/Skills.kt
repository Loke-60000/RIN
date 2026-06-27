// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat.tools

import android.content.Context
import android.util.Log
import app.kaiwa.proto.Skill

private const val TAG = "AGSkills"

private const val SKILL_INSTRUCTIONS_TEMPLATE = "---\nname: %s\ndescription: %s\n---\n\n%s"

/** Built-in skills that ship disabled by default until the user opts in. */
val DEFAULT_DISABLED_SKILLS =
  setOf("calculate-hash", "kitchen-adventure", "text-spinner", "send-email")

/** Renders a [Skill] back into its SKILL.md body, used when loading a skill's instructions. */
fun Skill.getSkillContent(): String =
  SKILL_INSTRUCTIONS_TEMPLATE.format(name, description, instructions)

/**
 * Converts the content of a SKILL.md file to a [Skill] proto.
 *
 * The expected format is:
 * ```
 * ---
 * name: name-of-the-skill
 * description: description of the skill
 * metadata:
 *   key: value
 * ---
 *
 * other instructions text
 * ```
 *
 * @return A [Pair] of the parsed [Skill] proto (null if errors occurred) and a list of error
 *   messages.
 */
fun convertSkillMdToProto(
  mdContent: String,
  builtIn: Boolean,
  selected: Boolean,
  skillUrl: String = "",
  importDir: String = "",
): Pair<Skill?, List<String>> {
  val parts = mdContent.split("---")
  val errors = mutableListOf<String>()

  if (parts.size < 3) {
    errors.add("Invalid format: Expected at least two '---' sections.")
    return Pair(null, errors)
  }

  val header = parts[1].trim()
  var name: String? = null
  var description: String? = null
  var requireSecret = false
  var requireSecretDescription = ""
  var homepage: String? = null

  var startMetadata = false
  for (line in header.lines()) {
    val trimmedLine = line.trim()
    if (trimmedLine == "metadata:") {
      startMetadata = true
      continue
    }
    if (!startMetadata) {
      when {
        trimmedLine.startsWith("name:") -> name = trimmedLine.substringAfter("name:").trim()
        trimmedLine.startsWith("description:") ->
          description = trimmedLine.substringAfter("description:").trim()
      }
    } else {
      when {
        trimmedLine.startsWith("require-secret:") ->
          requireSecret = trimmedLine.substringAfter("require-secret:").trim().toBoolean()
        trimmedLine.startsWith("require-secret-description:") ->
          requireSecretDescription =
            trimmedLine.substringAfter("require-secret-description:").trim()
        trimmedLine.startsWith("homepage:") ->
          homepage = trimmedLine.substringAfter("homepage:").trim()
      }
    }
  }

  if (name.isNullOrEmpty()) {
    errors.add("Missing or empty 'name' in the header.")
  }
  if (description.isNullOrEmpty()) {
    errors.add("Missing or empty 'description' in the header.")
  }

  val instructions = parts.drop(2).joinToString("---").trim()

  if (errors.isNotEmpty()) {
    return Pair(null, errors)
  }

  val skill =
    Skill.newBuilder()
      .setName(name!!)
      .setDescription(description!!)
      .setInstructions(instructions)
      .setBuiltIn(builtIn)
      .setSelected(selected)
      .setSkillUrl(skillUrl)
      .setRequireSecret(requireSecret)
      .setRequireSecretDescription(requireSecretDescription)
      .setHomepage(homepage ?: "")
      .setImportDirName(importDir)
      .build()

  return Pair(skill, emptyList())
}

/**
 * Reads and parses SKILL.md files from assets/skills directories to load all built-in skills.
 *
 * @param context The application context.
 * @param builtInSelectionMap A map of skill names to their selection state and whether they were
 *   user-modified.
 * @param defaultDisabledSkills Skills that should default to deselected unless the user changed it.
 */
fun loadBuiltInSkills(
  context: Context,
  builtInSelectionMap: Map<String, Pair<Boolean /* selected */, Boolean /* userModified */>> =
    emptyMap(),
  defaultDisabledSkills: Set<String> = emptySet(),
): List<Skill> {
  val builtInSkills = mutableListOf<Skill>()
  try {
    val skillAssetDirs = context.assets.list("skills") ?: emptyArray()
    for (dirName in skillAssetDirs) {
      val skillMdPath = "skills/$dirName/SKILL.md"
      try {
        context.assets.open(skillMdPath).use { inputStream ->
          val mdContent = inputStream.bufferedReader().use { it.readText() }
          val (skillProto, errors) =
            convertSkillMdToProto(
              mdContent,
              builtIn = true,
              // Selection state will be reconciled with DataStore later.
              selected = true,
              importDir = "assets/skills/$dirName",
            )
          if (errors.isNotEmpty()) {
            Log.w(TAG, "Error parsing asset skill $dirName: ${errors.joinToString(", ")}")
          } else {
            skillProto?.let {
              // Skills are opt-in: every built-in skill is off by default so the assistant's
              // system prompt stays lean, and the user enables the ones they want. A skill the
              // user explicitly toggled keeps that choice. (defaultDisabledSkills is retained for
              // callers that still want to force a subset off even if it were ever defaulted on.)
              val defaultSelected = false
              val (persistedSelected, userModified) =
                builtInSelectionMap[it.name] ?: Pair(defaultSelected, false)
              val selectedState =
                if (userModified) persistedSelected else defaultSelected && it.name !in defaultDisabledSkills
              builtInSkills.add(
                it.toBuilder()
                  .setSelected(selectedState)
                  .setUserModifiedSelection(userModified)
                  .build()
              )
              Log.d(TAG, "Added built-in skill: ${it.name}")
            }
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "SKILL.md not found or error reading for asset skill $dirName", e)
      }
    }
  } catch (e: Exception) {
    Log.e(TAG, "Error listing assets/skills", e)
  }
  return builtInSkills
}
