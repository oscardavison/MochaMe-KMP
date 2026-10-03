@file:OptIn(ExperimentalKermitApi::class)

package com.mochame.sync.spi

import co.touchlab.kermit.ExperimentalKermitApi
import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.spi.infrastructure.serialization.FieldHlcMap
import com.mochame.sync.spi.infrastructure.serialization.FieldMergeScope
import kotlinx.coroutines.test.TestScope
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull


private inline fun runEnv(crossinline block: SyncApiTestEnv.(TestScope) -> Unit) =
    runUnitEnvironment<SyncApiTestEnv>(
        koinSetup = { modules(SyncApiTestModule::class) },
        block = block
    )


class FieldMergeScopeTest : MochaPlatformTest() {

    // ===================================================================
    // NULL INCOMING VALUE (PROTOBUF ABSENT FIELD VIA IMPLICIT FIELDS)
    // ===================================================================

    @Test
    fun should_retainExistingValueAndRetainIndex_when_incomingValueIsNull() = runEnv {
        // Given
        val initialHlc = createHlc(ts = 100L, nodeId = TestNodeId.A)
        val existingBytes = FieldHlcMap.EMPTY.updateTag(tagId = 1, hlc = initialHlc).bytes
        val incomingHlc =
            createHlc(ts = 500L, nodeId = TestNodeId.B) // Higher clock, but incoming value is null

        val scope = FieldMergeScope(existingBytes, incomingHlc, 0L, logger)

        // When: Remote delta did not change the individual field
        val result = scope.eval(tagId = 1, incomingVal = null, existingVal = "currentValue")

        // Then: Retains existing value and leaves local tag HLC untouched
        assertEquals("currentValue", result)
        assertEquals(
            initialHlc,
            scope.getHlc(tagId = 1),
            "Tag 1 HLC must not be updated when incoming value is null"
        )
        writer.assertFieldRejectionLogCount(0)
    }

    // ===================================================================
    // MISSING LOCAL TAG (FIRST INSERTION)
    // ===================================================================

    @Test
    fun should_acceptIncomingValueAndInsertTag_when_localTagDoesNotExist() = runEnv {
        // Given: Scope initialized with empty bytes (no local tags exist)
        val incomingHlc = createHlc(ts = 200L, count = 1)
        val scope = FieldMergeScope(
            existingBytes = ByteArray(0),
            incomingHlc = incomingHlc,
            changedMask = 0b00001000L,
            logger
        )

        // When
        val result = scope.eval(tagId = 3, incomingVal = "insertedText", existingVal = "")

        // Then: Accepts incoming value and inserts tag with incoming HLC
        assertEquals("insertedText", result)
        assertEquals(incomingHlc, scope.getHlc(tagId = 3))
        assertEquals(FieldHlcMap.RECORD_SIZE, scope.buildResultBlob().size)
        writer.assertFieldRejectionLogCount(0)
    }

    // ===================================================================
    // HIGHER CLOCK (LWW ACCEPTANCE)
    // ===================================================================

    @Test
    fun should_acceptIncomingValueAndUpdateTag_when_incomingHlcIsHigherThanLocalTagHlc() = runEnv {
        // Given: Existing blob with Tag 4 at HLC(ts = 100L)
        val localHlc = createHlc(ts = 100L, count = 1)
        val existingBytes = FieldHlcMap.EMPTY.updateTag(tagId = 4, hlc = localHlc).bytes

        val higherIncomingHlc = createHlc(ts = 200L, count = 1)
        val scope =
            FieldMergeScope(
                existingBytes,
                incomingHlc = higherIncomingHlc,
                changedMask = 0b00010000L,
                logger
            )

        // When
        val result =
            scope.eval(tagId = 4, incomingVal = "newAcceptedValue", existingVal = "oldLocalValue")

        // Then
        assertEquals("newAcceptedValue", result)
        assertEquals(higherIncomingHlc, scope.getHlc(tagId = 4))
        assertEquals(
            FieldHlcMap.RECORD_SIZE,
            scope.buildResultBlob().size,
            "Size must remain 27 bytes for in-place update"
        )
        writer.assertFieldRejectionLogCount(0)
    }

    // ===================================================================
    // LOWER CLOCK (LWW REJECTION / STALE PAYLOAD)
    // ===================================================================

    @Test
    fun should_rejectIncomingValueAndKeepLocalTag_when_incomingHlcIsLowerThanLocalTagHlc() =
        runEnv {
            // Given: Existing blob with Tag 4 at HLC(ts = 500L)
            val localHlc = createHlc(ts = 500L, count = 1)
            val existingBytes = FieldHlcMap.EMPTY.updateTag(tagId = 4, hlc = localHlc).bytes

            val staleIncomingHlc = createHlc(ts = 200L, count = 1)
            val scope = FieldMergeScope(
                existingBytes = existingBytes,
                incomingHlc = staleIncomingHlc,
                changedMask = 0b00010000L,
                logger
            )

            // When
            val result = scope.eval(
                tagId = 4,
                incomingVal = "staleIncomingValue",
                existingVal = "winnerLocalValue"
            )

            // Then: Rejects incoming value, retains existing value, and preserves local tag HLC
            assertEquals("winnerLocalValue", result)
            assertEquals(localHlc, scope.getHlc(tagId = 4), "Tag 4 HLC must remain at 500L")
            writer.assertFieldRejectionLogCount(1)
        }

