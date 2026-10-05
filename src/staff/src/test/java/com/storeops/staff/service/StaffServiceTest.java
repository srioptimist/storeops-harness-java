package com.storeops.staff.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeops.staff.api.AuthenticatedUser;
import com.storeops.staff.model.AuthToken;
import com.storeops.staff.model.StaffRole;
import com.storeops.staff.repository.InMemoryStaffRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StaffServiceTest {

    private InMemoryStaffRepository repository;
    private StaffService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryStaffRepository();
        service = new StaffService(repository);
    }

    @Test
    void authenticatesSeededToken() {
        AuthenticatedUser user = service.authenticate("dev-token-manager").orElseThrow();

        assertThat(user.userId()).isEqualTo("staff-manager-001");
        assertThat(user.storeId()).isEqualTo("store-001");
        assertThat(user.role()).isEqualTo(StaffRole.STORE_MANAGER);
        assertThat(user.isStoreManager()).isTrue();
    }

    @Test
    void rejectsUnknownBlankAndNullTokens() {
        assertThat(service.authenticate("nope")).isEmpty();
        assertThat(service.authenticate("  ")).isEmpty();
        assertThat(service.authenticate(null)).isEmpty();
    }

    @Test
    void rejectsExpiredToken() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        repository.saveToken(new AuthToken("expired", "staff-lead-001", now.minusSeconds(1)));
        StaffService clocked = new StaffService(repository, Clock.fixed(now, ZoneOffset.UTC));

        assertThat(clocked.authenticate("expired")).isEmpty();
    }

    @Test
    void findsAndChecksExistence() {
        assertThat(service.findById("staff-assoc-001")).get()
                .extracting(u -> u.profile().displayName()).isEqualTo("Ada Associate");
        assertThat(service.exists("staff-assoc-001")).isTrue();
        assertThat(service.exists("ghost")).isFalse();
    }
}
