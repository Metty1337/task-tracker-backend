package metty1337.task.tracker.backend.task;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import metty1337.task.tracker.backend.user.User;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "tasks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Task {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status = TaskStatus.TODO;

    @Column(name = "completed_at")
    private Instant completedAt;

    public Task(String title, String description, User owner) {
        this.title = Objects.requireNonNull(title);
        this.description = description;
        this.owner = Objects.requireNonNull(owner);
    }

    public void update(String title, String description, TaskStatus status) {
        this.title = Objects.requireNonNull(title);
        this.description = description;
        Objects.requireNonNull(status);
        if (this.status != status) {
            this.completedAt = status == TaskStatus.COMPLETED ? Instant.now() : null;
        }
        this.status = status;
    }
}
