package com.storeops.shared.error;

/** The request conflicts with the current state of a resource (HTTP 409). */
public class ConflictError extends AppError {

    private static final long serialVersionUID = 1L;

    public ConflictError(String message) {
        super("CONFLICT", message, 409);
    }
}
