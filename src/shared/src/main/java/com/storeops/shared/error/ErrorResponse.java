package com.storeops.shared.error;

/** JSON error body returned for every failed request. */
public record ErrorResponse(String code, String message, int statusCode) {

    public static ErrorResponse from(AppError error) {
        return new ErrorResponse(error.getCode(), error.getMessage(), error.getStatusCode());
    }
}
