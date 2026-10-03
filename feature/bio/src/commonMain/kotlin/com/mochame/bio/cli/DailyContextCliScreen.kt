package com.mochame.bio.cli

import com.mochame.bio.domain.DailyContextRepository
import com.mochame.bio.domain.SaveDailyContextUseCase
import com.mochame.utils.interfaces.MochaTimeUtils
import com.mochame.utils.ui.InputSanitizer
import com.mochame.utils.ui.InteractiveScreen
import com.mochame.utils.ui.PrimitiveParsers
import com.mochame.utils.ui.ScreenResult
import com.mochame.utils.ui.Update
import org.koin.core.annotation.Factory

@Factory(binds = [DailyContextCliScreen::class, InteractiveScreen::class])
class DailyContextCliScreen(
    private val repository: DailyContextRepository,
    private val saveUseCase: SaveDailyContextUseCase,
    private val timeProvider: MochaTimeUtils
) : InteractiveScreen {

    private var activeEpochDay: Long = timeProvider.getStandardEpochDay()

    override val title: String
        get() = "Daily Context (${timeProvider.formatStandardDay(activeEpochDay)})"

    override suspend fun renderAndHandleInput(): ScreenResult {
        val currentEntity = repository.getContext(activeEpochDay)

        println("Current Stored Values:")
        println("  1. Sleep Hours    : " + (currentEntity?.sleepHours?.let { "$it hrs" }
            ?: "[Not Set]"))
        println("  2. Readiness (1-5): " + (currentEntity?.readinessScore ?: "[Not Set]"))
        println("  3. Napped Today   : " + (currentEntity?.isNapped?.let { if (it) "Yes" else "No" }
            ?: "[Not Set]"))
        println(
            "  4. Notes          : " + (
                    if (currentEntity?.notes == null) {
                        "[Not Set]"
                    } else {
                        "\n" + currentEntity.notes
                    })
        )
        println("----------------------------------------")
        println("Options:")
        println("  [0] Log All Fields (Batch Wizard)")
        println("  [1] Edit Sleep Hours")
        println("  [2] Edit Readiness Score")
        println("  [3] Toggle Nap Status")
        println("  [4] Edit Notes")
        println("  [5] Change Active Day")
        println("  [6] Delete Record for this Day")
        println("  [b] Back to Main Menu")
        print("\nSelect Option > ")

        return when (readlnOrNull()?.trim()?.lowercase()) {
            "0" -> {
                handleBatchEntry(
                    currentEntity?.sleepHours,
                    currentEntity?.readinessScore,
                    currentEntity?.isNapped,
                    currentEntity?.notes
                )
                ScreenResult.Stay
            }

            "1" -> {
                handleSingleSleep(currentEntity?.sleepHours)
                ScreenResult.Stay
            }

            "2" -> {
                handleSingleReadiness(currentEntity?.readinessScore)
                ScreenResult.Stay
            }

            "3" -> {
                handleNapToggle(currentEntity?.isNapped)
                ScreenResult.Stay
            }

            "4" -> {
                handleSingleNotes(currentEntity?.notes)
                ScreenResult.Stay
            }

            "5" -> {
                handleChangeDay()
                ScreenResult.Stay
            }

            "6" -> {
                handleDelete()
                ScreenResult.Stay
            }

            "b", "back" -> ScreenResult.GoBack
            else -> {
                println("[ERROR] Invalid choice.")
                ScreenResult.Stay
            }
        }
    }


    // --- Batch Wizard ---

    private suspend fun handleBatchEntry(
        currentSleep: Double?,
        currentReadiness: Int?,
        currentNapped: Boolean?,
        currentNotes: String?
    ) {
        println("\n--- Batch Entry Wizard ---")
        println("(Enter value, 'clear' to unset, press Enter to keep current, or 'c' to cancel)")

        val sleepUpdate = promptOrAbort { promptSleep(currentSleep) } ?: return
        val readinessUpdate = promptOrAbort { promptReadiness(currentReadiness) } ?: return
        val nappedUpdate = promptOrAbort { promptNap(currentNapped) } ?: return
        val notesUpdate = promptOrAbort { promptNotes(currentNotes) } ?: return

        saveUseCase(
            epochDay = activeEpochDay,
            sleepHours = sleepUpdate,
            readinessScore = readinessUpdate,
            isNapped = nappedUpdate,
            notes = notesUpdate
        ).fold(
            onSuccess = { println("[SUCCESS] All metrics saved for day $activeEpochDay.") },
            onFailure = { error -> println("[ERROR] Failed to save client: ${error.message}") }
        )
    }

    inline fun <T> promptOrAbort(prompt: () -> T?): T? =
        prompt() ?: run {
            println("[INFO] Batch entry aborted. No changes made.")
            null
        }

    // --- Single-Field Handlers ---

    private suspend fun handleSingleSleep(current: Double?) {
        val update = promptSleep(current) ?: return
        if (update is Update.Unchanged) {
            println("[INFO] Sleep hours unchanged.")
            return
        }
        saveUseCase(activeEpochDay, sleepHours = update).fold(
            onSuccess = { println("[SUCCESS] Sleep hours updated.") },
            onFailure = { println("[ERROR] ${it.message}") }
        )
    }

    private suspend fun handleSingleReadiness(current: Int?) {
        val update = promptReadiness(current) ?: return
        if (update is Update.Unchanged) {
            println("[INFO] Readiness score unchanged.")
            return
        }
        saveUseCase(activeEpochDay, readinessScore = update).fold(
            onSuccess = { println("[SUCCESS] Readiness score updated.") },
            onFailure = { println("[ERROR] ${it.message}") }
        )
    }

    private suspend fun handleSingleNotes(current: String?) {
        val update = promptNotes(current) ?: return
        if (update is Update.Unchanged) {
            println("[INFO] Notes unchanged.")
            return
        }
        saveUseCase(activeEpochDay, notes = update).fold(
            onSuccess = { println("[SUCCESS] Notes updated.") },
            onFailure = { println("[ERROR] ${it.message}") }
        )
    }

    private suspend fun handleNapToggle(current: Boolean?) {
        val update = promptNap(current) ?: return
        if (update is Update.Unchanged) {
            println("[INFO] Napped state unchanged.")
            return
        }
        saveUseCase(activeEpochDay, isNapped = update).fold(
            onSuccess = { println("[SUCCESS] Nap status updated.") },
            onFailure = { println("[ERROR] ${it.message}") }
        )
    }

    // --- Reusable Field Prompts (Returns null on Cancel) ---

    private fun promptSleep(current: Double?): Update<Double>? {
        val displayCurrent = current?.let { "$it hrs" } ?: "Not Set"
        while (true) {
            print("Sleep Hours [Current: $displayCurrent] > ")
            val input = readlnOrNull()?.trim() ?: return Update.Unchanged

            when {
                input.equals("c", ignoreCase = true) || input.equals(
                    "cancel",
                    ignoreCase = true
                ) -> {
                    println("[INFO] Operation cancelled.")
                    return null
                }

                input.isEmpty() -> return Update.Unchanged
                input.equals("clear", ignoreCase = true) -> return Update.Clear
                else -> {
                    val result =
                        PrimitiveParsers.parseBoundedDouble(input, 0.0..72.0, "Sleep Hours")
                    if (result.isSuccess) {
                        return Update.Set(result.getOrThrow()!!)
                    } else {
                        println("[ERROR] ${result.exceptionOrNull()?.message}")
                    }
                }
            }
        }
    }

    private fun promptReadiness(current: Int?): Update<Int>? {
        val displayCurrent = current?.toString() ?: "Not Set"
        while (true) {
            print("Readiness Score (1-5) [Current: $displayCurrent] > ")
            val input = readlnOrNull()?.trim() ?: return Update.Unchanged

            when {
                input.equals("c", ignoreCase = true) || input.equals(
                    "cancel",
                    ignoreCase = true
                ) -> {
                    println("[INFO] Operation cancelled.")
                    return null
                }

                input.isEmpty() -> return Update.Unchanged
                input.equals("clear", ignoreCase = true) -> return Update.Clear
                else -> {
                    val result = PrimitiveParsers.parseBoundedInt(input, 1..5, "Readiness Score")
                    if (result.isSuccess) {
                        return Update.Set(result.getOrThrow()!!)
                    } else {
                        println("[ERROR] ${result.exceptionOrNull()?.message}")
                    }
                }
            }
        }
    }

    private fun promptNotes(current: String?): Update<String>? {
        val displayCurrent = current?.takeIf { it.isNotBlank() } ?: "Not Set"

        println("\n----------------------------------------")
        println("Edit Notes [Current: $displayCurrent]")
        println("Instructions:")
        println(" • Type your notes freely (press Enter for new lines).")
        println(" • Enter '.' on an empty line and press Enter to save.")
        println(" • Press Enter immediately on line 1 to keep current.")
        println(" • Type 'clear' and press Enter on line 1 to erase.")
        println(" • Type 'c' or 'cancel' to abort.")
        println("----------------------------------------")
        print("> ")

        val firstLine = readlnOrNull() ?: return Update.Unchanged
        val trimmedFirst = firstLine.trim()

        when {
            trimmedFirst.equals("c", ignoreCase = true) || trimmedFirst.equals(
                "cancel",
                ignoreCase = true
            ) -> {
                println("[INFO] Operation cancelled.")
                return null
            }

            trimmedFirst.isEmpty() -> {
                println("[INFO] Notes unchanged.")
                return Update.Unchanged
            }

            trimmedFirst.equals("clear", ignoreCase = true) -> {
                println("[INFO] Notes cleared.")
                return Update.Clear
            }

            trimmedFirst == "." -> {
                println("[INFO] Notes unchanged.")
                return Update.Unchanged
            }
        }

        val lines = mutableListOf<String>()
        lines.add(firstLine)

        while (true) {
            print("... ")
            val nextLine = readlnOrNull() ?: break
            if (nextLine.trim() == ".") break
            lines.add(nextLine)
        }

        println("[INFO] Notes saved.")
        val cleanNotes = InputSanitizer.sanitizeMultiline(lines.joinToString("\n"))
        return if (cleanNotes != null) Update.Set(cleanNotes) else Update.Clear
    }

    private fun promptNap(current: Boolean?): Update<Boolean>? {
        val displayCurrent = current?.let { if (it) "Yes" else "No" } ?: "Not Set"
        while (true) {
            print("Napped Today (y/n) [Current: $displayCurrent] > ")
            val input = readlnOrNull()?.trim() ?: return Update.Unchanged

            when {
                input.equals("c", ignoreCase = true) || input.equals(
                    "cancel",
                    ignoreCase = true
                ) -> {
                    println("[INFO] Operation cancelled.")
                    return null
                }

                input.isEmpty() -> return Update.Unchanged
                input.equals("clear", ignoreCase = true) -> return Update.Clear
                else -> {
                    val result = PrimitiveParsers.parseBoolean(input, "Napped")
                    if (result.isSuccess) {
                        return Update.Set(result.getOrThrow()!!)
                    } else {
                        println("[ERROR] ${result.exceptionOrNull()?.message}")
                    }
                }
            }
        }
    }

    // --- Screen State Navigation ---

    private fun handleChangeDay() {
        print("Enter target epoch day (current: $activeEpochDay, 't' for today, 'c' to cancel) > ")
        val input = readlnOrNull()?.trim()

        if (input.equals("c", ignoreCase = true) || input.equals("cancel", ignoreCase = true)) {
            println("[INFO] Day change cancelled.")
            return
        }

        activeEpochDay = if (input.equals("t", ignoreCase = true) || input.isNullOrEmpty()) {
            timeProvider.getStandardEpochDay()
        } else {
            input.toLongOrNull() ?: activeEpochDay
        }
    }

    private suspend fun handleDelete() {
        print("Confirm deletion of day $activeEpochDay? (y/N) > ")
        val input = readlnOrNull()?.trim()
        if (input?.equals("y", ignoreCase = true) == true) {
            repository.softDeleteContext(activeEpochDay)
            println("[SUCCESS] Record deleted for day $activeEpochDay.")
        } else {
            println("[INFO] Deletion cancelled.")
        }
    }
}