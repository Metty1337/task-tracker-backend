package metty1337.task.tracker.backend.email;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class EmailOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public record EmailRequest(String recipient, String subject, String body) {
    }

    public void enqueue(String email) {
        String payload = mapper.writeValueAsString(new EmailRequest(email,
                "Welcome to Task Tracker", "Your Task Tracker account has been created."));
        jdbc.update("INSERT INTO email_outbox (payload) VALUES (?)", payload);
    }
}
