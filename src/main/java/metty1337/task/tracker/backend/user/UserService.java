package metty1337.task.tracker.backend.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository users;

    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(String subject) {
        User user = requireCurrentUser(subject);
        return new UserResponse(user.getId(), user.getEmail());
    }

    @Transactional(readOnly = true)
    public User requireCurrentUser(String subject) {
        long userId;
        try {
            userId = Long.parseLong(subject);
        } catch (NumberFormatException exception) {
            throw new CurrentUserUnavailableException();
        }
        if (userId <= 0) {
            throw new CurrentUserUnavailableException();
        }
        return users.findById(userId).orElseThrow(CurrentUserUnavailableException::new);
    }
}
