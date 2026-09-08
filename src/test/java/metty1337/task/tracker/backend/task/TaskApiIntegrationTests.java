package metty1337.task.tracker.backend.task;

import metty1337.task.tracker.backend.email.EmailOutbox.EmailRequest;
import metty1337.task.tracker.backend.security.TokenService;
import metty1337.task.tracker.backend.user.User;
import metty1337.task.tracker.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "app.jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.email.scheduling-enabled=false",
        "spring.kafka.admin.auto-create=false"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class TaskApiIntegrationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    TaskRepository tasks;
    @Autowired
    UserRepository users;
    @Autowired
    TokenService tokens;
    @MockitoBean
    KafkaTemplate<String, EmailRequest> kafka;

    @BeforeEach
    void cleanDatabase() {
        tasks.deleteAll();
        users.deleteAll();
    }

    @Test
    void persistsTaskLifecycleAndPreventsAccessByAnotherUser() throws Exception {
        User alice = users.saveAndFlush(new User("alice@example.com", "hash"));
        User bob = users.saveAndFlush(new User("bob@example.com", "hash"));
        String aliceToken = "Bearer " + tokens.issue(alice.getId());
        String bobToken = "Bearer " + tokens.issue(bob.getId());
        String location = mvc.perform(post("/tasks").header("Authorization", aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"title":"First","description":"Details"}
                                """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("TODO"))
                .andReturn().getResponse().getHeader("Location");
        Long id = tasks.findAll().getFirst().getId();
        assertThat(location).isEqualTo("/tasks/" + id);
        tasks.saveAndFlush(new Task("Bob's task", null, bob));
        mvc.perform(get("/tasks").header("Authorization", aliceToken).param("ownerId", bob.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/tasks").header("Authorization", bobToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Bob's task"));

        for (String path : new String[]{location, "/tasks/9223372036854775807"}) {
            for (HttpMethod method : new HttpMethod[]{HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE}) {
                mvc.perform(request(method, path).header("Authorization", bobToken)
                                .contentType(MediaType.APPLICATION_JSON).content("""
                                        {"title":"Stolen","status":"COMPLETED"}
                                        """))
                        .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Task not found"));
            }
        }
        assertThat(tasks.findById(id).orElseThrow().getTitle()).isEqualTo("First");
        String completed = """
                {"title":"Done","description":null,"status":"COMPLETED"}
                """;
        mvc.perform(put(location).header("Authorization", aliceToken).contentType(MediaType.APPLICATION_JSON)
                        .content(completed))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completedAt").isString());
        Task saved = tasks.findById(id).orElseThrow();
        assertThat(saved.getTitle()).isEqualTo("Done");
        assertThat(saved.getDescription()).isNull();
        assertThat(saved.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(saved.getCompletedAt()).isNotNull();
        mvc.perform(put(location).header("Authorization", aliceToken).contentType(MediaType.APPLICATION_JSON)
                        .content(completed)).andExpect(status().isOk());
        assertThat(tasks.findById(id).orElseThrow().getCompletedAt()).isEqualTo(saved.getCompletedAt());
        mvc.perform(put(location).header("Authorization", aliceToken).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Again","status":"TODO"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completedAt").isEmpty());
        assertThat(tasks.findById(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(tasks.findById(id).orElseThrow().getCompletedAt()).isNull();
        mvc.perform(get(location).header("Authorization", aliceToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Again"));
        mvc.perform(delete(location).header("Authorization", aliceToken))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(tasks.findById(id)).isEmpty();
        mvc.perform(delete(location).header("Authorization", aliceToken)).andExpect(status().isNotFound());
        mvc.perform(get("/tasks").header("Authorization", aliceToken))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        assertThat(tasks.count()).isEqualTo(1);
    }

    @Test
    void listsTasksInAscendingIdOrder() throws Exception {
        User owner = users.saveAndFlush(new User("owner@example.com", "hash"));
        Task first = tasks.saveAndFlush(new Task("First", null, owner));
        Task second = tasks.saveAndFlush(new Task("Second", null, owner));
        mvc.perform(get("/tasks").header("Authorization", "Bearer " + tokens.issue(owner.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(first.getId()))
                .andExpect(jsonPath("$[1].id").value(second.getId()));
    }
}
