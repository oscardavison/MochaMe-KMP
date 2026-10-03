package com.mochame.bio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mochame.core.design.MochaHeader
import com.mochame.utils.interfaces.MochaTimeUtils
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun DailyContextRoute(
    epochDay: Long,
    timeUtils: MochaTimeUtils,
    modifier: Modifier = Modifier
) {
    val viewModel: DailyContextViewModel = koinViewModel(
        key = "DailyContextViewModel_$epochDay"
    ) { parametersOf(epochDay) }

    val state by viewModel.state.collectAsStateWithLifecycle()

    DailyContextScreen(
        state = state,
        onIntent = viewModel::dispatch,
        timeUtils = timeUtils,
        modifier = modifier
    )
}

@Composable
fun DailyContextScreen(
    state: DailyContextUiState,
    onIntent: (DailyContextIntent) -> Unit,
    timeUtils: MochaTimeUtils,
    modifier: Modifier = Modifier
) {
    if (state.isLoading) {
        Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Loading context...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    val readableDate = remember(state.epochDay) {
        timeUtils.formatStandardDay(state.epochDay)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
    ) {
        val isWideLayout = maxWidth >= 600.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            MochaHeader(subtitle = readableDate)

            Spacer(modifier = Modifier.height(28.dp))

            if (isWideLayout) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Metrics",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            SleepInputField(
                                value = state.sleepHoursInput,
                                onIntent = onIntent,
                                onNext = { focusManager.moveFocus(FocusDirection.Down) }
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            ReadinessInputField(
                                value = state.readinessScoreInput,
                                onIntent = onIntent,
                                onDone = { focusManager.moveFocus(FocusDirection.Down) }
                            )
                        }
                    }

                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Recovery",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            NapSwitchField(state.isNapped, onIntent)
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Metrics",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        SleepInputField(
                            value = state.sleepHoursInput,
                            onIntent = onIntent,
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        ReadinessInputField(
                            value = state.readinessScoreInput,
                            onIntent = onIntent,
                            onDone = { focusManager.moveFocus(FocusDirection.Down) }
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        NapSwitchField(state.isNapped, onIntent)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                NotesInputField(
                    value = state.notesInput,
                    onIntent = onIntent,
                    onDone = { focusManager.clearFocus() }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        onIntent(DailyContextIntent.Save)
                    },
                    enabled = !state.isSaving
                ) {
                    Text(if (state.isSaving) "Saving..." else "Save Changes")
                }

                OutlinedButton(
                    onClick = {
                        focusManager.clearFocus()
                        onIntent(DailyContextIntent.Delete)
                    },
                    enabled = !state.isSaving,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete Record")
                }
            }

            state.errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun SleepInputField(
    value: String,
    onIntent: (DailyContextIntent) -> Unit,
    onNext: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onIntent(DailyContextIntent.UpdateSleepInput(it)) },
        label = { Text("Sleep Duration") },
        placeholder = { Text("e.g. 7.5 hrs") },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(onNext = { onNext() }),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ReadinessInputField(
    value: String,
    onIntent: (DailyContextIntent) -> Unit,
    onDone: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onIntent(DailyContextIntent.UpdateReadinessInput(it)) },
        label = { Text("Readiness Score") },
        placeholder = { Text("1 - 5") },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun NotesInputField(
    value: String,
    onIntent: (DailyContextIntent) -> Unit,
    onDone: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "Notes",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { onIntent(DailyContextIntent.UpdateNotesInput(it)) },
            placeholder = { Text("Testing...") },
            minLines = 1,
            maxLines = 10,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Default
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun NapSwitchField(
    isNapped: Boolean,
    onIntent: (DailyContextIntent) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Napped Today", style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (isNapped) "Recharged" else "Recharge?",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = isNapped,
            onCheckedChange = { onIntent(DailyContextIntent.ToggleNapped(it)) }
        )
    }
}