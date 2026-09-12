package metty1337.task.tracker.backend.dto;

import metty1337.task.tracker.backend.entity.TaskStatus;

import java.time.Instant;

public record TaskResponse(
        Long id,
        String title,
        String description,
        TaskStatus status,
        Instant completedAt) {
}
