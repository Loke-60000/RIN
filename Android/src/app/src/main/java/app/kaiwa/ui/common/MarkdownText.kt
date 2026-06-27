// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import app.kaiwa.ui.theme.customColors
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.ui.CodeBlockStyle
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.halilibo.richtext.ui.string.RichTextStringStyle

/** Renders a Markdown string with a body text style and themed link/code-block styling. */
@Composable
fun MarkdownText(
  text: String,
  modifier: Modifier = Modifier,
  smallFontSize: Boolean = false,
  textColor: Color = MaterialTheme.colorScheme.onSurface,
  linkColor: Color = MaterialTheme.customColors.linkColor,
) {
  val typography = MaterialTheme.typography
  val bodyFontSize =
    if (smallFontSize) typography.bodyMedium.fontSize else typography.bodyLarge.fontSize
  val lineHeightFactor = if (smallFontSize) 1.4f else 1.5f

  val baseTextStyle =
    TextStyle(
      fontSize = bodyFontSize,
      lineHeight = bodyFontSize * lineHeightFactor,
      color = textColor,
      letterSpacing = 0.2.sp,
    )

  val codeFontSize = typography.bodySmall.fontSize
  val richTextStyle =
    RichTextStyle(
      codeBlockStyle =
        CodeBlockStyle(
          textStyle =
            TextStyle(
              fontSize = codeFontSize,
              fontFamily = FontFamily.Monospace,
              lineHeight = codeFontSize * 1.4f,
            )
        ),
      stringStyle =
        RichTextStringStyle(linkStyle = TextLinkStyles(style = SpanStyle(color = linkColor))),
    )

  CompositionLocalProvider {
    ProvideTextStyle(value = baseTextStyle) {
      RichText(modifier = modifier, style = richTextStyle) { Markdown(content = text) }
    }
  }
}
