// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.ui.common.tos

import androidx.lifecycle.ViewModel
import app.kaiwa.data.DataStoreRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Exposes the acceptance state for the app's and Gemma's terms of use. */
@HiltViewModel
open class TosViewModel @Inject constructor(private val dataStoreRepository: DataStoreRepository) :
  ViewModel() {

  open fun getIsTosAccepted(): Boolean = dataStoreRepository.isTosAccepted()

  open fun acceptTos() = dataStoreRepository.acceptTos()

  open fun getIsGemmaTermsOfUseAccepted(): Boolean =
    dataStoreRepository.isGemmaTermsOfUseAccepted()

  open fun acceptGemmaTermsOfUse() = dataStoreRepository.acceptGemmaTermsOfUse()
}
