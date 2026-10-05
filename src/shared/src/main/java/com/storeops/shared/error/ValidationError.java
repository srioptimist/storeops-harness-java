package com.storeops.shared.error;

/** The request is syntactically or semantically invalid (HTTP 400). */
public class ValidationError extends AppError {

    private static final long serialVersionUID = 1L;

    public ValidationError(String message) {
        super("VALIDATION_ERROR", message, 400);
    }
}
