package com.healthcloud.request;

/** Lifecycle of a request assignment. Exactly one ACTIVE per request; older ones are SUPERSEDED. */
public enum RequestAssignmentStatus {
    ACTIVE,
    SUPERSEDED
}
