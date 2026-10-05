package com.storeops.programmes.routes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/programmes}. */
public record CreateProgrammeRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 2000) String description) {
}
