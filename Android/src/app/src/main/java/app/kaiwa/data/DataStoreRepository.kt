// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.data

import androidx.datastore.core.DataStore
import app.kaiwa.proto.AccessTokenData
import app.kaiwa.proto.BenchmarkResult
import app.kaiwa.proto.BenchmarkResults
import app.kaiwa.proto.Cutout
import app.kaiwa.proto.CutoutCollection
import app.kaiwa.proto.ImportedModel
import app.kaiwa.proto.Settings
import app.kaiwa.proto.Skill
import app.kaiwa.proto.Skills
import app.kaiwa.proto.Theme
import app.kaiwa.proto.UserData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

// TODO(b/423700720): Change to async (suspend) functions
interface DataStoreRepository {
  fun saveTextInputHistory(history: List<String>)

  fun readTextInputHistory(): List<String>

  fun saveTheme(theme: Theme)

  fun readTheme(): Theme

  fun saveSecret(key: String, value: String)

  fun readSecret(key: String): String?

  fun deleteSecret(key: String)

  fun saveAccessTokenData(accessToken: String, refreshToken: String, expiresAt: Long)

  fun clearAccessTokenData()

  fun readAccessTokenData(): AccessTokenData?

  fun saveImportedModels(importedModels: List<ImportedModel>)

  fun readImportedModels(): List<ImportedModel>

  fun isTosAccepted(): Boolean

  fun acceptTos()

  fun isGemmaTermsOfUseAccepted(): Boolean

  fun acceptGemmaTermsOfUse()

  fun addCutout(cutout: Cutout)

  fun getAllCutouts(): List<Cutout>

  fun setCutout(newCutout: Cutout)

  fun setCutouts(cutouts: List<Cutout>)

  fun setHasSeenBenchmarkComparisonHelp(seen: Boolean)

  fun getHasSeenBenchmarkComparisonHelp(): Boolean

  fun addBenchmarkResult(result: BenchmarkResult)

  fun getAllBenchmarkResults(): List<BenchmarkResult>

  fun deleteBenchmarkResult(index: Int)

  fun addSkill(skill: Skill)

  fun setSkills(skills: List<Skill>)

  fun setSkillSelected(skill: Skill, selected: Boolean)

  fun setAllSkillsSelected(selected: Boolean)

  fun getAllSkills(): List<Skill>

  fun deleteSkill(name: String)

  suspend fun deleteSkills(names: Set<String>)

  /** Records that a promo with the specified ID has been viewed. */
  fun addViewedPromoId(promoId: String)

  /** Removes a viewed promo record. */
  fun removeViewedPromoId(promoId: String)

  /** Returns whether a promo with the specified ID has been viewed. */
  fun hasViewedPromo(promoId: String): Boolean
}

/**
 * Proto DataStore-backed implementation of [DataStoreRepository].
 *
 * The interface is intentionally synchronous, so every accessor wraps the underlying suspending
 * DataStore call in [runBlocking]. Each datastore owns one slice of persisted state.
 */
