package metty1337.task.tracker.backend.controller;

import metty1337.task.tracker.backend.dto.TaskResponse;

import metty1337.task.tracker.backend.service.TaskService;

import metty1337.task.tracker.backend.dto.CreateTaskRequest;

import metty1337.task.tracker.backend.entity.TaskStatus;

import metty1337.task.tracker.backend.exception.TaskNotFoundException;

import metty1337.task.tracker.backend.dto.UpdateTaskRequest;

import metty1337.task.tracker.backend.config.SecurityConfiguration;
import metty1337.task.tracker.backend.service.TokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TaskController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = "app.jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class TaskControllerTests {
    @Autowired
    MockMvc mvc;
    @Autowired
    JwtEncoder encoder;
    @MockitoBean
    TaskService tasks;

    @Test
    void returnsCurrentUsersTasksAsJsonArray() throws Exception {
        Instant completedAt = Instant.parse("2026-09-12T10:00:00Z");
        when(tasks.getTasks("7")).thenReturn(List.of(
                new TaskResponse(1L, "Todo", null, TaskStatus.TODO, null),
                new TaskResponse(2L, "Done", "Description", TaskStatus.COMPLETED, completedAt)));

        mvc.perform(get("/tasks").header("Authorization", authorization(7L)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].title").value("Todo"))
                .andExpect(jsonPath("$[0].status").value("TODO"))
                .andExpect(jsonPath("$[0].completedAt").isEmpty())
                .andExpect(jsonPath("$[1].description").value("Description"))
                .andExpect(jsonPath("$[1].status").value("COMPLETED"))
                .andExpect(jsonPath("$[1].completedAt").value("2026-09-12T10:00:00Z"));
        verify(tasks).getTasks("7");
    }

    @Test
    void rejectsTasksWithoutAuthentication() throws Exception {
        mvc.perform(get("/tasks"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.message").value("Authentication required"));
        verifyNoInteractions(tasks);
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        Instant now = Instant.now();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().issuer(TokenService.ISSUER).subject("7")
                        .issuedAt(now.minusSeconds(7200)).expiresAt(now.minusSeconds(3600)).build())).getTokenValue();

        mvc.perform(get("/tasks").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication required"));
        verifyNoInteractions(tasks);
    }

    @Test
    void createsTaskAndReturnsLocation() throws Exception {
        when(tasks.create("7", new CreateTaskRequest("New task", "Description")))
                .thenReturn(new TaskResponse(15L, "New task", "Description", TaskStatus.TODO, null));

        mvc.perform(post("/tasks").header("Authorization", authorization(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":" New task ","description":"Description"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/tasks/15"))
                .andExpect(jsonPath("$.id").value(15))
                .andExpect(jsonPath("$.title").value("New task"))
                .andExpect(jsonPath("$.status").value("TODO"));
        verify(tasks).create("7", new CreateTaskRequest("New task", "Description"));
    }

    @Test
    void updatesWholeTaskState() throws Exception {
        UpdateTaskRequest request = new UpdateTaskRequest("Updated", null, TaskStatus.COMPLETED);

        mvc.perform(put("/tasks/15").header("Authorization", authorization(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Updated","description":null,"status":"COMPLETED"}
                                """))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(tasks).update(15L, "7", request);
    }

    @Test
    void deletesTask() throws Exception {
        mvc.perform(delete("/tasks/15").header("Authorization", authorization(7L)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(tasks).delete(15L, "7");
    }

    @Test
    void returnsNotFoundWhenTaskCannotBeDeleted() throws Exception {
        doThrow(new TaskNotFoundException()).when(tasks).delete(99L, "7");

        mvc.perform(delete("/tasks/99").header("Authorization", authorization(7L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Task not found"));
    }

    @Test
    void returnsNotFoundWithoutDisclosingTaskOwnership() throws Exception {
        doThrow(new TaskNotFoundException()).when(tasks).update(eq(99L), eq("7"), any());

        mvc.perform(put("/tasks/99").header("Authorization", authorization(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Updated","status":"TODO"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Task not found"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{",
            "null",
            "{\"title\":\"   \"}",
            "{\"title\":null}"
    })
    void rejectsInvalidCreateBody(String body) throws Exception {
        mvc.perform(post("/tasks").header("Authorization", authorization(7L))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid request fields or body"));
        verifyNoInteractions(tasks);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "null",
            "{\"title\":\"Title\"}",
            "{\"title\":\"   \",\"status\":\"TODO\"}",
            "{\"title\":\"Title\",\"status\":null}"
    })
    void rejectsInvalidUpdateBody(String body) throws Exception {
        mvc.perform(put("/tasks/15").header("Authorization", authorization(7L))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid request fields or body"));
        verifyNoInteractions(tasks);
    }

    private String authorization(Long userId) {
        return "Bearer " + new TokenService(encoder, Duration.ofHours(1)).issue(userId);
    }
}
