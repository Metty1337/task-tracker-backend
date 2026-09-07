package metty1337.task.tracker.backend.email;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import metty1337.task.tracker.backend.email.EmailOutbox.EmailRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailPublisher {
    public static final String TOPIC = "EMAIL_SENDING_TASKS";
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, EmailRequest> kafka;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    @Scheduled(fixedDelayString = "${app.email.publish-delay-ms}")
    public void publishPending() {
        try {
            transactions.executeWithoutResult(transaction -> {
                var pending = jdbc.query("SELECT id, payload FROM email_outbox ORDER BY id LIMIT 20 FOR UPDATE SKIP LOCKED",
                        (row, index) -> new PendingEmail(row.getLong("id"), row.getString("payload")));
                for (var email : pending) {
                    try {
                        EmailRequest request = mapper.readValue(email.payload(), EmailRequest.class);
                        kafka.send(TOPIC, Long.toString(email.id()), request).get(15, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Email publication interrupted", exception);
                    } catch (Exception exception) {
                        throw new IllegalStateException("Email publication failed", exception);
                    }
                    jdbc.update("DELETE FROM email_outbox WHERE id = ?", email.id());
                }
            });
        } catch (RuntimeException exception) {
            log.warn("Email publication failed; pending requests will be retried", exception);
        }
    }

    private record PendingEmail(long id, String payload) {
    }
}
