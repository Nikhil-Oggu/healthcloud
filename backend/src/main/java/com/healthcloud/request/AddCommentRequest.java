package com.healthcloud.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload to add a comment to a service request. The tenant, request, and author are taken from the
 * caller's context and the path; only the body comes from the client.
 */
public record AddCommentRequest(
        @NotBlank @Size(max = 2000) String body) {
}
