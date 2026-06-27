// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.ui.common

import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.kaiwa.R
import app.kaiwa.data.BooleanSwitchConfig
import app.kaiwa.data.BottomSheetSelectorConfig
import app.kaiwa.data.BottomSheetSelectorItem
import app.kaiwa.data.Config
import app.kaiwa.data.ConfigKeys
import app.kaiwa.data.LabelConfig
import app.kaiwa.data.NumberSliderConfig
import app.kaiwa.data.SegmentedButtonConfig
import app.kaiwa.data.ValueType
import app.kaiwa.ui.theme.labelSmallNarrow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AGConfigDialog"

private data class ConfigTab(@StringRes val labelResId: Int)

private val DIALOG_TABS =
  listOf(
    ConfigTab(labelResId = R.string.config_dialog_tab_model_configs),
    ConfigTab(labelResId = R.string.config_dialog_tab_system_prompt),
  )

private const val CONFIGS_TAB_INDEX = 0
private const val SYSTEM_PROMPT_TAB_INDEX = 1

/**
 * A modal dialog that lets the user edit a list of [configs] and, optionally, the system prompt
 * across two tabs. The edited config map plus the old/new system prompt are reported through [onOk].
 */
@Composable
fun ConfigDialog(
  title: String,
  configs: List<Config>,
  initialValues: Map<String, Any>,
  onDismissed: () -> Unit,
  onOk: (values: Map<String, Any>, oldSystemPrompt: String, newSystemPrompt: String) -> Unit,
  okBtnLabel: String = "OK",
  subtitle: String = "",
  showCancel: Boolean = true,
  showSystemPromptEditorTab: Boolean = false,
  defaultSystemPrompt: String = "",
  curSystemPrompt: String = "",
) {
  val values: SnapshotStateMap<String, Any> = remember {
    mutableStateMapOf<String, Any>().apply { putAll(initialValues) }
  }
  val interactionSource = remember { MutableInteractionSource() }
  var selectedTabIndex by remember { mutableIntStateOf(CONFIGS_TAB_INDEX) }
  val savedSystemPrompt = remember { curSystemPrompt }
  var systemPrompt by remember { mutableStateOf(curSystemPrompt) }
  val onSystemPromptTab = showSystemPromptEditorTab && selectedTabIndex == SYSTEM_PROMPT_TAB_INDEX

  Dialog(onDismissRequest = onDismissed) {
    val focusManager = LocalFocusManager.current
    Card(
      modifier =
        Modifier.fillMaxWidth()
          .clickable(interactionSource = interactionSource, indication = null) {
            focusManager.clearFocus()
          }
          .imePadding(),
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        DialogHeader(title = title, subtitle = subtitle)

        if (showSystemPromptEditorTab) {
          ConfigTabRow(
            selectedTabIndex = selectedTabIndex,
            onTabSelected = { selectedTabIndex = it },
          )
        }

        when (selectedTabIndex) {
          CONFIGS_TAB_INDEX ->
            Column(
              modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false),
              verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
              ConfigEditorsPanel(configs = configs, values = values)
            }
          SYSTEM_PROMPT_TAB_INDEX ->
            OutlinedTextField(
              value = systemPrompt,
              modifier = Modifier.weight(1f, fill = false),
              textStyle = MaterialTheme.typography.bodySmall,
              onValueChange = { systemPrompt = it },
              placeholder = {
                Text(
                  text = stringResource(R.string.system_prompt_placeholder),
                  modifier = Modifier.offset(y = (4).dp),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
              },
            )
        }

        Row(
          horizontalArrangement =
            if (onSystemPromptTab) Arrangement.SpaceBetween else Arrangement.End,
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(top = 8.dp),
        ) {
          if (onSystemPromptTab) {
            OutlinedButton(
              onClick = { systemPrompt = defaultSystemPrompt },
              contentPadding = SMALL_BUTTON_CONTENT_PADDING,
            ) {
              Text(stringResource(R.string.restore_default))
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (showCancel) {
              TextButton(onClick = { onDismissed() }) { Text("Cancel") }
            }
            Button(
              onClick = {
                Log.d(TAG, "Values from dialog: $values")
                onOk(values.toMap(), savedSystemPrompt, systemPrompt)
              }
            ) {
              Text(okBtnLabel)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DialogHeader(title: String, subtitle: String) {
  Column {
    Text(
      title,
      style = MaterialTheme.typography.titleLarge,
      modifier = Modifier.padding(bottom = 8.dp),
    )
    if (subtitle.isNotEmpty()) {
      Text(
        subtitle,
        style = labelSmallNarrow,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.offset(y = (-6).dp),
      )
    }
  }
}

@Composable
private fun ConfigTabRow(selectedTabIndex: Int, onTabSelected: (Int) -> Unit) {
  PrimaryTabRow(selectedTabIndex = selectedTabIndex, containerColor = Color.Transparent) {
    DIALOG_TABS.forEachIndexed { index, tab ->
      Tab(
        selected = selectedTabIndex == index,
        onClick = { onTabSelected(index) },
        text = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            val titleColor =
              if (selectedTabIndex == index) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurfaceVariant
            Text(stringResource(tab.labelResId), color = titleColor)
          }
        },
      )
    }
  }
}

/** Composable function to display a list of config editor rows. */
@Composable
fun ConfigEditorsPanel(configs: List<Config>, values: SnapshotStateMap<String, Any>) {
  for (config in configs) {
    when (config) {
      is LabelConfig -> LabelRow(config = config, values = values)
      is NumberSliderConfig -> NumberSliderRow(config = config, values = values)
      is BooleanSwitchConfig -> BooleanSwitchRow(config = config, values = values)
      is SegmentedButtonConfig -> SegmentedButtonRow(config = config, values = values)
      is BottomSheetSelectorConfig -> BottomSheetSelectorRow(config = config, values = values)
      else -> {}
    }
  }
}

@Composable
fun LabelRow(config: LabelConfig, values: SnapshotStateMap<String, Any>) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Text(config.key.label, style = MaterialTheme.typography.titleSmall)
    val label = (values[config.key.label] as? String).orEmpty()
    Text(label, style = MaterialTheme.typography.bodyMedium)
  }
}

fun getTextFieldDisplayValue(valueType: ValueType, value: Float): String {
  return try {
    when (valueType) {
      ValueType.FLOAT -> "%.2f".format(value)
      ValueType.INT -> "${value.toInt()}"
      else -> ""
    }
  } catch (e: Exception) {
    ""
  }
}

private fun SnapshotStateMap<String, Any>.floatOrZero(key: String): Float =
  try {
    this[key] as Float
  } catch (e: Exception) {
    0f
  }

/**
 * Renders a labeled numeric row pairing a [Slider] with a small numeric text field. Out-of-range or
 * non-numeric text is tolerated while editing and clamped into the slider range once committed.
 */
@Composable
fun NumberSliderRow(config: NumberSliderConfig, values: SnapshotStateMap<String, Any>) {
  val focusManager = LocalFocusManager.current
  val key = config.key.label

  Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
    val minStr = getTextFieldDisplayValue(config.valueType, config.sliderMin)
    val maxStr = getTextFieldDisplayValue(config.valueType, config.sliderMax)
    Text("$key ($minStr-$maxStr)", style = MaterialTheme.typography.titleSmall)

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      var isFocused by remember { mutableStateOf(false) }
      val focusRequester = remember { FocusRequester() }

      // Holds whatever the user is typing, which may be temporarily invalid or out of range.
      var textFieldDisplayValue by remember {
        mutableStateOf(getTextFieldDisplayValue(config.valueType, values[key] as Float))
      }

      Slider(
        modifier = Modifier.height(24.dp).weight(1f).padding(end = 8.dp),
        value = values.floatOrZero(key),
        valueRange = config.sliderMin..config.sliderMax,
        onValueChange = {
          values[key] = it
          textFieldDisplayValue = getTextFieldDisplayValue(config.valueType, it)
        },
      )

      Spacer(modifier = Modifier.width(8.dp))

      BasicTextField(
        value = textFieldDisplayValue,
        modifier =
          Modifier.width(80.dp).focusRequester(focusRequester).onFocusChanged {
            isFocused = it.isFocused
            // On blur, snap the display back to the committed (valid) value.
            if (!isFocused) {
              textFieldDisplayValue =
                getTextFieldDisplayValue(config.valueType, values[key] as Float)
            }
          },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        singleLine = true,
        onValueChange = {
          textFieldDisplayValue = it
          // Only commit parseable input, clamped to the slider range, to avoid NaN crashes.
          it.toFloatOrNull()?.let { parsed ->
            values[key] = minOf(maxOf(parsed, config.sliderMin), config.sliderMax)
          }
        },
        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
      ) { innerTextField ->
        Box(
          modifier =
            Modifier.border(
              width = if (isFocused) 2.dp else 1.dp,
              color =
                if (isFocused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
              shape = RoundedCornerShape(4.dp),
            )
        ) {
          Box(modifier = Modifier.padding(8.dp)) { innerTextField() }
        }
      }
    }

    if (config.key == ConfigKeys.MAX_TOKENS && values.floatOrZero(key) >= 10000f) {
      Text(
        text = stringResource(R.string.max_tokens_warning_message),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 8.dp),
      )
    }
  }
}

/** Renders a labeled boolean row backed by a [Switch]. */
@Composable
fun BooleanSwitchRow(config: BooleanSwitchConfig, values: SnapshotStateMap<String, Any>) {
  val key = config.key.label
  val switchValue =
    try {
      values[key] as Boolean
    } catch (e: Exception) {
      false
    }
  Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
    Text(key, style = MaterialTheme.typography.titleSmall)
    Switch(checked = switchValue, onCheckedChange = { values[key] = it })
  }
}

