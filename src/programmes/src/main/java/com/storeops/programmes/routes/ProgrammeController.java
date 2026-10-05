package com.storeops.programmes.routes;

import com.storeops.programmes.model.Project;
import com.storeops.programmes.service.ProgrammeService;
import com.storeops.staff.api.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** HTTP routes for store programmes. HTTP concerns and validation only; logic lives in the service. */
@RestController
@RequestMapping("/api/programmes")
public class ProgrammeController {

    private final ProgrammeService service;

    public ProgrammeController(ProgrammeService service) {
        this.service = service;
    }

    @GetMapping
    public List<Project> list(AuthenticatedUser caller) {
        return service.listForStore(caller);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Project create(AuthenticatedUser caller, @Valid @RequestBody CreateProgrammeRequest request) {
        return service.create(caller, request.name(), request.description());
    }

    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public Project addMember(AuthenticatedUser caller, @PathVariable("id") String id,
            @Valid @RequestBody AddMemberRequest request) {
        return service.addMember(caller, id, request.staffId(), request.role());
    }
}
