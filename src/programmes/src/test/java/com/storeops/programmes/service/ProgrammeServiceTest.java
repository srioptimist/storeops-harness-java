package com.storeops.programmes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeops.programmes.model.Project;
import com.storeops.programmes.model.ProjectRole;
import com.storeops.programmes.repository.InMemoryProjectRepository;
import com.storeops.shared.error.ConflictError;
import com.storeops.shared.error.ForbiddenError;
import com.storeops.shared.error.NotFoundError;
import com.storeops.shared.error.ValidationError;
import com.storeops.shared.events.EventBus;
import com.storeops.shared.events.ProgrammeMemberAddedEvent;
import com.storeops.staff.api.AuthenticatedUser;
import com.storeops.staff.api.StaffDirectory;
import com.storeops.staff.model.StaffRole;
import com.storeops.staff.model.User;
import com.storeops.staff.model.UserProfile;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProgrammeServiceTest {

    private static final AuthenticatedUser MANAGER =
            new AuthenticatedUser("m1", "store-001", "region-north", StaffRole.STORE_MANAGER);
    private static final AuthenticatedUser ASSOCIATE =
            new AuthenticatedUser("a1", "store-001", "region-north", StaffRole.ASSOCIATE);
    private static final AuthenticatedUser OTHER_STORE =
            new AuthenticatedUser("m2", "store-002", "region-north", StaffRole.STORE_MANAGER);

    private StaffDirectory staffDirectory;
    private EventBus eventBus;
    private ProgrammeService service;

    @BeforeEach
    void setUp() {
        staffDirectory = mock(StaffDirectory.class);
        eventBus = mock(EventBus.class);
        service = new ProgrammeService(new InMemoryProjectRepository(), staffDirectory, eventBus);
    }

    @Test
    void createsAndListsProgrammesScopedToCallerStore() {
        Project created = service.create(MANAGER, "Autumn rollout", "Seasonal reset");
        service.create(OTHER_STORE, "Other store refit", null);

        assertThat(created.ownerId()).isEqualTo("m1");
        assertThat(created.members()).isEmpty();
        assertThat(service.listForStore(MANAGER)).extracting(Project::name).containsExactly("Autumn rollout");
        assertThat(service.findById(created.id())).contains(created);
        assertThat(service.exists(created.id())).isTrue();
        assertThat(service.exists("missing")).isFalse();
    }

    @Test
    void addMemberAddsStaffAndEmitsEvent() {
        Project project = service.create(MANAGER, "Refit", null);
        when(staffDirectory.findById("s1")).thenReturn(Optional.of(staff("s1", "store-001")));

        Project updated = service.addMember(MANAGER, project.id(), "s1", ProjectRole.ASSOCIATE);

        assertThat(updated.hasMember("s1")).isTrue();
        ArgumentCaptor<ProgrammeMemberAddedEvent> event = ArgumentCaptor.forClass(ProgrammeMemberAddedEvent.class);
        verify(eventBus).emit(event.capture());
        assertThat(event.getValue().staffId()).isEqualTo("s1");
        assertThat(event.getValue().role()).isEqualTo("ASSOCIATE");
    }

    @Test
    void addMemberRejectsAssociates() {
        Project project = service.create(MANAGER, "Refit", null);

        assertThatThrownBy(() -> service.addMember(ASSOCIATE, project.id(), "s1", ProjectRole.ASSOCIATE))
                .isInstanceOf(ForbiddenError.class);
        verify(eventBus, never()).emit(any());
    }

    @Test
    void addMemberHidesProgrammesOfOtherStores() {
        Project project = service.create(OTHER_STORE, "Other", null);

        assertThatThrownBy(() -> service.addMember(MANAGER, project.id(), "s1", ProjectRole.ASSOCIATE))
                .isInstanceOf(NotFoundError.class);
    }

    @Test
    void addMemberRequiresExistingStaffInSameStore() {
        Project project = service.create(MANAGER, "Refit", null);
        when(staffDirectory.findById("ghost")).thenReturn(Optional.empty());
        when(staffDirectory.findById("far")).thenReturn(Optional.of(staff("far", "store-999")));

        assertThatThrownBy(() -> service.addMember(MANAGER, project.id(), "ghost", ProjectRole.ASSOCIATE))
                .isInstanceOf(NotFoundError.class);
        assertThatThrownBy(() -> service.addMember(MANAGER, project.id(), "far", ProjectRole.ASSOCIATE))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void addMemberRejectsDuplicates() {
        Project project = service.create(MANAGER, "Refit", null);
        when(staffDirectory.findById("s1")).thenReturn(Optional.of(staff("s1", "store-001")));
        service.addMember(MANAGER, project.id(), "s1", ProjectRole.ASSOCIATE);

        assertThatThrownBy(() -> service.addMember(MANAGER, project.id(), "s1", ProjectRole.DEPARTMENT_LEAD))
                .isInstanceOf(ConflictError.class)
                .hasFieldOrPropertyWithValue("statusCode", 409);
    }

    private static User staff(String id, String storeId) {
        return new User(id, storeId, "region-north", StaffRole.ASSOCIATE, new UserProfile(id, id + "@x", "GENERAL"));
    }
}
