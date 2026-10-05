package com.storeops.activities.service;

import java.util.List;

/** Input for a bulk status update: items in request order plus an optional handover note. */
public record BulkStatusCommand(List<BulkStatusItem> items, String note) {

    public BulkStatusCommand {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
