package com.storeops.alerts.routes;

import com.storeops.alerts.model.Notification;
import com.storeops.alerts.service.AlertService;
import com.storeops.staff.api.AuthenticatedUser;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP routes for the caller's alerts. */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService service;

    public AlertController(AlertService service) {
        this.service = service;
    }

    @GetMapping
    public List<Notification> list(AuthenticatedUser caller) {
        return service.listForUser(caller);
    }
}
