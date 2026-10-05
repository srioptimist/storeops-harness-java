package com.storeops.programmes.repository;

import com.storeops.programmes.model.Project;
import java.util.List;
import java.util.Optional;

/** Data access for programmes. Internal to the programmes module. */
public interface ProjectRepository {

    Optional<Project> findById(String id);

    List<Project> findByStore(String storeId);

    Project save(Project project);
}
