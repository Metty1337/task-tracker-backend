package metty1337.task.tracker.backend.controller;

import metty1337.task.tracker.backend.service.LoginService;

import metty1337.task.tracker.backend.dto.LoginRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AuthController {
    private final LoginService logins;

    @PostMapping(value = "/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + logins.login(request))
                .build();
    }
}
