package com.storeops.shared.error;

/** The caller is not authenticated (HTTP 401). */
public class UnauthorizedError extends AppError {

    private static final long serialVersionUID = 1L;

    public UnauthorizedError(String message) {
        super("UNAUTHORIZED", message, 401);
    }
}
