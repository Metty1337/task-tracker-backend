package metty1337.task.tracker.backend.controller;

import metty1337.task.tracker.backend.config.SecurityConfiguration;
import metty1337.task.tracker.backend.entity.User;
import metty1337.task.tracker.backend.repository.UserRepository;
import metty1337.task.tracker.backend.service.LoginService;
import metty1337.task.tracker.backend.service.TokenService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({SecurityConfiguration.class, LoginService.class, TokenService.class, ProtectedTestController.class})
@TestPropertySource(properties = {
        "app.jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.jwt.ttl=PT1H"
})
class AuthControllerTests {
    @Autowired
    MockMvc mvc;
    @Autowired
    PasswordEncoder passwords;
    @Autowired
    JwtDecoder decoder;
    @MockitoBean
    UserRepository users;

    @Test
    void authenticatesNormalizedEmailAndReturnsUsableJwt() throws Exception {
        User user = mock(User.class);
        when(user.getId()).thenReturn(42L);
        when(user.getPasswordHash()).thenReturn(passwords.encode(" password-123 "));
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        String authorization = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":" Alice@Example.com ","password":" password-123 "}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Authorization", org.hamcrest.Matchers.startsWith("Bearer ")))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().string(""))
                .andReturn().getResponse().getHeader("Authorization");
        var jwt = decoder.decode(authorization.substring(7));
        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(TokenService.ISSUER);
        assertThat(jwt.getExpiresAt()).isAfter(Instant.now());
        assertThat(jwt.getExpiresAt()).isEqualTo(jwt.getIssuedAt().plusSeconds(3600));
        mvc.perform(get("/test/protected").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value("42"));
        verify(users).findByEmail("alice@example.com");
        verifyNoMoreInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsWrongPasswordAndUnknownEmailWithSameError(boolean exists) throws Exception {
        if (exists) {
            when(users.findByEmail("alice@example.com"))
                    .thenReturn(Optional.of(new User("alice@example.com", passwords.encode("correct-password"))));
        }
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"alice@example.com","password":"wrong"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("Invalid email or password"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().doesNotExist("Authorization"));
        verify(users).findByEmail("alice@example.com");
        verifyNoMoreInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{", "null",
            "{\"email\":\"bad\",\"password\":\"password-123\"}",
            "{\"email\":\"alice@example.com\"}",
            "{\"email\":\"alice@example.com\",\"password\":null}",
            "{\"email\":\"alice@example.com\",\"password\":\"   \"}"})
    void rejectsInvalidBody(String body) throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid request fields or body"))
                .andExpect(header().doesNotExist("Authorization"));
        verifyNoInteractions(users);
    }

    @Test
    void rejectsOversizedPassword() throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"" + "a".repeat(129) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isString());
        verifyNoInteractions(users);
    }

    @Test
    void rejectsUnsupportedContentType() throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").isString());
        verifyNoInteractions(users);
    }

    @Test
    void hidesInternalFailureDetails() throws Exception {
        when(users.findByEmail("alice@example.com"))
                .thenThrow(new DataAccessResourceFailureException("private database details"));
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"alice@example.com","password":"password-123"}
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Internal server error"))
                .andExpect(header().doesNotExist("Authorization"));
    }
}
