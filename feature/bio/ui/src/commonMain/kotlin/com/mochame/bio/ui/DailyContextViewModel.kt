package com.mochame.bio.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochame.bio.domain.DailyContextRepository
import com.mochame.bio.domain.SaveDailyContextUseCase
import com.mochame.utils.runCatchingCancellable
import com.mochame.utils.ui.InputSanitizer
import com.mochame.utils.ui.ParsedInput
import com.mochame.utils.ui.PrimitiveParsers
import com.mochame.utils.ui.Update
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel


@KoinViewModel
class DailyContextViewModel(
    @InjectedParam private val epochDay: Long,
    private val repository: DailyContextRepository,
    private val saveUseCase: SaveDailyContextUseCase
) : ViewModel() {

    private val userInputs = MutableStateFlow(TransientInput())
    private val isSaving = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)

    val state: StateFlow<DailyContextUiState> = combine(
        repository.observeContext(epochDay), // currently allows error propagation to cancel collectAtStateWithLifecycle() and up - can try a .catch{e -> _ } ?
        userInputs,
        isSaving,
        errorMessage
    ) { entity, inputs, saving, error ->
        DailyContextUiState(
            epochDay = epochDay,
            sleepHoursInput = inputs.sleep ?: entity?.sleepHours?.toString().orEmpty(),
            readinessScoreInput = inputs.readiness ?: entity?.readinessScore?.toString().orEmpty(),
            notesInput = inputs.notes ?: entity?.notes.orEmpty(),
            isNapped = inputs.isNapped ?: entity?.isNapped ?: false,
            isLoading = false,
            isSaving = saving,
            errorMessage = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DailyContextUiState(epochDay = epochDay, isLoading = true)
    )

    fun dispatch(intent: DailyContextIntent) {
        when (intent) {
            is DailyContextIntent.UpdateSleepInput ->
                userInputs.update { it.copy(sleep = intent.input) }

            is DailyContextIntent.UpdateReadinessInput ->
                userInputs.update { it.copy(readiness = intent.input) }

            is DailyContextIntent.UpdateNotesInput ->
                userInputs.update { it.copy(notes = intent.input) }

            is DailyContextIntent.ToggleNapped ->
                userInputs.update { it.copy(isNapped = intent.isNapped) }

            is DailyContextIntent.Save -> performSave()
            is DailyContextIntent.Delete -> performDelete()
            is DailyContextIntent.DismissError -> errorMessage.value = null
        }
    }

    private fun performSave() {
        val snapshot = userInputs.value
        if (!snapshot.hasChanges) return

        viewModelScope.launch(CoroutineName("SaveDailyContext")) {
            isSaving.value = true
            try {
                val parsedSleep = parseSleepOrNull(snapshot.sleep) ?: return@launch
                val parsedReadiness = parseReadinessOrNull(snapshot.readiness) ?: return@launch
                val cleanNotes = InputSanitizer.sanitizeMultiline(snapshot.notes)

                saveUseCase(
                    epochDay = epochDay,
                    sleepHours = Update.fromParsed(snapshot.sleep, parsedSleep.value),
                    readinessScore = Update.fromParsed(snapshot.readiness, parsedReadiness.value),
                    notes = Update.fromParsed(snapshot.notes, cleanNotes),
                    isNapped = Update.fromNullable(snapshot.isNapped)
                ).fold(
                    onSuccess = {
                        userInputs.update { current ->
                            current.copy(
                                sleep = current.sleep.takeUnless { it == snapshot.sleep },
                                readiness = current.readiness.takeUnless { it == snapshot.readiness },
                                notes = current.notes.takeUnless { it == snapshot.notes },
                                isNapped = current.isNapped.takeUnless { it == snapshot.isNapped }
                            )
                        }
                    },
                    onFailure = { error ->
                        errorMessage.value = error.message ?: "Persistence failure."
                    }
                )
            } finally {
                isSaving.value = false
            }
        }
    }

    private fun parseSleepOrNull(raw: String?): ParsedInput<Double>? {
        if (raw.isNullOrBlank()) return ParsedInput(null)
        return PrimitiveParsers.parseBoundedDouble(
            raw = raw,
            range = 0.0..24.0,
            fieldName = "Sleep Duration"
        ).fold(
            onSuccess = { ParsedInput(it) },
            onFailure = { error ->
                errorMessage.value = error.message ?: "Invalid sleep duration."
                null
            }
        )
    }

    private fun parseReadinessOrNull(raw: String?): ParsedInput<Int>? {
        if (raw.isNullOrBlank()) return ParsedInput(null)
        return PrimitiveParsers.parseBoundedInt(
            raw = raw,
            range = 1..5,
            fieldName = "Readiness Score"
        ).fold(
            onSuccess = { ParsedInput(it) },
            onFailure = { error ->
                errorMessage.value = error.message ?: "Invalid readiness score."
                null
            }
        )
    }

    private fun performDelete() {
        viewModelScope.launch {
            isSaving.value = true
            errorMessage.value = null
            runCatchingCancellable {
                repository.softDeleteContext(epochDay)
            }.fold(
                onSuccess = { userInputs.value = TransientInput() },
                onFailure = { errorMessage.value = it.message ?: "Deletion failure." }
            )
            isSaving.value = false
        }
    }


    private val TransientInput.hasChanges: Boolean
        get() = sleep != null || readiness != null || notes != null || isNapped != null

}