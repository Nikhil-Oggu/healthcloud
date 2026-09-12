package com.healthcloud.auth;

import java.util.List;
import java.util.UUID;

/** The authenticated user's context: who they are, which organization, and their roles. */
public record CurrentUserDto(
        UUID userId,
        String email,
        String fullName,
        UUID organizationId,
        String organizationName,
        List<String> roles) {
}
