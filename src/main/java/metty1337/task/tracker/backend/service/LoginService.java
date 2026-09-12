package metty1337.task.tracker.backend.service;

import metty1337.task.tracker.backend.exception.InvalidCredentialsException;

import metty1337.task.tracker.backend.dto.LoginRequest;
import metty1337.task.tracker.backend.entity.User;
import metty1337.task.tracker.backend.repository.UserRepository;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class LoginService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final String dummyPasswordHash;

    public LoginService(UserRepository users, PasswordEncoder passwords, TokenService tokens) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.dummyPasswordHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional(readOnly = true)
    public String login(LoginRequest request) {
        User user = users.findByEmail(request.email()).orElse(null);
        // Perform password hashing even for an unknown email to reduce timing differences.
        boolean matches = passwords.matches(request.password(),
                user == null ? dummyPasswordHash : user.getPasswordHash());
        if (user == null || !matches) {
            throw new InvalidCredentialsException();
        }
        return tokens.issue(user.getId());
    }
}
