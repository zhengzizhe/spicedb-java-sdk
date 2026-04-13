package com.authx.sdk.model.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class EnumTest {

    @Test void operationResult_values() {
        assertThat(OperationResult.values()).containsExactly(
            OperationResult.SUCCESS, OperationResult.ERROR);
    }

    @Test void operationResult_valueOf() {
        assertThat(OperationResult.valueOf("SUCCESS")).isEqualTo(OperationResult.SUCCESS);
        assertThat(OperationResult.valueOf("ERROR")).isEqualTo(OperationResult.ERROR);
    }

    @Test void permissionship_values() {
        assertThat(Permissionship.values()).containsExactly(
            Permissionship.HAS_PERMISSION,
            Permissionship.NO_PERMISSION,
            Permissionship.CONDITIONAL_PERMISSION);
    }

    @Test void sdkAction_allValues() {
        assertThat(SdkAction.values()).containsExactly(
            SdkAction.CHECK,
            SdkAction.CHECK_BULK,
            SdkAction.WRITE,
            SdkAction.DELETE,
            SdkAction.READ,
            SdkAction.LOOKUP_SUBJECTS,
            SdkAction.LOOKUP_RESOURCES,
            SdkAction.EXPAND);
    }

    @Test void sdkAction_valueOf() {
        assertThat(SdkAction.valueOf("CHECK")).isEqualTo(SdkAction.CHECK);
        assertThat(SdkAction.valueOf("EXPAND")).isEqualTo(SdkAction.EXPAND);
    }
}
