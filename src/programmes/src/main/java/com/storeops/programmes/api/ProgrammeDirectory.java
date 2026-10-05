package com.storeops.programmes.api;

import com.storeops.programmes.model.Project;
import java.util.Optional;

/** Read-only public API of the programmes module for use by other modules. */
public interface ProgrammeDirectory {

    Optional<Project> findById(String programmeId);

    boolean exists(String programmeId);
}
