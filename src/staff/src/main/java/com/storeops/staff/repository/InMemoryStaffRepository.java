package com.storeops.staff.repository;

import com.storeops.staff.model.AuthToken;
import com.storeops.staff.model.StaffRole;
import com.storeops.staff.model.User;
import com.storeops.staff.model.UserProfile;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * In-memory staff store, seeded with development users so the API is usable without a
 * registration flow. Tokens: {@code dev-token-manager}, {@code dev-token-lead},
 * {@code dev-token-associate}, {@code dev-token-other-store}.
 */
@Repository
public class InMemoryStaffRepository implements StaffRepository {

    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Map<String, AuthToken> tokens = new ConcurrentHashMap<>();

    public InMemoryStaffRepository() {
        Instant expiry = Instant.now().plus(3650, ChronoUnit.DAYS);
        seed("staff-manager-001", "store-001", StaffRole.STORE_MANAGER, "Maya Manager", "dev-token-manager", expiry);
        seed("staff-lead-001", "store-001", StaffRole.DEPARTMENT_LEAD, "Leo Lead", "dev-token-lead", expiry);
        seed("staff-assoc-001", "store-001", StaffRole.ASSOCIATE, "Ada Associate", "dev-token-associate", expiry);
        seed("staff-manager-002", "store-002", StaffRole.STORE_MANAGER, "Omar Other", "dev-token-other-store", expiry);
    }

    private void seed(String id, String storeId, StaffRole role, String name, String token, Instant expiry) {
        String email = id + "@storeops.example";
        users.put(id, new User(id, storeId, "region-north", role, new UserProfile(name, email, "GENERAL")));
        tokens.put(token, new AuthToken(token, id, expiry));
    }

    @Override
    public Optional<User> findById(String userId) {
        return Optional.ofNullable(users.get(userId));
    }

    @Override
    public User save(User user) {
        users.put(user.id(), user);
        return user;
    }

    @Override
    public Optional<AuthToken> findToken(String token) {
        return Optional.ofNullable(tokens.get(token));
    }

    @Override
    public AuthToken saveToken(AuthToken token) {
        tokens.put(token.token(), token);
        return token;
    }
}
