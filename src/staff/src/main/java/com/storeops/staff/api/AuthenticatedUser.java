package com.storeops.staff.api;

import com.storeops.staff.model.StaffRole;

/**
 * The caller of the current request. Declare it as a controller method parameter to require
 * authentication; it is resolved from the {@code Authorization: Bearer <token>} header.
 */
public record AuthenticatedUser(String userId, String storeId, String regionId, StaffRole role) {

    public boolean isStoreManager() {
        return role == StaffRole.STORE_MANAGER;
    }
}
