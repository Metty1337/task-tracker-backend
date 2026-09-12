package metty1337.task.tracker.backend.dto;

public record EmailRequest(String recipient, String subject, String body) {
}
