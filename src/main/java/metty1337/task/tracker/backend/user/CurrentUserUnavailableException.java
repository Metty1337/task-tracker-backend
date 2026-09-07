package metty1337.task.tracker.backend.user;

public class CurrentUserUnavailableException extends RuntimeException {
    public CurrentUserUnavailableException() {
        super("Authentication required");
    }
}
