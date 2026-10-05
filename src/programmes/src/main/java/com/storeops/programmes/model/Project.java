package com.storeops.programmes.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A store programme (e.g. a seasonal rollout or compliance drive). Immutable. */
public record Project(
        String id,
        String storeId,
        String name,
        String description,
        String ownerId,
        List<ProjectMember> members,
        Instant createdAt) {

    public Project {
        members = List.copyOf(members);
    }

    public boolean hasMember(String staffId) {
        return members.stream().anyMatch(m -> m.staffId().equals(staffId));
    }

    public Project withMember(ProjectMember member) {
        List<ProjectMember> updated = new ArrayList<>(members);
        updated.add(member);
        return new Project(id, storeId, name, description, ownerId, updated, createdAt);
    }
}
