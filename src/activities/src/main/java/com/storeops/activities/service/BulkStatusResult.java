package com.storeops.activities.service;

import java.util.List;

/** Result of a bulk status update: counts plus per-item results in request order. */
public record BulkStatusResult(
        String batchId,
        int requested,
        int updated,
        int unchanged,
        int failed,
        List<BulkItemResult> results) {

    public BulkStatusResult {
        results = List.copyOf(results);
    }
}
