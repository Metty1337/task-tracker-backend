package metty1337.task.tracker.backend.task;

import jakarta.persistence.EntityManager;
import metty1337.task.tracker.backend.user.User;
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

    private Task reload(Task task) {
        entityManager.flush();
        Long id = task.getId();
        entityManager.clear();
        return entityManager.find(Task.class, id);
    }
}