    // ===================================================================
    // MULTI-FIELD MERGE & BLOB AGGREGATION
    // ===================================================================

    @Test
    fun should_correctlyAggregateBinaryResultBlob_when_simulatingRestore() = runEnv {
        // Given
        val hlcTs100 = createHlc(ts = 100L)
        val hlcTs200 = createHlc(ts = 200L)
        val initialBytes = FieldHlcMap.EMPTY
            .updateTag(tagId = 1, hlc = hlcTs100)
            .updateTag(tagId = 2, hlc = hlcTs200)
            .bytes

        val incomingHlcTs300 = createHlc(ts = 300L)
        val scope = FieldMergeScope(
            existingBytes = initialBytes,
            incomingHlc = incomingHlcTs300,
            changedMask = 0b00001110L,
            logger
        )

        // When
        val field1 = scope.eval(tagId = 1, incomingVal = "val1_new", existingVal = "val1_old")
        val field2 = scope.eval(tagId = 2, incomingVal = "val2_new", existingVal = "val2_old")
        val field3 = scope.eval(tagId = 3, incomingVal = "val3_new", existingVal = "val3_old")
        val field4 = scope.eval(tagId = 4, incomingVal = null, existingVal = "val4_old")

        // Then
        assertEquals("val1_new", field1)
        assertEquals("val2_new", field2)
        assertEquals("val3_new", field3)
        assertEquals("val4_old", field4)

        val resultBlob = scope.buildResultBlob()
        assertEquals(FieldHlcMap.RECORD_SIZE * 3, resultBlob.size)

        val resultMap = FieldHlcMap(resultBlob)
        assertEquals(incomingHlcTs300, resultMap.getHlc(tagId = 1))
        assertEquals(incomingHlcTs300, resultMap.getHlc(tagId = 2))
        assertEquals(incomingHlcTs300, resultMap.getHlc(tagId = 3))
        assertNull(resultMap.getHlc(tagId = 4))
        writer.assertFieldRejectionLogCount(0)
    }

    // ===================================================================
    // DELETION SWEEP
    // ===================================================================

    @Test
    fun should_sweepOlderFieldsToNullAndRetainNewerFields_when_deltaIsDeletion() = runEnv {
        // Given
        val olderHlc = createHlc(ts = 100L)
        val newerHlc = createHlc(ts = 500L)
        val existingBytes = FieldHlcMap.EMPTY
            .updateTag(tagId = 1, hlc = olderHlc)
            .updateTag(tagId = 2, hlc = newerHlc)
            .bytes

        val deleteHlc = createHlc(ts = 300L)
        val scope = FieldMergeScope(
            existingBytes = existingBytes,
            incomingHlc = deleteHlc,
            changedMask = 0L,
            logger = logger,
            isDelete = true
        )

        // When
        val sweptField = scope.eval(tagId = 1, incomingVal = null, existingVal = "activeValue")
        val survivingField = scope.eval(tagId = 2, incomingVal = null, existingVal = "recentValue")
        val unrecordedField = scope.eval(tagId = 3, incomingVal = null, existingVal = "untrackedValue")

        // Then
        assertNull(sweptField)
        assertEquals("recentValue", survivingField)
        assertNull(unrecordedField)

        assertEquals(deleteHlc, scope.getHlc(tagId = 1))
        assertEquals(newerHlc, scope.getHlc(tagId = 2))
        assertEquals(deleteHlc, scope.getHlc(tagId = 3))
        assertEquals(FieldHlcMap.RECORD_SIZE * 3, scope.buildResultBlob().size)
    }

    // ===================================================================
    // EXPLICIT TAG MUTATION
    // ===================================================================

    @Test
    fun should_updateTagDirectlyInIndex_when_invokingUpdateTag() = runEnv {
        // Given
        val initialHlc = createHlc(ts = 100L)
        val updatedHlc = createHlc(ts = 400L)
        val existingBytes = FieldHlcMap.EMPTY.updateTag(tagId = 2, hlc = initialHlc).bytes
        val scope = FieldMergeScope(existingBytes, incomingHlc = initialHlc, changedMask = 0L, logger)

        // When
        scope.updateTag(tagId = 2, hlc = updatedHlc)

        // Then
        assertEquals(updatedHlc, scope.getHlc(tagId = 2))
        assertEquals(FieldHlcMap.RECORD_SIZE, scope.buildResultBlob().size)
    }

}




