package metty1337.task.tracker.backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.email.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class EmailSchedulingConfiguration {
}
