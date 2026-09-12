package metty1337.task.tracker.backend.exception;

import metty1337.task.tracker.backend.entity.Task;

public class TaskNotFoundException extends RuntimeException {
    public TaskNotFoundException() {
        super("Task not found");
    }
}
