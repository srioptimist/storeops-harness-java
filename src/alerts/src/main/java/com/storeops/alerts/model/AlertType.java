package com.storeops.alerts.model;

/** Operational reason an alert was raised. */
public enum AlertType {
    INVENTORY,
    SLA_BREACH,
    SHIFT_HANDOVER,
    ESCALATION
}
