package com.storeops.staff.model;

import java.time.Instant;

/** Bearer token issued to a staff member. */
public record AuthToken(String token, String userId, Instant expiresAt) {

    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }
}
