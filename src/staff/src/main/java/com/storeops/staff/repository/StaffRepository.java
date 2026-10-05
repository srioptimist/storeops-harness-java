package com.storeops.staff.repository;

import com.storeops.staff.model.AuthToken;
import com.storeops.staff.model.User;
import java.util.Optional;

/** Data access for staff and their tokens. Internal to the staff module. */
public interface StaffRepository {

    Optional<User> findById(String userId);

    User save(User user);

    Optional<AuthToken> findToken(String token);

    AuthToken saveToken(AuthToken token);
}
