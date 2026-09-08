package metty1337.task.tracker.backend.task;

import java.time.Instant;

public record TaskResponse(Long id, String title, String description, TaskStatus status, Instant completedAt) {
    static TaskResponse from(Task task) {
        return new TaskResponse(task.getId(), task.getTitle(), task.getDescription(),
                task.getStatus(), task.getCompletedAt());
    }
}
