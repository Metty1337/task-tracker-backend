package metty1337.task.tracker.backend.email;

import metty1337.task.tracker.backend.email.EmailOutbox.EmailRequest;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class EmailSerializationTests {
    @Test
    void configuredSerializerWritesJsonObjectWithoutJavaTypeHeaders() throws Exception {
        Properties properties = new Properties();
        try (var input = getClass().getResourceAsStream("/application.properties")) {
            properties.load(input);
        }
        var serializerClass = Class.forName(properties.getProperty("spring.kafka.producer.value-serializer"));
        try (var serializer = (JacksonJsonSerializer<?>) serializerClass.getConstructor().newInstance()) {
            serializer.configure(Map.of(JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS,
                    properties.getProperty("spring.kafka.producer.properties.spring.json.add.type.headers")), false);
            assertJsonPayload(serializer);
        }
    }

    private <T> void assertJsonPayload(JacksonJsonSerializer<T> serializer) {
        var mapper = JsonMapper.builder().build();
        var request = new EmailRequest("alice@example.com", "Привет, \"Alice\"!", "Первая строка\nВторая строка");
        var headers = new RecordHeaders();
        // The configured serializer accepts application DTOs at runtime.
        @SuppressWarnings("unchecked")
        byte[] bytes = serializer.serialize(EmailPublisher.TOPIC, headers, (T) request);
        var json = mapper.readTree(bytes);
        assertThat(json.isObject()).isTrue();
        assertThat(json.size()).isEqualTo(3);
        assertThat(mapper.treeToValue(json, EmailRequest.class)).isEqualTo(request);
        assertThat(headers.toArray()).isEmpty();
    }
}
