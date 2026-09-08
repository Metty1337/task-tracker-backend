package metty1337.task.tracker.backend.task;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping(value = "/tasks", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class TaskController {
    private final TaskService tasks;

    @GetMapping
    public List<TaskResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return tasks.list(jwt.getSubject());
    }

    @GetMapping("/{id}")
    public TaskResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return tasks.get(jwt.getSubject(), id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TaskResponse> create(@AuthenticationPrincipal Jwt jwt,
                                               @Valid @RequestBody CreateTaskRequest request) {
        TaskResponse task = tasks.create(jwt.getSubject(), request);
        return ResponseEntity.created(URI.create("/tasks/" + task.id())).body(task);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TaskResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                               @Valid @RequestBody UpdateTaskRequest request) {
        return tasks.update(jwt.getSubject(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        tasks.delete(jwt.getSubject(), id);
    }
}
