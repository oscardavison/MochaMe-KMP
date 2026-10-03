package com.mochame.utils.ui

import kotlin.jvm.JvmInline

object InputSanitizer {
    private val HORIZONTAL_SPACE_REGEX = Regex("[^\\S\\r\\n]+") // Matches spaces/tabs but NOT \r or \n
    private val EXCESSIVE_NEWLINES_REGEX = Regex("(\\r?\\n){3,}") // Caps consecutive blank lines at 2

    /**
     * Sanitizes single-line fields (e.g. sleep duration, readiness score).
     * Collapses all whitespace (including newlines) into a single space.
     */
    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw
            .replace('\u00A0', ' ')
            .replace('\u200B', ' ')
            .trim()
            .replace(Regex("\\s+"), " ")

        return cleaned.ifBlank { null }
    }

    /**
     * Sanitizes multi-line notes.
     * Preserves intentional line breaks while stripping invisible characters
     * and collapsing run-away horizontal spaces.
     */
    fun sanitizeMultiline(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw
            .replace('\u00A0', ' ')
            .replace('\u200B', ' ')
            .replace("\r\n", "\n")
            .replace(HORIZONTAL_SPACE_REGEX, " ")
            .lines()
            .map { it.trim() }
            .joinToString("\n")
            .replace(EXCESSIVE_NEWLINES_REGEX, "\n\n")
            .trim()

        return cleaned.ifBlank { null }
    }

}

/** Inline value wrapper to differentiate between "No input / Valid Null" vs "Failed Validation" */
@JvmInline
value class ParsedInput<T>(val value: T?)

object PrimitiveParsers {

    private val DECIMAL_REGEX = Regex("^[+-]?[0-9]+([.,][0-9]+)?(\\s*[a-zA-Z]*)?$")
    private val INTEGER_REGEX = Regex("^[+-]?[0-9]+(\\s*[a-zA-Z]*)?$")

    fun parseBoundedDouble(
        raw: String?,
        range: ClosedFloatingPointRange<Double>,
        fieldName: String = "Value"
    ): Result<Double?> {
        val sanitized = InputSanitizer.sanitize(raw) ?: return Result.success(null)

        return runCatching {
            require(PrimitiveParsers.DECIMAL_REGEX.matches(sanitized)) {
                "Invalid numeric format for $fieldName: '$raw'."
            }
            val cleanNumber = sanitized.replace(',', '.')
                .replace(Regex("[^0-9.-]"), "")

            val parsed = cleanNumber.toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid decimal format for $fieldName.")

            require(parsed in range) {
                "$fieldName must be between ${range.start} and ${range.endInclusive} (got $parsed)."
            }
            parsed
        }
    }

    fun parseBoundedInt(
        raw: String?,
        range: IntRange,
        fieldName: String = "Value"
    ): Result<Int?> {
        val sanitized = InputSanitizer.sanitize(raw) ?: return Result.success(null)

        return runCatching {
            require(PrimitiveParsers.INTEGER_REGEX.matches(sanitized)) {
                "Invalid integer format for $fieldName: '$raw'."
            }
            val digitsOnly = sanitized.replace(Regex("[^0-9-]"), "")

            val parsed = digitsOnly.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid integer format for $fieldName.")

            require(parsed in range) {
                "$fieldName must be between ${range.first} and ${range.last} (got $parsed)."
            }
            parsed
        }
    }

    fun parseBoolean(raw: String?, fieldName: String = "Flag"): Result<Boolean?> {
        val sanitized = InputSanitizer.sanitize(raw)?.lowercase() ?: return Result.success(null)

        return when (sanitized) {
            "true", "t", "yes", "y", "1", "on" -> Result.success(true)
            "false", "f", "no", "n", "0", "off" -> Result.success(false)
            else -> Result.failure(
                IllegalArgumentException("Invalid boolean for $fieldName: '$raw'. Use yes/no, true/false, or 1/0.")
            )
        }
    }
}