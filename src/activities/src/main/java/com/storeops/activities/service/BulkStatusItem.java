package com.storeops.activities.service;

import com.storeops.activities.model.TaskStatus;

/** One requested status change within a bulk status update. */
public record BulkStatusItem(String activityId, TaskStatus status) {
}
