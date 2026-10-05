package com.storeops.activities.routes;

import com.storeops.activities.service.BulkStatusCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Body of {@code PATCH /api/activities/bulk-status}: 1 to 50 items and an optional note. */
public record BulkStatusRequest(
        @NotEmpty @Size(max = BulkStatusRequest.MAX_ITEMS) List<@Valid BulkStatusItemRequest> items,
        @Size(max = 500) String note) {

    static final int MAX_ITEMS = 50;

    public BulkStatusRequest {
        items = items == null ? null : List.copyOf(items);
    }

    BulkStatusCommand toCommand() {
        return new BulkStatusCommand(items.stream().map(BulkStatusItemRequest::toCommand).toList(), note);
    }
}
