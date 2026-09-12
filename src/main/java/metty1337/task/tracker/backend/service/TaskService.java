package metty1337.task.tracker.backend.service;

import metty1337.task.tracker.backend.mapper.TaskMapper;

import metty1337.task.tracker.backend.dto.TaskResponse;

import metty1337.task.tracker.backend.repository.TaskRepository;

import metty1337.task.tracker.backend.entity.Task;

import metty1337.task.tracker.backend.dto.CreateTaskRequest;

import metty1337.task.tracker.backend.exception.TaskNotFoundException;

import metty1337.task.tracker.backend.dto.UpdateTaskRequest;

import lombok.RequiredArgsConstructor;
import metty1337.task.tracker.backend.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskService {
    private final TaskRepository tasks;
    private final UserService currentUsers;
    private final TaskMapper mapper;

    @Transactional(readOnly = true)
    public List<TaskResponse> getTasks(String subject) {
        User owner = currentUsers.requireCurrentUser(subject);
        return mapper.toResponses(tasks.findAllByOwnerIdOrderByIdAsc(owner.getId()));
    }

    @Transactional
    public TaskResponse create(String subject, CreateTaskRequest request) {
        User owner = currentUsers.requireCurrentUser(subject);
        Task task = mapper.toEntity(request, owner);
        return mapper.toResponse(tasks.save(task));
    }

    @Transactional
    public void update(Long id, String subject, UpdateTaskRequest request) {
        Long ownerId = currentUsers.getCurrentUserId(subject);
        if (tasks.update(ownerId, id, request) == 0) {
            throw new TaskNotFoundException();
        }
    }

    @Transactional
    public void delete(Long id, String subject) {
        Long ownerId = currentUsers.getCurrentUserId(subject);
        if (tasks.delete(ownerId, id) == 0) {
            throw new TaskNotFoundException();
        }
    }
}
