package metty1337.task.tracker.backend.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {
    List<Task> findAllByOwnerIdOrderByIdAsc(Long ownerId);

    Optional<Task> findByIdAndOwnerId(Long id, Long ownerId);
}
