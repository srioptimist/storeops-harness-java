package com.storeops.activities.api;

import com.storeops.activities.model.Task;
import java.util.List;

/** Read-only public API of the activities module (used by reports for aggregation). */
public interface ActivityDirectory {

    List<Task> findByStore(String storeId);
}
