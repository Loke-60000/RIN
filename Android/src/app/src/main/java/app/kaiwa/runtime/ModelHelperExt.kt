// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.runtime

import app.kaiwa.data.Model
import app.kaiwa.data.RuntimeType
import app.kaiwa.runtime.aicore.AICoreModelHelper
import app.kaiwa.ui.llmchat.LlmChatModelHelper

/** Test seam: when set, every model resolves to this helper regardless of its runtime. */
var testingModelHelper: LlmModelHelper? = null

/** The [LlmModelHelper] that should drive inference for this model. */
val Model.runtimeHelper: LlmModelHelper
  get() =
    testingModelHelper
      ?: if (runtimeType == RuntimeType.AICORE) AICoreModelHelper else LlmChatModelHelper
