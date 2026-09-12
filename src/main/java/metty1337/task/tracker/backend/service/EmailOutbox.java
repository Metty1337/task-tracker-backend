package metty1337.task.tracker.backend.service;

import metty1337.task.tracker.backend.entity.Task;

import lombok.RequiredArgsConstructor;
import metty1337.task.tracker.backend.dto.EmailRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class EmailOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public void enqueue(String email) {
        String payload = mapper.writeValueAsString(new EmailRequest(email,
                "Welcome to Task Tracker", "Your Task Tracker account has been created."));
        jdbc.update("INSERT INTO email_outbox (payload) VALUES (?)", payload);
    }
}
