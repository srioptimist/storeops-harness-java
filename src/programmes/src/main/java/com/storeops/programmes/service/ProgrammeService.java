package com.storeops.programmes.service;

import com.storeops.programmes.api.ProgrammeDirectory;
import com.storeops.programmes.model.Project;
import com.storeops.programmes.model.ProjectMember;
import com.storeops.programmes.model.ProjectRole;
import com.storeops.programmes.repository.ProjectRepository;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Programme business logic. Stub implementation; rules are intentionally minimal. */
@Service
public class ProgrammeService implements ProgrammeDirectory {

    private static final String RESOURCE = "Programme";

    private final ProjectRepository repository;
    private final StaffDirectory staffDirectory;
    private final EventBus eventBus;

    public ProgrammeService(ProjectRepository repository, StaffDirectory staffDirectory, EventBus eventBus) {
        this.repository = repository;
        this.staffDirectory = staffDirectory;
        this.eventBus = eventBus;
    }

    public List<Project> listForStore(AuthenticatedUser caller) {
        return repository.findByStore(caller.storeId());
    }

    public Project create(AuthenticatedUser caller, String name, String description) {
        Project project = new Project(UUID.randomUUID().toString(), caller.storeId(), name, description,
                caller.userId(), List.of(), Instant.now());
        return repository.save(project);
    }

    public Project addMember(AuthenticatedUser caller, String programmeId, String staffId, ProjectRole role) {
        if (caller.role() == StaffRole.ASSOCIATE) {
            throw new ForbiddenError("Associates cannot manage programme membership");
        }
        Project project = repository.findById(programmeId)
                .filter(p -> p.storeId().equals(caller.storeId()))
                .orElseThrow(() -> new NotFoundError(RESOURCE, programmeId));
        User staff = staffDirectory.findById(staffId)
                .orElseThrow(() -> new NotFoundError("Staff member", staffId));
        if (!staff.storeId().equals(project.storeId())) {
            throw new ValidationError("Staff member '" + staffId + "' belongs to a different store");
        }
        if (project.hasMember(staffId)) {
            throw new ConflictError("Staff member '" + staffId + "' is already a member of this programme");
        }
        Instant now = Instant.now();
        Project updated = repository.save(project.withMember(new ProjectMember(staffId, role, now)));
        eventBus.emit(new ProgrammeMemberAddedEvent(updated.id(), updated.name(), staffId, role.name(), now));
        return updated;
    }

    @Override
    public Optional<Project> findById(String programmeId) {
        return repository.findById(programmeId);
    }

    @Override
    public boolean exists(String programmeId) {
        return repository.findById(programmeId).isPresent();
    }
}