/**
 * Renders a labeled multi-choice segmented button row. The selection is persisted as a
 * comma-separated string of option labels; single-select configs keep exactly one option active.
 */
@Composable
fun SegmentedButtonRow(config: SegmentedButtonConfig, values: SnapshotStateMap<String, Any>) {
  val key = config.key.label
  val initiallySelected: List<String> = remember { (values[key] as String).split(",") }
  var selectionStates: List<Boolean> by remember {
    mutableStateOf(config.options.map { initiallySelected.contains(it) })
  }

  Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
    Text(key, style = MaterialTheme.typography.titleSmall)
    MultiChoiceSegmentedButtonRow {
      config.options.forEachIndexed { index, label ->
        SegmentedButton(
          shape = SegmentedButtonDefaults.itemShape(index = index, count = config.options.size),
          onCheckedChange = {
            val updated = selectionStates.toMutableList()
            val selectedCount = updated.count { it }

            if (!config.allowMultiple) {
              if (!updated[index]) {
                selectionStates = List(config.options.size) { it == index }
              } else {
                selectionStates = updated
              }
            } else {
              if (!(selectedCount == 1 && updated[index])) {
                updated[index] = !updated[index]
              }
              selectionStates = updated
            }

            values[key] =
              config.options.filterIndexed { i, _ -> selectionStates[i] }.joinToString(",")
          },
          checked = selectionStates[index],
          label = { Text(label) },
        )
      }
    }
  }
}

