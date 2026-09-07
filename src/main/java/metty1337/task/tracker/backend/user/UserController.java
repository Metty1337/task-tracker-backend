package metty1337.task.tracker.backend.user;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class UserController {
    private final RegistrationService registrations;

    @PostMapping(value = "/user", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> registerJson(@Valid @RequestBody RegistrationRequest request) {
        return register(request);
    }

    @PostMapping(value = "/user", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> registerForm(@Valid @ModelAttribute RegistrationRequest request) {
        return register(request);
    }

    private ResponseEntity<Void> register(RegistrationRequest request) {
        return ResponseEntity.ok()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + registrations.register(request))
                .build();
    }
}
