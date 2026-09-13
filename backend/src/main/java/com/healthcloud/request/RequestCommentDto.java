package com.healthcloud.request;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Client-facing view of one comment on a request. */
public record RequestCommentDto(
        UUID id,
        UUID authorUserId,
        String body,
        OffsetDateTime createdAt) {

    public static RequestCommentDto from(RequestComment c) {
        return new RequestCommentDto(c.getId(), c.getAuthorUserId(), c.getBody(), c.getCreatedAt());
    }
}
