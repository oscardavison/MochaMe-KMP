package com.mochame.bio.ui

data class DailyContextUiState(
    val epochDay: Long,
    val sleepHoursInput: String = "",
    val readinessScoreInput: String = "",
    val notesInput: String = "",
    val isNapped: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null
)

/**
 * null = untouched, "" = explicitly cleared, "X" = edited
 *
 * null = untouched, Boolean = edited
 */
internal data class TransientInput(
    val sleep: String? = null,
    val readiness: String? = null,
    val isNapped: Boolean? = null,
    val notes: String? = null
)

sealed interface DailyContextIntent {
    data class UpdateSleepInput(val input: String) : DailyContextIntent
    data class UpdateReadinessInput(val input: String) : DailyContextIntent
    data class UpdateNotesInput(val input: String) : DailyContextIntent
    data class ToggleNapped(val isNapped: Boolean) : DailyContextIntent
    data object Save : DailyContextIntent
    data object Delete : DailyContextIntent
    data object DismissError : DailyContextIntent
}