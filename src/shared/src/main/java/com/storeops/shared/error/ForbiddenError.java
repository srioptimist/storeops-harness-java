package com.storeops.shared.error;

/** The caller is authenticated but not allowed to perform the action (HTTP 403). */
public class ForbiddenError extends AppError {

    private static final long serialVersionUID = 1L;

    public ForbiddenError(String message) {
        super("FORBIDDEN", message, 403);
    }
}