/**
 * Renders a labeled row whose value is chosen from a [ModalBottomSheet] list. The chosen option is
 * written back into [values] and reported through [onSelected].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetSelectorRow(
  config: BottomSheetSelectorConfig,
  values: SnapshotStateMap<String, Any>,
  showLabel: Boolean = true,
  onSelected: (BottomSheetSelectorItem) -> Unit = {},
) {
  var selectedOption by remember {
    mutableStateOf(config.options.find { it.label == config.defaultValue })
  }
  var showBottomSheet by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()

  Column(
    modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    if (showLabel) {
      Text(config.key.label, style = MaterialTheme.typography.titleSmall)
    }
    Row(
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
      modifier =
        Modifier.height(40.dp)
          .clip(CircleShape)
          .clickable { showBottomSheet = true }
          .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
          .padding(start = 12.dp, end = 8.dp),
    ) {
      Text(
        selectedOption?.label ?: "-",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
      )
      Icon(
        Icons.Rounded.ArrowDropDown,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
      )
    }
  }

  if (showBottomSheet) {
    ModalBottomSheet(
      onDismissRequest = { showBottomSheet = false },
      sheetState = sheetState,
      containerColor = MaterialTheme.colorScheme.surface,
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        config.bottomSheetTitleResId?.let { titleResId ->
          Text(
            stringResource(titleResId),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(16.dp),
          )
        }
        LazyColumn {
          items(config.options) { option ->
            Row(
              modifier =
                Modifier.clickable {
                    selectedOption = option
                    values[config.key.label] = option.label
                    onSelected(option)
                    scope.launch {
                      delay(200)
                      sheetState.hide()
                      showBottomSheet = false
                    }
                  }
                  .padding(horizontal = 16.dp, vertical = 12.dp)
                  .fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
              Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.alpha(if (option == selectedOption) 1f else 0f),
              )
              Text(
                option.label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
              )
            }
          }
        }
      }
    }
  }
}
