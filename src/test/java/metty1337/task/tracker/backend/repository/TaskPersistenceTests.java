package metty1337.task.tracker.backend.repository;

import metty1337.task.tracker.backend.entity.Task;

import metty1337.task.tracker.backend.entity.TaskStatus;

import metty1337.task.tracker.backend.dto.UpdateTaskRequest;

import jakarta.persistence.EntityManager;
import metty1337.task.tracker.backend.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Testcontainers(disabledWithoutDocker = true)
class TaskPersistenceTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    EntityManager entityManager;
    @Autowired
    TaskRepository tasks;

    @Test
    void persistsMultipleTasksForOwner() {
        User owner = new User("owner@example.com", "hash");
        entityManager.persist(owner);
        String description = "Long description ".repeat(100);
        Task task = new Task("First task", description, owner);
        entityManager.persist(task);
        entityManager.persist(new Task("Second task", null, owner));
        Long ownerId = owner.getId();
        task = reload(task);
        assertThat(task.getId()).isNotNull();
        assertThat(task.getTitle()).isEqualTo("First task");
        assertThat(task.getDescription()).isEqualTo(description);
        assertThat(task.getOwner().getId()).isEqualTo(ownerId);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(task.getCompletedAt()).isNull();
        assertThat(entityManager.createQuery("select count(t) from Task t where t.owner.id = :ownerId", Long.class)
                .setParameter("ownerId", ownerId).getSingleResult()).isEqualTo(2L);
    }

    @Test
    void rejectsNonexistentOwner() {
        assertThatThrownBy(() -> entityManager.createNativeQuery(
                "INSERT INTO tasks (title, owner_id) VALUES ('Orphan', -1)").executeUpdate())
                .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
    }

    @Test
    void rejectsCompletionWithoutTimestamp() {
        User owner = new User("owner@example.com", "hash");
        entityManager.persist(owner);
        assertThatThrownBy(() -> entityManager.createNativeQuery(
                        "INSERT INTO tasks (title, owner_id, status) VALUES ('Invalid', :ownerId, 'COMPLETED')")
                .setParameter("ownerId", owner.getId()).executeUpdate())
                .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
    }

    @Test
    void updatesOnlyOwnedTaskAndMaintainsCompletionTimestamp() {
        User owner = new User("owner@example.com", "hash");
        User anotherUser = new User("another@example.com", "hash");
        entityManager.persist(owner);
        entityManager.persist(anotherUser);
        Task task = new Task("Old title", "Old description", owner);
        entityManager.persist(task);
        entityManager.flush();

        assertThat(tasks.update(anotherUser.getId(), task.getId(),
                new UpdateTaskRequest("Forbidden", null, TaskStatus.COMPLETED))).isZero();
        assertThat(tasks.update(owner.getId(), task.getId(),
                new UpdateTaskRequest("Completed", "Updated description", TaskStatus.COMPLETED))).isOne();

        task = reload(task);
        assertThat(task.getTitle()).isEqualTo("Completed");
        assertThat(task.getDescription()).isEqualTo("Updated description");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getCompletedAt()).isNotNull();
        var completedAt = task.getCompletedAt();

        assertThat(tasks.update(owner.getId(), task.getId(),
                new UpdateTaskRequest("Edited", null, TaskStatus.COMPLETED))).isOne();
        task = reload(task);
        assertThat(task.getCompletedAt()).isEqualTo(completedAt);

        assertThat(tasks.update(owner.getId(), task.getId(),
                new UpdateTaskRequest("Reopened", null, TaskStatus.TODO))).isOne();
        task = reload(task);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(task.getCompletedAt()).isNull();
    }

    @Test
    void deletesOnlyOwnedTask() {
        User owner = new User("owner@example.com", "hash");
        User anotherUser = new User("another@example.com", "hash");
        entityManager.persist(owner);
        entityManager.persist(anotherUser);
        Task task = new Task("Title", null, owner);
        entityManager.persist(task);
        entityManager.flush();

        assertThat(tasks.delete(anotherUser.getId(), task.getId())).isZero();
        assertThat(tasks.delete(owner.getId(), task.getId())).isOne();
        entityManager.clear();
        assertThat(entityManager.find(Task.class, task.getId())).isNull();
    }

    private Task reload(Task task) {
        entityManager.flush();
        Long id = task.getId();
        entityManager.clear();
        return entityManager.find(Task.class, id);
    }
}
