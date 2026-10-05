package com.storeops.activities.routes;

import com.storeops.activities.model.ActivityAuditEntry;
import com.storeops.activities.model.Task;
import com.storeops.activities.model.TaskStatus;
import com.storeops.activities.service.ActivityService;
import com.storeops.activities.service.BulkStatusResult;
import com.storeops.staff.api.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP routes for operational activities. HTTP concerns and validation only; logic lives in
 * {@link ActivityService}. {@code GET /api/tasks} is kept as an alias of the list endpoint for
 * the baseline smoke check.
 */
@RestController
public class ActivityController {

    private static final String BASE = "/api/activities";

    private final ActivityService service;

    public ActivityController(ActivityService service) {
        this.service = service;
    }

    @GetMapping({BASE, "/api/tasks"})
    public List<Task> list(@RequestParam(name = "programmeId", required = false) String programmeId,
            @RequestParam(name = "status", required = false) TaskStatus status) {
        return service.list(programmeId, status);
    }

    @PostMapping(BASE)
    @ResponseStatus(HttpStatus.CREATED)
    public Task create(AuthenticatedUser caller, @Valid @RequestBody CreateActivityRequest request) {
        return service.create(caller, request.toCommand());
    }

    @GetMapping(BASE + "/{id}")
    public Task get(@PathVariable("id") String id) {
        return service.get(id);
    }

    @GetMapping(BASE + "/{id}/audit")
    public List<ActivityAuditEntry> audit(AuthenticatedUser caller, @PathVariable("id") String id) {
        return service.auditTrail(caller, id);
    }

    @PatchMapping(BASE + "/bulk-status")
    public BulkStatusResult bulkStatus(AuthenticatedUser caller, @Valid @RequestBody BulkStatusRequest request) {
        return service.bulkUpdateStatus(caller, request.toCommand());
    }

    @PatchMapping(BASE + "/{id}")
    public Task update(AuthenticatedUser caller, @PathVariable("id") String id,
            @Valid @RequestBody UpdateActivityRequest request) {
        return service.update(caller, id, request.toCommand());
    }

    @DeleteMapping(BASE + "/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(AuthenticatedUser caller, @PathVariable("id") String id) {
        service.delete(caller, id);
    }
}
