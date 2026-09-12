package metty1337.task.tracker.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTaskRequest(
        @NotBlank @Size(max = 255) String title,
        String description) {
    public CreateTaskRequest {
        title = title == null ? null : title.strip();
    }
}
