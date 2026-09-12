package metty1337.task.tracker.backend.exception;

public class DuplicateEmailException extends RuntimeException {
    public DuplicateEmailException() {
        super("Email is already registered");
    }
}
