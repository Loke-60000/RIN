// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat.tools

import android.content.Context
import android.util.Log
import app.kaiwa.data.DataStoreRepository
import app.kaiwa.proto.Skill
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGChatSkillManager"

/**
 * App-scoped owner of the skill catalog. It reconciles the built-in asset skills (loaded via
 * [loadBuiltInSkills]) with persisted selection state in [DataStoreRepository], and drops all the
 * UI-only management (import / validation / editing) which lives in the skill-management UI.
 */
@Singleton
class SkillManager
@Inject
constructor(
  private val dataStoreRepository: DataStoreRepository,
  @ApplicationContext private val context: Context,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _skills = MutableStateFlow<List<Skill>>(emptyList())
  val skills = _skills.asStateFlow()

  @Volatile private var loaded = false

  /** Reconciles built-in asset skills with persisted selection state. Safe to call repeatedly. */
  suspend fun load() {
    if (loaded) return
    withContext(Dispatchers.IO) {
      Log.d(TAG, "Loading skills index...")
      val allDataStoreSkills = dataStoreRepository.getAllSkills()
      val dataStoreCustomSkills = allDataStoreSkills.filter { !it.builtIn }
      val builtInSelectionMap =
        allDataStoreSkills.filter { it.builtIn }.associate {
          it.name to Pair(it.selected, it.userModifiedSelection)
        }

      val builtInSkills =
        loadBuiltInSkills(context, builtInSelectionMap, DEFAULT_DISABLED_SKILLS)

      val finalSkills = builtInSkills.toMutableList()
      for (customSkill in dataStoreCustomSkills) {
        if (finalSkills.none { it.name == customSkill.name }) finalSkills.add(customSkill)
      }

      dataStoreRepository.setSkills(finalSkills)
      _skills.update { finalSkills }
      loaded = true
    }
  }

  fun getSelectedSkills(): List<Skill> = _skills.value.filter { it.selected }

  /** Selects or deselects [skill], updating both the live catalog and persisted state. */
  fun setSkillSelected(skill: Skill, selected: Boolean) {
    _skills.update { current ->
      current.map { if (it.name == skill.name) it.toBuilder().setSelected(selected).build() else it }
    }
    scope.launch { dataStoreRepository.setSkillSelected(skill, selected) }
  }

  /** The instructions body for [skillName] if it is selected, else null. */
  fun loadSkill(skillName: String): String? {
    val skill = getSelectedSkills().find { it.name == skillName.trim() } ?: return null
    return skill.getSkillContent()
  }

  /** A short selected-skills summary for the system prompt (name + description per line). */
  fun getSelectedSkillsSummary(): String =
    getSelectedSkills().joinToString("\n\n") { skill ->
      "- Skill name: \"${skill.name}\"\n- Description: ${skill.description}"
    }
}
