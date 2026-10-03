package com.customertrace.web.dto;

import java.time.Instant;

/** Creator-to-restaurant assignment payloads. */
public final class AssignmentDtos {

    private AssignmentDtos() {
    }

    /** One assignment row, including who created it and when. */
    public record AssignmentResponse(
            Long id,
            Long restaurantId,
            Long creatorId,
            boolean active,
            Long assignedBy,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** Body for creating an assignment. */
    public record AssignRequest(Long restaurantId, Long creatorId) {
    }
}