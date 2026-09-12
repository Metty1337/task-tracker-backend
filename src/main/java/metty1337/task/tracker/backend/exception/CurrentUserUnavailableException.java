package metty1337.task.tracker.backend.exception;

public class CurrentUserUnavailableException extends RuntimeException {
    public CurrentUserUnavailableException() {
        super("Authentication required");
    }
}
