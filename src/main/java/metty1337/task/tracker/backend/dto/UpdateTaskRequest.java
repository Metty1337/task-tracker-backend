package metty1337.task.tracker.backend.dto;

import metty1337.task.tracker.backend.entity.TaskStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateTaskRequest(
        @NotBlank @Size(max = 255) String title,
        String description,
        @NotNull TaskStatus status) {
    public UpdateTaskRequest {
        title = title == null ? null : title.strip();
    }

    public boolean completed() {
        return status == TaskStatus.COMPLETED;
    }
}
