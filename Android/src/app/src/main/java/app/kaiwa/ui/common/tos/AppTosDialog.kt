// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.ui.common.tos

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.kaiwa.R
import app.kaiwa.ui.common.MarkdownText

private const val TOS_BODY =
  "By using this app, you agree to the " +
    "[Google Terms of Service](https://policies.google.com/terms?hl=en-US).\n\n" +
    "To learn what information we collect and why, how we use it, " +
    "and how to review and update it, please review the " +
    "[Google Privacy Policy](https://policies.google.com/privacy?hl=en-US).\n\n" +
    "Your use of each model is subject to the applicable model license terms."

/**
 * Terms-of-service dialog shown on first launch. When [viewingMode] is true the dialog is
 * dismissible and shows a Close button instead of the accept action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTosDialog(onTosAccepted: () -> Unit, viewingMode: Boolean = false) {
  Dialog(
    properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    onDismissRequest = { if (viewingMode) onTosAccepted() },
  ) {
    Card(shape = RoundedCornerShape(28.dp)) {
      Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        val titleColor = MaterialTheme.colorScheme.onSurface
        BasicText(
          stringResource(R.string.tos_dialog_title_app),
          modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
          style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
          color = { titleColor },
          maxLines = 1,
          autoSize =
            TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 24.sp, stepSize = 1.sp),
        )

        Column(modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
          MarkdownText(
            TOS_BODY,
            smallFontSize = true,
            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
          )
        }

        Button(
          onClick = onTosAccepted,
          modifier = Modifier.padding(top = 28.dp, bottom = 24.dp).align(Alignment.End),
        ) {
          val labelRes =
            if (viewingMode) R.string.close
            else R.string.tos_dialog_accept_and_continue_button_label
          Text(stringResource(labelRes))
        }
      }
    }
  }
}
