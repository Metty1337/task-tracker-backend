package metty1337.task.tracker.backend.repository;

import metty1337.task.tracker.backend.entity.Task;

import metty1337.task.tracker.backend.dto.UpdateTaskRequest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {
    List<Task> findAllByOwnerIdOrderByIdAsc(Long ownerId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Task task
            set task.title = :#{#payload.title()},
                task.description = :#{#payload.description()},
                task.completedAt = case
                    when :#{#payload.completed()} = true
                        then coalesce(task.completedAt, current_timestamp)
                    else null
                end,
                task.status = :#{#payload.status()}
            where task.owner.id = :ownerId and task.id = :taskId
            """)
    int update(@Param("ownerId") Long ownerId, @Param("taskId") Long taskId,
               @Param("payload") UpdateTaskRequest payload);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Task task where task.owner.id = :ownerId and task.id = :taskId")
    int delete(@Param("ownerId") Long ownerId, @Param("taskId") Long taskId);
}
