package metty1337.task.tracker.backend.test;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProtectedTestController {
    @GetMapping("/test/protected")
    public TestResponse protectedResource(@AuthenticationPrincipal Jwt jwt) {
        return new TestResponse("Authentication successful", jwt.getSubject());
    }

    public record TestResponse(String message, String userId) {
    }
}
