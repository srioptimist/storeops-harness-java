package com.storeops.staff.model;

/** A registered store staff member. Immutable; exposed read-only to other modules. */
public record User(String id, String storeId, String regionId, StaffRole role, UserProfile profile) {
}
