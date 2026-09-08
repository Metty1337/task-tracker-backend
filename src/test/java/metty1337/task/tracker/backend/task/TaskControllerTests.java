package metty1337.task.tracker.backend.task;

import metty1337.task.tracker.backend.security.SecurityConfiguration;
import metty1337.task.tracker.backend.security.TokenService;
import metty1337.task.tracker.backend.user.User;
import metty1337.task.tracker.backend.user.UserRepository;
import metty1337.task.tracker.backend.user.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TaskController.class)
@Import({SecurityConfiguration.class, TaskService.class, UserService.class})
@TestPropertySource(properties = "app.jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class TaskControllerTests {
    @Autowired
    MockMvc mvc;
    @Autowired
    JwtEncoder encoder;
    @MockitoBean
    TaskRepository tasks;
    @MockitoBean
    UserRepository users;

    @Test
    void listsOnlyAuthenticatedOwnersTasksAndReturnsArray() throws Exception {
        User owner = owner();
        when(tasks.findAllByOwnerIdOrderByIdAsc(1L)).thenReturn(List.of(new Task("Mine", null, owner)));
        mvc.perform(get("/tasks").param("ownerId", "2").header("Authorization", token()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Mine"))
                .andExpect(jsonPath("$[0].status").value("TODO"))
                .andExpect(jsonPath("$[0].completedAt").isEmpty())
                .andExpect(jsonPath("$[0].owner").doesNotExist());
        verify(tasks).findAllByOwnerIdOrderByIdAsc(1L);
        verifyNoMoreInteractions(tasks);
    }

    @Test
    void returnsEmptyArrayForOwnerWithoutTasks() throws Exception {
        owner();
        mvc.perform(get("/tasks").header("Authorization", token()))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void createsTodoTaskWithAuthenticatedOwner() throws Exception {
        User owner = owner();
        when(tasks.save(any(Task.class))).thenAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            assertThat(task.getOwner()).isSameAs(owner);
            assertThat(task.getTitle()).isEqualTo("New task");
            assertThat(task.getDescription()).isEqualTo("Details");
            Task saved = mock(Task.class);
            when(saved.getId()).thenReturn(7L);
            when(saved.getTitle()).thenReturn(task.getTitle());
            when(saved.getDescription()).thenReturn(task.getDescription());
            when(saved.getStatus()).thenReturn(task.getStatus());
            return saved;
        });
        mvc.perform(post("/tasks").header("Authorization", token()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"New task","description":"Details","ownerId":2,"status":"COMPLETED"}
                                """))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/tasks/7"))
                .andExpect(jsonPath("$.id").value(7)).andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.completedAt").isEmpty());
    }

    @Test
    void readsUpdatesCompletesAndReopensOwnedTask() throws Exception {
        Task task = new Task("Original", "Description", owner());
        when(tasks.findByIdAndOwnerId(7L, 1L)).thenReturn(Optional.of(task));
        mvc.perform(get("/tasks/7").header("Authorization", token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Original"));
        String completed = """
                {"title":"Changed","description":null,"status":"COMPLETED"}
                """;
        mvc.perform(put("/tasks/7").header("Authorization", token()).contentType(MediaType.APPLICATION_JSON)
                        .content(completed))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Changed"))
                .andExpect(jsonPath("$.description").isEmpty())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isString());
        Instant firstCompletion = task.getCompletedAt();
        mvc.perform(put("/tasks/7").header("Authorization", token()).contentType(MediaType.APPLICATION_JSON)
                        .content(completed)).andExpect(status().isOk());
        assertThat(task.getCompletedAt()).isEqualTo(firstCompletion);
        mvc.perform(put("/tasks/7").header("Authorization", token()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Reopened","status":"TODO"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.completedAt").isEmpty());
        assertThat(task.getCompletedAt()).isNull();
    }

    @Test
    void deletesOwnedTaskWithoutResponseBody() throws Exception {
        Task task = new Task("Mine", null, owner());
        when(tasks.findByIdAndOwnerId(7L, 1L)).thenReturn(Optional.of(task));
        mvc.perform(delete("/tasks/7").header("Authorization", token()))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(tasks).delete(task);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE"})
    void hidesMissingOrOtherOwnersTask(String method) throws Exception {
        owner();
        mvc.perform(request(HttpMethod.valueOf(method), "/tasks/7").header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"title":"Attempt","status":"COMPLETED"}
                                """))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Task not found"));
        verify(tasks).findByIdAndOwnerId(7L, 1L);
        verifyNoMoreInteractions(tasks);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{", "null", "{\"title\":\"\"}", "{\"title\":\"   \"}"})
    void rejectsInvalidCreateBody(String body) throws Exception {
        mvc.perform(post("/tasks").header("Authorization", token()).contentType(MediaType.APPLICATION_JSON)
                        .content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
        verifyNoInteractions(tasks, users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{", "null", "{\"title\":\"Valid\"}",
            "{\"title\":\"Valid\",\"status\":null}", "{\"title\":\"Valid\",\"status\":\"UNKNOWN\"}",
            "{\"title\":\" \",\"status\":\"TODO\"}"})
    void rejectsInvalidUpdateBody(String body) throws Exception {
        mvc.perform(put("/tasks/7").header("Authorization", token()).contentType(MediaType.APPLICATION_JSON)
                        .content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
        verifyNoInteractions(tasks, users);
    }

    @Test
    void rejectsOversizedTitleAndUnsupportedContentType() throws Exception {
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT)) {
            String path = method == HttpMethod.POST ? "/tasks" : "/tasks/7";
            mvc.perform(request(method, path).header("Authorization", token()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"" + "x".repeat(256) + "\",\"status\":\"TODO\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
            mvc.perform(request(method, path).header("Authorization", token()).contentType(MediaType.TEXT_PLAIN)
                            .content("text"))
                    .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.message").isString());
        }
        verifyNoInteractions(tasks, users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "DELETE"})
    void rejectsMissingExpiredAndInvalidAuthentication(String method) throws Exception {
        String path = method.equals("PUT") || method.equals("DELETE") ? "/tasks/7" : "/tasks";
        for (String authorization : List.of("", signedToken("1", Instant.now().minusSeconds(3600)), "Bearer invalid")) {
            var request = request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"Valid\",\"status\":\"TODO\"}");
            if (!authorization.isEmpty()) {
                request.header("Authorization", authorization);
            }
            mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.message").value("Authentication required"));
        }
        verifyNoInteractions(tasks, users);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"abc", "0", "-1", "9223372036854775808"})
    void rejectsInvalidSubject(String subject) throws Exception {
        mvc.perform(get("/tasks").header("Authorization", signedToken(subject, Instant.now().plusSeconds(3600))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").isString());
        verifyNoInteractions(tasks, users);
    }

    @Test
    void rejectsDeletedUser() throws Exception {
        mvc.perform(get("/tasks").header("Authorization", token()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").isString());
        verifyNoInteractions(tasks);
    }

    @Test
    void rejectsMalformedTaskId() throws Exception {
        mvc.perform(get("/tasks/not-a-number").header("Authorization", token()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
        verifyNoInteractions(tasks, users);
    }

    private User owner() {
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(1L);
        when(users.findById(1L)).thenReturn(Optional.of(owner));
        return owner;
    }

    private String token() {
        return signedToken("1", Instant.now().plusSeconds(3600));
    }

    private String signedToken(String subject, Instant expiresAt) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer(TokenService.ISSUER)
                .issuedAt(Instant.now().minusSeconds(7200)).expiresAt(expiresAt);
        if (subject != null) {
            claims.subject(subject);
        }
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }
}
