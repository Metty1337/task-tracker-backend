package metty1337.task.tracker.backend.service;

import metty1337.task.tracker.backend.dto.RegistrationRequest;

import metty1337.task.tracker.backend.entity.User;

import metty1337.task.tracker.backend.repository.UserRepository;

import metty1337.task.tracker.backend.exception.DuplicateEmailException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTests {
    @Mock
    UserRepository users;
    @Mock
    PasswordEncoder passwords;
    @Mock
    TokenService tokens;
    @Mock
    EmailOutbox emails;
    RegistrationService registrations;
    final RegistrationRequest request = new RegistrationRequest("alice@example.com", "password-123");

    @BeforeEach
    void setUp() {
        registrations = new RegistrationService(users, passwords, tokens, emails);
    }

    @Test
    void savesHashAndEnqueuesEmailBeforeIssuingToken() {
        User saved = mock(User.class);
        when(saved.getId()).thenReturn(42L);
        when(saved.getEmail()).thenReturn(request.email());
        when(passwords.encode(request.password())).thenReturn("encoded-password");
        when(users.saveAndFlush(any())).thenReturn(saved);
        when(tokens.issue(42L)).thenReturn("token");
        assertThat(registrations.register(request)).isEqualTo("token");
        verify(users).saveAndFlush(argThat(user -> user.getEmail().equals(request.email())
                && user.getPasswordHash().equals("encoded-password")));
        var order = inOrder(emails, tokens);
        order.verify(emails).enqueue(request.email());
        order.verify(tokens).issue(42L);
    }

    @Test
    void duplicateStopsBeforePasswordHashingOrEmail() {
        when(users.existsByEmail(request.email())).thenReturn(true);
        assertThatThrownBy(() -> registrations.register(request)).isInstanceOf(DuplicateEmailException.class);
        verifyNoInteractions(passwords, emails, tokens);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void concurrentDuplicateMapsOnlyTheEmailConstraintToConflict() {
        var violation = new org.hibernate.exception.ConstraintViolationException("duplicate",
                new java.sql.SQLException("duplicate", "23505"), "uk_app_users_email");
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate", violation));
        assertThatThrownBy(() -> registrations.register(request)).isInstanceOf(DuplicateEmailException.class);
        verifyNoInteractions(emails, tokens);
    }

    @Test
    void unrelatedDatabaseFailureIsNotReportedAsDuplicate() {
        var failure = new DataIntegrityViolationException("other constraint");
        when(users.saveAndFlush(any())).thenThrow(failure);
        assertThatThrownBy(() -> registrations.register(request)).isSameAs(failure);
        verifyNoInteractions(emails, tokens);
    }
}
