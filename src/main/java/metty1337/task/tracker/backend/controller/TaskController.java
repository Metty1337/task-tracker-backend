package metty1337.task.tracker.backend.controller;

import metty1337.task.tracker.backend.dto.TaskResponse;

import metty1337.task.tracker.backend.service.TaskService;

import metty1337.task.tracker.backend.dto.CreateTaskRequest;

import metty1337.task.tracker.backend.dto.UpdateTaskRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskController {
    private final TaskService taskService;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<TaskResponse> getTaskService(@AuthenticationPrincipal Jwt jwt) {
        return taskService.getTasks(jwt.getSubject());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TaskResponse> create(@AuthenticationPrincipal Jwt jwt,
                                               @Valid @RequestBody CreateTaskRequest request) {
        TaskResponse task = taskService.create(jwt.getSubject(), request);
        return ResponseEntity.created(URI.create("/tasks/" + task.id())).body(task);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> update(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody UpdateTaskRequest request) {
        taskService.update(id, jwt.getSubject(), request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        taskService.delete(id, jwt.getSubject());
        return ResponseEntity.noContent().build();
    }
}
