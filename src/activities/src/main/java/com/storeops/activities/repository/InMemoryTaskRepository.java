package com.storeops.activities.repository;

import com.storeops.activities.model.Task;
import com.storeops.activities.model.TaskStatus;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/** Thread-safe in-memory activity store. */
@Repository
public class InMemoryTaskRepository implements TaskRepository {

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    @Override
    public Optional<Task> findById(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public List<Task> findAll(String programmeId, TaskStatus status) {
        return tasks.values().stream()
                .filter(t -> programmeId == null || programmeId.equals(t.programmeId()))
                .filter(t -> status == null || status == t.status())
                .sorted(Comparator.comparing(Task::createdAt))
                .toList();
    }

    @Override
    public List<Task> findByStore(String storeId) {
        return tasks.values().stream()
                .filter(t -> t.storeId().equals(storeId))
                .sorted(Comparator.comparing(Task::createdAt))
                .toList();
    }

    @Override
    public Task save(Task task) {
        tasks.put(task.id(), task);
        return task;
    }

    @Override
    public boolean deleteById(String id) {
        return tasks.remove(id) != null;
    }
}
