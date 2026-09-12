package metty1337.task.tracker.backend.service;

import metty1337.task.tracker.backend.mapper.TaskMapper;

import metty1337.task.tracker.backend.dto.TaskResponse;

import metty1337.task.tracker.backend.repository.TaskRepository;

import metty1337.task.tracker.backend.entity.Task;

import metty1337.task.tracker.backend.dto.CreateTaskRequest;

import metty1337.task.tracker.backend.entity.TaskStatus;

import metty1337.task.tracker.backend.exception.TaskNotFoundException;

import metty1337.task.tracker.backend.dto.UpdateTaskRequest;

import metty1337.task.tracker.backend.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskServiceTests {
    @Mock
    TaskRepository tasks;
    @Mock
    UserService currentUsers;
    TaskService service;

    @BeforeEach
    void setUp() {
        service = new TaskService(tasks, currentUsers, Mappers.getMapper(TaskMapper.class));
    }

    @Test
    void returnsOnlyTasksBelongingToCurrentUser() {
        User owner = mockOwner(7L);
        Task first = mockTask(10L, "First", null, TaskStatus.TODO, null);
        Instant completedAt = Instant.parse("2026-09-12T10:00:00Z");
        Task second = mockTask(11L, "Second", "Description", TaskStatus.COMPLETED, completedAt);
        when(currentUsers.requireCurrentUser("7")).thenReturn(owner);
        when(tasks.findAllByOwnerIdOrderByIdAsc(7L)).thenReturn(List.of(first, second));

        assertThat(service.getTasks("7")).containsExactly(
                new TaskResponse(10L, "First", null, TaskStatus.TODO, null),
                new TaskResponse(11L, "Second", "Description", TaskStatus.COMPLETED, completedAt));
        verify(tasks).findAllByOwnerIdOrderByIdAsc(7L);
        verifyNoMoreInteractions(tasks);
    }

    @Test
    void createsTodoForCurrentUser() {
        User owner = mock(User.class);
        when(currentUsers.requireCurrentUser("7")).thenReturn(owner);
        when(tasks.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TaskResponse response = service.create("7", new CreateTaskRequest("New task", "Description"));

        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(tasks).save(taskCaptor.capture());
        Task task = taskCaptor.getValue();
        assertThat(task.getTitle()).isEqualTo("New task");
        assertThat(task.getDescription()).isEqualTo("Description");
        assertThat(task.getOwner()).isSameAs(owner);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(task.getCompletedAt()).isNull();
        assertThat(response.status()).isEqualTo(TaskStatus.TODO);
    }

    @Test
    void updatesTaskWithOneRepositoryCall() {
        UpdateTaskRequest payload = new UpdateTaskRequest("Done", "Updated", TaskStatus.COMPLETED);
        when(currentUsers.getCurrentUserId("7")).thenReturn(7L);
        when(tasks.update(7L, 10L, payload)).thenReturn(1);

        service.update(10L, "7", payload);

        verify(tasks).update(7L, 10L, payload);
        verifyNoMoreInteractions(tasks);
    }

    @Test
    void hidesWhetherAnotherUsersTaskExists() {
        UpdateTaskRequest payload = new UpdateTaskRequest("Title", null, TaskStatus.TODO);
        when(currentUsers.getCurrentUserId("7")).thenReturn(7L);
        when(tasks.update(7L, 99L, payload)).thenReturn(0);

        assertThatThrownBy(() -> service.update(99L, "7", payload))
                .isInstanceOf(TaskNotFoundException.class)
                .hasMessage("Task not found");
        verify(tasks).update(7L, 99L, payload);
        verifyNoMoreInteractions(tasks);
    }

    @Test
    void deletesOnlyOwnedTask() {
        when(currentUsers.getCurrentUserId("7")).thenReturn(7L);
        when(tasks.delete(7L, 10L)).thenReturn(1);

        service.delete(10L, "7");

        verify(tasks).delete(7L, 10L);
        verifyNoMoreInteractions(tasks);
    }

    @Test
    void returnsNotFoundWhenDeleteDoesNotAffectTask() {
        when(currentUsers.getCurrentUserId("7")).thenReturn(7L);
        when(tasks.delete(7L, 99L)).thenReturn(0);

        assertThatThrownBy(() -> service.delete(99L, "7"))
                .isInstanceOf(TaskNotFoundException.class)
                .hasMessage("Task not found");
        verify(tasks).delete(7L, 99L);
        verifyNoMoreInteractions(tasks);
    }

    private User mockOwner(Long id) {
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(id);
        return owner;
    }

    private Task mockTask(Long id, String title, String description, TaskStatus status, Instant completedAt) {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(id);
        when(task.getTitle()).thenReturn(title);
        when(task.getDescription()).thenReturn(description);
        when(task.getStatus()).thenReturn(status);
        when(task.getCompletedAt()).thenReturn(completedAt);
        return task;
    }
}
