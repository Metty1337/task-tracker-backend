package metty1337.task.tracker.backend.user;

public class DuplicateEmailException extends RuntimeException {
    public DuplicateEmailException() {
        super("Email is already registered");
    }
}
