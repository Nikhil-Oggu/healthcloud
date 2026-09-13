package com.healthcloud.request;

import java.util.UUID;

/** A candidate assignee for a request — a same-tenant provider or claims reviewer (minimum-necessary). */
public record AssignableUserDto(
        UUID userId,
        String fullName,
        String role) {
}
