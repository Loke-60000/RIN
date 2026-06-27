// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.common

import androidx.core.net.toUri
import net.openid.appauth.AuthorizationServiceConfiguration

/**
 * Hugging Face OAuth settings used by the AppAuth flow.
 *
 * [clientId] and [redirectUri] are placeholders to be filled in per build; the redirect scheme must
 * match `appAuthRedirectScheme` in build.gradle.kts.
 */
object ProjectConfig {
  const val clientId = "REPLACE_WITH_YOUR_CLIENT_ID_IN_HUGGINGFACE_APP"
  const val redirectUri = "REPLACE_WITH_YOUR_REDIRECT_URI_IN_HUGGINGFACE_APP"

  private const val authEndpoint = "https://huggingface.co/oauth/authorize"
  private const val tokenEndpoint = "https://huggingface.co/oauth/token"

  val authServiceConfig =
    AuthorizationServiceConfiguration(authEndpoint.toUri(), tokenEndpoint.toUri())
}
