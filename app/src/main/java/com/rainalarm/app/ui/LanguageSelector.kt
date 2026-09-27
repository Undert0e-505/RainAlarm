package com.rainalarm.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.rainalarm.app.R
import com.rainalarm.app.data.AppLanguagePolicy

/** Shared first-use and Settings language chooser backed by AndroidX per-app locales. */
@Composable
fun LanguageSelectorDialog(
    selectedTag: String?,
    required: Boolean,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingTag by remember(selectedTag, required) { mutableStateOf(selectedTag) }
    val selectorDescription = stringResource(R.string.language_selector_description)
    AlertDialog(
        onDismissRequest = { if (!required) onDismiss() },
        title = { Text(stringResource(R.string.language_title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        if (required) R.string.language_first_use_help
                        else R.string.language_settings_help,
                    ),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Column(
                    Modifier.fillMaxWidth().selectableGroup().semantics {
                        contentDescription = selectorDescription
                    },
                ) {
                    AppLanguagePolicy.options.forEach { option ->
                        val selected = option.tag == pendingTag
                        val label = option.tag?.let { option.nativeName }
                            ?: stringResource(R.string.language_device_default)
                        Row(
                            Modifier.fillMaxWidth().selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { pendingTag = option.tag },
                            ).padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pendingTag) }) {
                Text(stringResource(R.string.language_apply))
            }
        },
        dismissButton = if (required) null else ({
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }),
        properties = DialogProperties(
            dismissOnBackPress = !required,
            dismissOnClickOutside = !required,
        ),
    )
}
