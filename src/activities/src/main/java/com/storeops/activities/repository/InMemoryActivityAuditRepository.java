package com.storeops.activities.repository;

import com.storeops.activities.model.ActivityAuditEntry;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/** Thread-safe in-memory activity audit store. */
@Repository
public class InMemoryActivityAuditRepository implements ActivityAuditRepository {

    private final Map<String, ActivityAuditEntry> entries = new ConcurrentHashMap<>();

    @Override
    public ActivityAuditEntry save(ActivityAuditEntry entry) {
        entries.put(entry.id(), entry);
        return entry;
    }

    @Override
    public List<ActivityAuditEntry> findByActivityId(String activityId) {
        return entries.values().stream()
                .filter(e -> e.activityId().equals(activityId))
                .sorted(Comparator.comparing(ActivityAuditEntry::occurredAt))
                .toList();
    }
}
