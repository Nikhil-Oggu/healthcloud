package com.healthcloud.request;

/** Request priority (source-of-truth §14.5 "priority and due date"). Stored as a string. */
public enum ServiceRequestPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT
}
