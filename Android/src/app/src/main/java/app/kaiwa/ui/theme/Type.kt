// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.kaiwa.R

/** Nunito across the full weight range, used for all app typography. */
val appFontFamily =
  FontFamily(
    Font(R.font.nunito_extralight, FontWeight.ExtraLight),
    Font(R.font.nunito_light, FontWeight.Light),
    Font(R.font.nunito_regular, FontWeight.Normal),
    Font(R.font.nunito_medium, FontWeight.Medium),
    Font(R.font.nunito_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_bold, FontWeight.Bold),
    Font(R.font.nunito_extrabold, FontWeight.ExtraBold),
    Font(R.font.nunito_black, FontWeight.Black),
  )

private val baseline = Typography()

private fun withAppFont(style: androidx.compose.ui.text.TextStyle) =
  style.copy(fontFamily = appFontFamily)

val AppTypography =
  Typography(
    displayLarge = withAppFont(baseline.displayLarge),
    displayMedium = withAppFont(baseline.displayMedium),
    displaySmall = withAppFont(baseline.displaySmall),
    headlineLarge = withAppFont(baseline.headlineLarge),
    headlineMedium = withAppFont(baseline.headlineMedium),
    headlineSmall = withAppFont(baseline.headlineSmall),
    titleLarge = withAppFont(baseline.titleLarge),
    titleMedium = withAppFont(baseline.titleMedium),
    titleSmall = withAppFont(baseline.titleSmall),
    bodyLarge = withAppFont(baseline.bodyLarge),
    bodyMedium = withAppFont(baseline.bodyMedium),
    bodySmall = withAppFont(baseline.bodySmall),
    labelLarge = withAppFont(baseline.labelLarge),
    labelMedium = withAppFont(baseline.labelMedium),
    labelSmall = withAppFont(baseline.labelSmall),
  )

// Tighter / resized variants used in specific spots around the UI.

val titleMediumNarrow = baseline.titleMedium.copy(fontFamily = appFontFamily, letterSpacing = 0.0.sp)

val titleSmaller =
  baseline.titleSmall.copy(fontFamily = appFontFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold)

val labelSmallNarrow = baseline.labelSmall.copy(fontFamily = appFontFamily, letterSpacing = 0.0.sp)

val labelSmallNarrowMedium =
  baseline.labelSmall.copy(
    fontFamily = appFontFamily,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.0.sp,
  )

val bodySmallNarrow = baseline.bodySmall.copy(fontFamily = appFontFamily, letterSpacing = 0.0.sp)

val bodySmallMediumNarrow =
  baseline.bodySmall.copy(fontFamily = appFontFamily, letterSpacing = 0.0.sp, fontSize = 14.sp)

val bodySmallMediumNarrowBold =
  baseline.bodySmall.copy(
    fontFamily = appFontFamily,
    letterSpacing = 0.0.sp,
    fontSize = 14.sp,
    fontWeight = FontWeight.Bold,
  )

val homePageTitleStyle =
  baseline.displayMedium.copy(
    fontFamily = appFontFamily,
    fontSize = 48.sp,
    lineHeight = 48.sp,
    letterSpacing = -1.sp,
    fontWeight = FontWeight.Medium,
  )

val bodyLargeNarrow = baseline.bodyLarge.copy(letterSpacing = 0.2.sp)
val bodyMediumMedium = baseline.bodyMedium.copy(fontWeight = FontWeight.Medium)
val headlineLargeMedium = baseline.headlineLarge.copy(fontWeight = FontWeight.Medium)

val emptyStateTitle = baseline.headlineSmall.copy(fontSize = 37.sp, lineHeight = 50.sp)
val emptyStateContent = baseline.headlineSmall.copy(fontSize = 16.sp, lineHeight = 22.sp)
