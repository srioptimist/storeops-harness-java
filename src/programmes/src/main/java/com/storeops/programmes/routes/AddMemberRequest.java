package com.storeops.programmes.routes;

import com.storeops.programmes.model.ProjectRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Body of {@code POST /api/programmes/{id}/members}. */
public record AddMemberRequest(@NotBlank String staffId, @NotNull ProjectRole role) {
}
