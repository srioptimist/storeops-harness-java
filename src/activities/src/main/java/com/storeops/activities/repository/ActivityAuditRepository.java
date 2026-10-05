package com.storeops.activities.repository;

import com.storeops.activities.model.ActivityAuditEntry;
import java.util.List;

/** Data access for activity audit entries. Internal to the activities module. */
public interface ActivityAuditRepository {

    ActivityAuditEntry save(ActivityAuditEntry entry);

    /** Lists the audit entries of one activity, oldest first. */
    List<ActivityAuditEntry> findByActivityId(String activityId);
}
