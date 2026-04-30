package com.authx.sdk.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteResultContractTest {

    @Test
    void knownSubmittedUpdateCountUsesSubmittedCountNotNetDatabaseChanges() {
        WriteResult result = new WriteResult("token-1", 3);

        assertEquals("token-1", result.zedToken());
        assertEquals(3, result.submittedUpdateCount());
        assertEquals(3, result.updateCount());
        assertEquals(3, result.count());
        assertTrue(result.submittedUpdateCountKnown());
        assertTrue(result.updateCountKnown());
    }

    @Test
    void unknownSubmittedUpdateCountIsExplicit() {
        WriteResult result = WriteResult.unknownSubmittedUpdateCount("token-2");

        assertEquals("token-2", result.zedToken());
        assertEquals(WriteResult.UNKNOWN_SUBMITTED_UPDATE_COUNT, result.submittedUpdateCount());
        assertFalse(result.submittedUpdateCountKnown());
        assertFalse(result.updateCountKnown());
    }

    @Test
    void rejectsCountsBelowUnknownSentinel() {
        assertThrows(IllegalArgumentException.class, () -> new WriteResult("token-3", -2));
    }
}
