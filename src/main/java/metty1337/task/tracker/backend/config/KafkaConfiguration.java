package metty1337.task.tracker.backend.config;

import metty1337.task.tracker.backend.email.EmailPublisher;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfiguration {
    @Bean
    public NewTopic emailSendingTasksTopic() {
        return TopicBuilder.name(EmailPublisher.TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
