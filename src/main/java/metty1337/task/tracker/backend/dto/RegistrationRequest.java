package metty1337.task.tracker.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public record RegistrationRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 128) String password) {
    public RegistrationRequest {
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }
}
