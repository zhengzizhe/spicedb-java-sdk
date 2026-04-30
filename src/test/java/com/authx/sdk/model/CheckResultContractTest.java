package com.authx.sdk.model;

import com.authx.sdk.model.enums.Permissionship;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckResultContractTest {

    @Test
    void allowedAndDeniedExposeOnlyPermissionOutcomeAndZedToken() {
        CheckResult allowed = CheckResult.allowed("token-1");
        CheckResult denied = CheckResult.denied("token-2");

        assertEquals(Permissionship.HAS_PERMISSION, allowed.permissionship());
        assertEquals("token-1", allowed.zedToken());
        assertTrue(allowed.hasPermission());
        assertFalse(allowed.isConditional());

        assertEquals(Permissionship.NO_PERMISSION, denied.permissionship());
        assertEquals("token-2", denied.zedToken());
        assertFalse(denied.hasPermission());
        assertFalse(denied.isConditional());
    }

    @Test
    void conditionalPermissionIsDistinctFromAllowed() {
        CheckResult result = new CheckResult(Permissionship.CONDITIONAL_PERMISSION, "token-3");

        assertFalse(result.hasPermission());
        assertTrue(result.isConditional());
    }
}
