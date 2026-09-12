package metty1337.task.tracker.backend.service;

import metty1337.task.tracker.backend.dto.UserResponse;

import metty1337.task.tracker.backend.entity.User;

import metty1337.task.tracker.backend.repository.UserRepository;

import metty1337.task.tracker.backend.exception.CurrentUserUnavailableException;

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
        Long userId = getCurrentUserId(subject);
        return users.findById(userId).orElseThrow(CurrentUserUnavailableException::new);
    }

    public Long getCurrentUserId(String subject) {
        long userId;
        try {
            userId = Long.parseLong(subject);
        } catch (NumberFormatException exception) {
            throw new CurrentUserUnavailableException();
        }
        if (userId <= 0) {
            throw new CurrentUserUnavailableException();
        }
        return userId;
    }
}
