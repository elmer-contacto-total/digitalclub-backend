package com.digitalgroup.holape.domain.common.enums;

/**
 * Import Status Enum matching Rails Import model exactly.
 *
 * Rails: enum status: {
 *   status_new: 0,
 *   status_valid: 1,
 *   status_invalid: 2,
 *   status_processing: 3,
 *   status_completed: 4,
 *   status_error: 5,
 *   status_validating: 6
 * }
 *
 * IMPORTANT: The ordinal position must match Rails values exactly
 * since we use EnumType.ORDINAL mapping.
 */
public enum ImportStatus {
    STATUS_NEW,          // ordinal 0
    STATUS_VALID,        // ordinal 1
    STATUS_INVALID,      // ordinal 2
    STATUS_PROCESSING,   // ordinal 3
    STATUS_COMPLETED,    // ordinal 4
    STATUS_ERROR,        // ordinal 5
    STATUS_VALIDATING;   // ordinal 6

    public boolean isNew() {
        return this == STATUS_NEW;
    }

    public boolean isValid() {
        return this == STATUS_VALID;
    }

    public boolean isInvalid() {
        return this == STATUS_INVALID;
    }

    public boolean isProcessing() {
        return this == STATUS_PROCESSING;
    }

    public boolean isCompleted() {
        return this == STATUS_COMPLETED;
    }

    public boolean isError() {
        return this == STATUS_ERROR;
    }

    public boolean isValidating() {
        return this == STATUS_VALIDATING;
    }

    public boolean isTerminal() {
        return this == STATUS_COMPLETED || this == STATUS_ERROR || this == STATUS_INVALID;
    }

    public boolean isActive() {
        return this == STATUS_PROCESSING || this == STATUS_VALIDATING;
    }
}
