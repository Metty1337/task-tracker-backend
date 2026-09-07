package metty1337.task.tracker.backend.user;

import lombok.RequiredArgsConstructor;
import metty1337.task.tracker.backend.email.EmailOutbox;
import metty1337.task.tracker.backend.security.TokenService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RegistrationService {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final EmailOutbox emails;

    @Transactional
    public String register(RegistrationRequest request) {
        if (users.existsByEmail(request.email())) {
            throw new DuplicateEmailException();
        }
        User user;
        try {
            user = users.saveAndFlush(new User(request.email(), passwordEncoder.encode(request.password())));
        } catch (DataIntegrityViolationException exception) {
            // The database constraint also handles concurrent registrations of the same email.
            Throwable cause = exception;
            while (cause != null) {
                if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                        && "uk_app_users_email".equals(violation.getConstraintName())) {
                    throw new DuplicateEmailException();
                }
                cause = cause.getCause();
            }
            throw exception;
        }
        emails.enqueue(user.getEmail());
        return tokens.issue(user.getId());
    }
}