class DefaultDataStoreRepository(
  private val dataStore: DataStore<Settings>,
  private val userDataDataStore: DataStore<UserData>,
  private val cutoutDataStore: DataStore<CutoutCollection>,
  private val benchmarkResultsDataStore: DataStore<BenchmarkResults>,
  private val skillsDataStore: DataStore<Skills>,
) : DataStoreRepository {

  // --- Text input history (Settings) ---

  override fun saveTextInputHistory(history: List<String>) = blockingUpdateSettings {
    it.clearTextInputHistory().addAllTextInputHistory(history)
  }

  override fun readTextInputHistory(): List<String> = readSettings().textInputHistoryList

  // --- Theme (Settings) ---

  override fun saveTheme(theme: Theme) = blockingUpdateSettings { it.setTheme(theme) }

  override fun readTheme(): Theme {
    val theme = readSettings().theme
    // "Auto" is the implicit default.
    return if (theme == Theme.THEME_UNSPECIFIED) Theme.THEME_AUTO else theme
  }

  // --- Secrets (UserData) ---

  override fun saveSecret(key: String, value: String) = blockingUpdateUserData {
    it.putSecrets(key, value)
  }

  override fun readSecret(key: String): String? =
    runBlocking { userDataDataStore.data.first().secretsMap[key] }

  override fun deleteSecret(key: String) = blockingUpdateUserData { it.removeSecrets(key) }

  // --- Access token (migrated from Settings to UserData) ---

  override fun saveAccessTokenData(accessToken: String, refreshToken: String, expiresAt: Long) {
    runBlocking {
      // Wipe the legacy copy that used to live in Settings.
      dataStore.updateData {
        it.toBuilder().setAccessTokenData(AccessTokenData.getDefaultInstance()).build()
      }
      userDataDataStore.updateData {
        it.toBuilder()
          .setAccessTokenData(
            AccessTokenData.newBuilder()
              .setAccessToken(accessToken)
              .setRefreshToken(refreshToken)
              .setExpiresAtMs(expiresAt)
              .build()
          )
          .build()
      }
    }
  }

  override fun clearAccessTokenData() {
    runBlocking {
      dataStore.updateData { it.toBuilder().clearAccessTokenData().build() }
      userDataDataStore.updateData { it.toBuilder().clearAccessTokenData().build() }
    }
  }

  override fun readAccessTokenData(): AccessTokenData? =
    runBlocking { userDataDataStore.data.first().accessTokenData }

  // --- Imported models (Settings) ---

  override fun saveImportedModels(importedModels: List<ImportedModel>) = blockingUpdateSettings {
    it.clearImportedModel().addAllImportedModel(importedModels)
  }

  override fun readImportedModels(): List<ImportedModel> = readSettings().importedModelList

  // --- Terms of service / Gemma terms (Settings) ---

  override fun isTosAccepted(): Boolean = readSettings().isTosAccepted

  override fun acceptTos() = blockingUpdateSettings { it.setIsTosAccepted(true) }

  override fun isGemmaTermsOfUseAccepted(): Boolean = readSettings().isGemmaTermsAccepted

  override fun acceptGemmaTermsOfUse() = blockingUpdateSettings { it.setIsGemmaTermsAccepted(true) }

  // --- Cutouts (CutoutCollection) ---

  override fun addCutout(cutout: Cutout) {
    runBlocking {
      cutoutDataStore.updateData { it.toBuilder().addCutout(cutout).build() }
    }
  }

  override fun getAllCutouts(): List<Cutout> = runBlocking { cutoutDataStore.data.first().cutoutList }

  override fun setCutout(newCutout: Cutout) {
    runBlocking {
      cutoutDataStore.updateData { cutouts ->
        val index = (0 until cutouts.cutoutCount).firstOrNull { cutouts.getCutout(it).id == newCutout.id } ?: -1
        if (index >= 0) {
          cutouts.toBuilder().setCutout(index, newCutout).build()
        } else {
          cutouts
        }
      }
    }
  }

  override fun setCutouts(cutouts: List<Cutout>) {
    runBlocking {
      cutoutDataStore.updateData { CutoutCollection.newBuilder().addAllCutout(cutouts).build() }
    }
  }

  // --- Benchmark help flag + results ---

  override fun setHasSeenBenchmarkComparisonHelp(seen: Boolean) = blockingUpdateSettings {
    it.setHasSeenBenchmarkComparisonHelp(seen)
  }

  override fun getHasSeenBenchmarkComparisonHelp(): Boolean =
    readSettings().hasSeenBenchmarkComparisonHelp

  override fun addBenchmarkResult(result: BenchmarkResult) {
    runBlocking {
      benchmarkResultsDataStore.updateData { it.toBuilder().addResult(0, result).build() }
    }
  }

  override fun getAllBenchmarkResults(): List<BenchmarkResult> =
    runBlocking { benchmarkResultsDataStore.data.first().resultList }

  override fun deleteBenchmarkResult(index: Int) {
    runBlocking {
      benchmarkResultsDataStore.updateData { it.toBuilder().removeResult(index).build() }
    }
  }

  // --- Skills (Skills) ---

  override fun addSkill(skill: Skill) {
    runBlocking {
      skillsDataStore.updateData { skills ->
        // Newly added skill goes to the front of the list.
        val merged = buildList {
          add(skill)
          addAll(skills.skillList)
        }
        skills.toBuilder().clearSkill().addAllSkill(merged).build()
      }
    }
  }

  override fun setSkills(skills: List<Skill>) {
    runBlocking {
      skillsDataStore.updateData { it.toBuilder().clearSkill().addAllSkill(skills).build() }
    }
  }

  override fun setSkillSelected(skill: Skill, selected: Boolean) {
    runBlocking {
      skillsDataStore.updateData { skills ->
        val updated =
          skills.skillList.map {
            if (it.name == skill.name) {
              it.toBuilder().setSelected(selected).setUserModifiedSelection(true).build()
            } else {
              it
            }
          }
        Skills.newBuilder().addAllSkill(updated).build()
      }
    }
  }

  override fun setAllSkillsSelected(selected: Boolean) {
    runBlocking {
      skillsDataStore.updateData { skills ->
        val updated =
          skills.skillList.map {
            it.toBuilder().setSelected(selected).setUserModifiedSelection(true).build()
          }
        Skills.newBuilder().addAllSkill(updated).build()
      }
    }
  }

  override fun getAllSkills(): List<Skill> = runBlocking { skillsDataStore.data.first().skillList }

  override fun deleteSkill(name: String) {
    runBlocking {
      skillsDataStore.updateData { skills ->
        Skills.newBuilder().addAllSkill(skills.skillList.filter { it.name != name }).build()
      }
    }
  }

  override suspend fun deleteSkills(names: Set<String>) {
    skillsDataStore.updateData { skills ->
      val kept = skills.skillList.filter { it.name !in names }
      skills.toBuilder().clearSkill().addAllSkill(kept).build()
    }
  }

  // --- Viewed promos (Settings) ---

  override fun addViewedPromoId(promoId: String) {
    runBlocking {
      dataStore.updateData { settings ->
        if (settings.viewedPromoIdList.contains(promoId)) {
          settings
        } else {
          settings.toBuilder().addViewedPromoId(promoId).build()
        }
      }
    }
  }

  override fun removeViewedPromoId(promoId: String) {
    runBlocking {
      dataStore.updateData { settings ->
        val kept = settings.viewedPromoIdList.filter { it != promoId }
        settings.toBuilder().clearViewedPromoId().addAllViewedPromoId(kept).build()
      }
    }
  }

  override fun hasViewedPromo(promoId: String): Boolean =
    readSettings().viewedPromoIdList.contains(promoId)

  // --- Shared helpers ---

  private fun readSettings(): Settings = runBlocking { dataStore.data.first() }

  private fun blockingUpdateSettings(transform: (Settings.Builder) -> Settings.Builder) {
    runBlocking { dataStore.updateData { transform(it.toBuilder()).build() } }
  }

  private fun blockingUpdateUserData(transform: (UserData.Builder) -> UserData.Builder) {
    runBlocking { userDataDataStore.updateData { transform(it.toBuilder()).build() } }
  }
}
