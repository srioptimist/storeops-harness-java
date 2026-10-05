package com.storeops.staff.service;

import com.storeops.staff.api.AuthenticatedUser;
import com.storeops.staff.api.StaffDirectory;
import com.storeops.staff.model.AuthToken;
import com.storeops.staff.model.User;
import com.storeops.staff.repository.StaffRepository;
import java.time.Clock;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Staff business logic. Implements the read-only {@link StaffDirectory} used by other modules. */
@Service
public class StaffService implements StaffDirectory {

    private final StaffRepository repository;
    private final Clock clock;

    @Autowired
    public StaffService(StaffRepository repository) {
        this(repository, Clock.systemUTC());
    }

    StaffService(StaffRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public Optional<User> findById(String userId) {
        return repository.findById(userId);
    }

    @Override
    public boolean exists(String userId) {
        return repository.findById(userId).isPresent();
    }

    @Override
    public Optional<AuthenticatedUser> authenticate(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return Optional.empty();
        }
        return repository.findToken(bearerToken)
                .filter(token -> !token.isExpired(clock.instant()))
                .map(AuthToken::userId)
                .flatMap(repository::findById)
                .map(user -> new AuthenticatedUser(user.id(), user.storeId(), user.regionId(), user.role()));
    }
}
