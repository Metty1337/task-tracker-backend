package metty1337.task.tracker.backend.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateTaskRequest(@NotBlank @Size(max = 255) String title, String description,
                                @NotNull TaskStatus status) {
}
