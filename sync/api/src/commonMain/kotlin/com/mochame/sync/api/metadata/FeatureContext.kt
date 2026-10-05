package com.mochame.sync.api.metadata

import com.mochame.annotations.InternalTestApi
import com.mochame.sync.api.exceptions.MochaException
import kotlinx.serialization.Serializable

enum class ContextCategory {
    SYSTEM,
    TEST,
    DOMAIN
}

@Serializable
enum class FeatureContext(
    val modelId: Int,
    val featureName: String,
    val modelName: String,
    val category: ContextCategory = ContextCategory.DOMAIN
) {
    UNRECOGNIZED_MODEL(0, "SYSTEM", "UNRECOGNIZED", ContextCategory.SYSTEM),

    BIO_DAILY_CONTEXT(1, "BIO", "DAILY_CONTEXT"),

//    TELEMETRY_TOPIC(2, "TELEMETRY", "TOPIC"),
//    TELEMETRY_DOMAIN(3, "TELEMETRY", "DOMAIN"),
//    TELEMETRY_MOMENT(4, "TELEMETRY", "MOMENT"),
//
//    RESONANCE_BOOK(5, "RESONANCE", "BOOK"),
//    RESONANCE_AUTHOR(6, "RESONANCE", "AUTHOR"),
//    RESONANCE_QUOTE(7, "RESONANCE", "QUOTE"),

    @InternalTestApi
    TEST_STUB_A(9001, "TEST", "A", ContextCategory.TEST),

    @InternalTestApi
    TEST_STUB_B(9002, "TEST", "B", ContextCategory.TEST);

    val isProductionEntity: Boolean
        get() = this.category == ContextCategory.DOMAIN

    companion object {
        val allFeatureModules: List<String> by lazy {
            entries
                .asSequence()
                .filter { it != UNRECOGNIZED_MODEL }
                .map { it.featureName }
                .distinct()
                .toList()
        }

        private val modelStringLookup by lazy { entries.associateBy { it.modelName } }

        fun fromModelString(model: String) = modelStringLookup[model]
            ?: throw MochaException.Transient.StateIssue("Unknown model name: $model")

        private val idLookup: Map<Int, FeatureContext> = buildMap(entries.size) {
            for (entry in FeatureContext.entries) {
                val existing = put(entry.modelId, entry)
                require(existing == null) {
                    "Duplicate moduleId detected: ${entry.modelId} on ${entry.name} and ${existing?.name}"
                }
            }
        }

        fun fromModelId(id: Int): FeatureContext = idLookup[id] ?: UNRECOGNIZED_MODEL
    }
}