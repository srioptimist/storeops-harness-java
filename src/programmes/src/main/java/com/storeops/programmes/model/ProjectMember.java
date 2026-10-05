package com.storeops.programmes.model;

import java.time.Instant;

/** Membership of a staff member in a programme. */
public record ProjectMember(String staffId, ProjectRole role, Instant joinedAt) {
}
