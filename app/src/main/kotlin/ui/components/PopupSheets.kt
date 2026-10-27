package com.onlasdan.studio.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.onlasdan.studio.R
import com.onlasdan.studio.ui.FloatingSheet

/**
 * Popup kit (user rule 2026-10-23): every popup in the app is a floating
 * bottom sheet in the app theme — no AlertDialog / DropdownMenu anywhere.
 * All pieces build on [FloatingSheet] so the look stays identical.
 */

@Composable
fun SheetTitle(text: String) {
    Text(
        text = text,
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleLarge
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(
    title: String,
    message: String,
    confirmLabel: String = "Ya",
    dismissLabel: String? = "Cancel",
    danger: Boolean = false,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    FloatingSheet(onDismissRequest = onDismissRequest) {
        SheetTitle(title)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (dismissLabel != null) {
                TextButton(onClick = onDismissRequest) { Text(dismissLabel) }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Button(
                onClick = onConfirm,
                shape = CircleShape,
                colors = if (danger) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                }
            ) {
                Text(confirmLabel, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MessageSheet(
    title: String,
    message: String,
    monospace: Boolean = false,
    closeLabel: String = "Close",
    onDismissRequest: () -> Unit
) {
    FloatingSheet(onDismissRequest = onDismissRequest) {
        SheetTitle(title)
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = onDismissRequest,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(closeLabel, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

data class SheetChoice(
    val label: String,
    val supporting: String? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChoiceSheet(
    title: String?,
    choices: List<SheetChoice>,
    onDismissRequest: () -> Unit
) {
    FloatingSheet(onDismissRequest = onDismissRequest) {
        if (title != null) {
            SheetTitle(title)
            Spacer(modifier = Modifier.height(4.dp))
        }
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            choices.forEach { choice ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            choice.onClick()
                            onDismissRequest()
                        }
                        .padding(vertical = 12.dp)
                ) {
                    Text(
                        text = choice.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (choice.danger) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    if (choice.supporting != null) {
                        Text(
                            text = choice.supporting,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Searchable variant of [ChoiceSheet] for LARGE lists (thousands of rows):
 * rows render lazily and a search field filters by label. Never feed a big
 * list to [ChoiceSheet] — it composes every row eagerly and can ANR.
 * Keys are derived from the label's occurrence in the source list, so they
 * stay stable while filtering and unique even if a label repeats.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SearchableChoiceSheet(
    title: String?,
    choices: List<SheetChoice>,
    searchLabel: String,
    emptyText: String,
    onDismissRequest: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val keyedChoices = remember(choices) {
        val occurrences = mutableMapOf<String, Int>()
        choices.map { choice ->
            val n = (occurrences[choice.label] ?: 0) + 1
            occurrences[choice.label] = n
            (if (n == 1) choice.label else "${choice.label}#$n") to choice
        }
    }
    val filtered = remember(query, keyedChoices) {
        if (query.isBlank()) {
            keyedChoices
        } else {
            keyedChoices.filter { it.second.label.contains(query, ignoreCase = true) }
        }
    }
    FloatingSheet(onDismissRequest = onDismissRequest) {
        if (title != null) {
            SheetTitle(title)
            Spacer(modifier = Modifier.height(4.dp))
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text(searchLabel) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (filtered.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(filtered, key = { it.first }) { (_, choice) ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                choice.onClick()
                                onDismissRequest()
                            }
                            .padding(vertical = 12.dp)
                    ) {
                        Text(
                            text = choice.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (choice.danger) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        if (choice.supporting != null) {
                            Text(
                                text = choice.supporting,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun InputSheet(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String = "Save",
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember(title, initial) { mutableStateOf(initial) }
    FloatingSheet(onDismissRequest = onDismissRequest) {
        SheetTitle(title)
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.cancel)) }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(confirmLabel, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BusySheet(text: String) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden }
    )
    FloatingSheet(onDismissRequest = { }, sheetState = sheetState) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = stringResource(R.string.processing_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
