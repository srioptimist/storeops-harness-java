package com.storeops.shared.error;

/** A requested resource does not exist (HTTP 404). */
public class NotFoundError extends AppError {

    private static final long serialVersionUID = 1L;

    public NotFoundError(String resource, String id) {
        super("NOT_FOUND", resource + " '" + id + "' not found", 404);
    }
}
