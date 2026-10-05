package com.storeops.staff.api;

import com.storeops.staff.model.User;
import java.util.Optional;

/**
 * Read-only public API of the staff module. This is the only staff type other modules may
 * depend on (besides {@code staff.model} and {@link AuthenticatedUser}); it exposes no writes.
 */
public interface StaffDirectory {

    Optional<User> findById(String userId);

    boolean exists(String userId);

    Optional<AuthenticatedUser> authenticate(String bearerToken);
}
