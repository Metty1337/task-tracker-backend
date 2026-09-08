package metty1337.task.tracker.backend.task;

import lombok.RequiredArgsConstructor;
import metty1337.task.tracker.backend.user.User;
import metty1337.task.tracker.backend.user.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskService {
    private final TaskRepository tasks;
    private final UserService currentUsers;

    @Transactional(readOnly = true)
    public List<TaskResponse> list(String subject) {
        User owner = currentUsers.requireCurrentUser(subject);
        return tasks.findAllByOwnerIdOrderByIdAsc(owner.getId()).stream().map(TaskResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public TaskResponse get(String subject, Long id) {
        return TaskResponse.from(ownedTask(subject, id));
    }

    @Transactional
    public TaskResponse create(String subject, CreateTaskRequest request) {
        User owner = currentUsers.requireCurrentUser(subject);
        return TaskResponse.from(tasks.save(new Task(request.title(), request.description(), owner)));
    }

    @Transactional
    public TaskResponse update(String subject, Long id, UpdateTaskRequest request) {
        Task task = ownedTask(subject, id);
        task.update(request.title(), request.description(), request.status());
        return TaskResponse.from(task);
    }

    @Transactional
    public void delete(String subject, Long id) {
        tasks.delete(ownedTask(subject, id));
    }

    private Task ownedTask(String subject, Long id) {
        User owner = currentUsers.requireCurrentUser(subject);
        return tasks.findByIdAndOwnerId(id, owner.getId()).orElseThrow(TaskNotFoundException::new);
    }
}
