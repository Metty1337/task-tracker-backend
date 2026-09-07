package metty1337.task.tracker.backend;

import metty1337.task.tracker.backend.email.EmailOutbox;
import metty1337.task.tracker.backend.email.EmailOutbox.EmailRequest;
import metty1337.task.tracker.backend.email.EmailPublisher;
import metty1337.task.tracker.backend.user.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.email.scheduling-enabled=false",
        // Kafka is mocked in this suite; topic creation requires a real broker.
        "spring.kafka.admin.auto-create=false"
})
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
class TaskTrackerBackendApplicationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    RegistrationService registrations;
    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder passwords;
    @Autowired
    JwtDecoder decoder;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EmailPublisher publisher;
    @MockitoBean
    KafkaTemplate<String, EmailRequest> kafka;
    @MockitoSpyBean
    EmailOutbox emails;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM email_outbox");
        users.deleteAll();
    }

    @Test
    void returnsDatabaseUserForEachToken() throws Exception {
        String aliceToken = registrations.register(new RegistrationRequest("alice@example.com", "password-123"));
        String bobToken = registrations.register(new RegistrationRequest("bob@example.com", "password-456"));
        for (String token : new String[]{aliceToken, bobToken}) {
            long id = Long.parseLong(decoder.decode(token).getSubject());
            mvc.perform(get("/user").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$.id").value(id))
                    .andExpect(jsonPath("$.email").value(token.equals(aliceToken) ? "alice@example.com" : "bob@example.com"));
        }
    }

    @Test
    void registersWithHashedPasswordJwtAndDurableEmail() {
        String token = registrations.register(new RegistrationRequest(" Alice@Example.com ", "password-123"));
        var user = users.findAll().getFirst();
        assertThat(user.getEmail()).isEqualTo("alice@example.com");
        assertThat(user.getPasswordHash()).isNotEqualTo("password-123");
        assertThat(passwords.matches("password-123", user.getPasswordHash())).isTrue();
        assertThat(decoder.decode(token).getSubject()).isEqualTo(user.getId().toString());
        assertThat(jdbc.queryForObject("SELECT payload FROM email_outbox", String.class))
                .contains("\"recipient\":\"alice@example.com\"", "\"subject\":", "\"body\":")
                .doesNotContain("password-123");
        verifyNoInteractions(kafka);
    }

    @Test
    void duplicateDoesNotCreateAnotherUserOrEmail() {
        registrations.register(new RegistrationRequest("alice@example.com", "password-123"));
        assertThatThrownBy(() -> registrations.register(new RegistrationRequest("ALICE@example.com", "password-456")))
                .isInstanceOf(DuplicateEmailException.class);
        assertThat(users.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox", Long.class)).isEqualTo(1);
    }

    @Test
    void failedKafkaSendLeavesEmailForRetry() {
        registrations.register(new RegistrationRequest("alice@example.com", "password-123"));
        when(kafka.send(eq(EmailPublisher.TOPIC), anyString(), any(EmailRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        publisher.publishPending();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox", Long.class)).isEqualTo(1);
        when(kafka.send(eq(EmailPublisher.TOPIC), anyString(), any(EmailRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        publisher.publishPending();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox", Long.class)).isZero();
        verify(kafka, times(2)).send(eq(EmailPublisher.TOPIC), anyString(), eq(new EmailRequest(
                "alice@example.com", "Welcome to Task Tracker", "Your Task Tracker account has been created.")));
    }

    @Test
    void rollsBackUserIfEmailCannotBeEnqueued() {
        doThrow(new IllegalStateException("outbox unavailable")).when(emails).enqueue(anyString());
        assertThatThrownBy(() -> registrations.register(new RegistrationRequest("alice@example.com", "password-123")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(users.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox", Long.class)).isZero();
    }

    @Test
    void databaseConstraintRejectsDuplicateEmails() {
        registrations.register(new RegistrationRequest("alice@example.com", "password-123"));
        assertThatThrownBy(() -> users.saveAndFlush(new User("alice@example.com", "hash")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
