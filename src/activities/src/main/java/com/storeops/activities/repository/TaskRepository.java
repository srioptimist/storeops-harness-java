package com.storeops.activities.repository;

import com.storeops.activities.model.Task;
import com.storeops.activities.model.TaskStatus;
import java.util.List;
import java.util.Optional;

/** Data access for activities. Internal to the activities module. */
public interface TaskRepository {

    Optional<Task> findById(String id);

    /** Lists tasks, optionally filtered; {@code null} filters are ignored. */
    List<Task> findAll(String programmeId, TaskStatus status);

    List<Task> findByStore(String storeId);

    Task save(Task task);

    boolean deleteById(String id);
}
