package metty1337.task.tracker.backend.user;

import metty1337.task.tracker.backend.security.SecurityConfiguration;
import metty1337.task.tracker.backend.security.TokenService;
import metty1337.task.tracker.backend.test.ProtectedTestController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
@Import({SecurityConfiguration.class, ProtectedTestController.class, UserService.class})
@TestPropertySource(properties = "app.jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class UserControllerTests {
    @Autowired
    MockMvc mvc;
    @Autowired
    JwtEncoder encoder;
    @MockitoBean
    RegistrationService registrations;
    @MockitoBean
    UserRepository users;

    @Test
    void returnsOnlyCurrentUserIdAndEmail() throws Exception {
        User user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(user.getEmail()).thenReturn("my@email.com");
        when(users.findById(1L)).thenReturn(Optional.of(user));
        String token = new TokenService(encoder, Duration.ofHours(1)).issue(1L);
        mvc.perform(get("/user").param("id", "2").param("email", "other@email.com")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.email").value("my@email.com"));
        verify(users).findById(1L);
        verifyNoMoreInteractions(users);
    }

    @Test
    void rejectsCurrentUserWithoutAuthentication() throws Exception {
        mvc.perform(get("/user"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.message").value("Authentication required"));
        verifyNoInteractions(users);
    }

    @Test
    void rejectsExpiredCurrentUserToken() throws Exception {
        mvc.perform(get("/user").header("Authorization", "Bearer " + signedToken("1", Instant.now().minusSeconds(3600))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication required"));
        verifyNoInteractions(users);
    }

    @Test
    void rejectsInvalidCurrentUserToken() throws Exception {
        mvc.perform(get("/user").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication required"));
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"abc", "0", "-1", "9223372036854775808"})
    void rejectsInvalidCurrentUserSubject(String subject) throws Exception {
        mvc.perform(get("/user").header("Authorization", "Bearer " + signedToken(subject, Instant.now().plusSeconds(3600))))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.message").value("Authentication required"));
        verifyNoInteractions(users);
    }

    @Test
    void rejectsUserMissingFromDatabase() throws Exception {
        when(users.findById(1L)).thenReturn(Optional.empty());
        mvc.perform(get("/user").header("Authorization", "Bearer " + signedToken("1", Instant.now().plusSeconds(3600))))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    private String signedToken(String subject, Instant expiresAt) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer(TokenService.ISSUER)
                .issuedAt(Instant.now().minusSeconds(7200)).expiresAt(expiresAt);
        if (subject != null) {
            claims.subject(subject);
        }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                claims.build())).getTokenValue();
    }

    @Test
    void acceptsJsonAndReturnsTokenHeader() throws Exception {
        when(registrations.register(any())).thenReturn("jwt-token");
        mvc.perform(post("/user").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":" Alice@Example.com ","password":"password-123"}
                                """))
                .andExpect(status().isOk()).andExpect(header().string("Authorization", "Bearer jwt-token"))
                .andExpect(content().string(""));
        verify(registrations).register(new RegistrationRequest("alice@example.com", "password-123"));
    }

    @Test
    void acceptsFormAndReturnsTokenHeader() throws Exception {
        when(registrations.register(any())).thenReturn("jwt-token");
        mvc.perform(post("/user").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", " Alice@Example.com ").param("password", "password-123"))
                .andExpect(status().isOk()).andExpect(header().string("Authorization", "Bearer jwt-token"));
        verify(registrations).register(new RegistrationRequest("alice@example.com", "password-123"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{", "null", "{\"email\":\"bad\",\"password\":\"password-123\"}",
            "{\"email\":\"alice@example.com\",\"password\":\"short\"}",
            "{\"email\":\"alice@example.com\",\"password\":\"        \"}"})
    void rejectsInvalidJson(String body) throws Exception {
        mvc.perform(post("/user").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
        verifyNoInteractions(registrations);
    }

    @Test
    void rejectsInvalidForm() throws Exception {
        mvc.perform(post("/user").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("email", "bad"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
        verifyNoInteractions(registrations);
    }

    @Test
    void rejectsUnsupportedContentType() throws Exception {
        mvc.perform(post("/user").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.message").isString());
    }

    @Test
    void returnsConflictForDuplicate() throws Exception {
        when(registrations.register(any())).thenThrow(new DuplicateEmailException());
        mvc.perform(post("/user").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"alice@example.com","password":"password-123"}
                                """))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Email is already registered"))
                .andExpect(header().doesNotExist("Authorization"));
    }

    @Test
    void rejectsMissingAuthentication() throws Exception {
        mvc.perform(get("/test/protected"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").isString());
    }

    @Test
    void acceptsIssuedToken() throws Exception {
        String token = new TokenService(encoder, Duration.ofHours(1)).issue(1L);
        mvc.perform(get("/test/protected").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Authentication successful"))
                .andExpect(jsonPath("$.userId").value("1"));
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        Instant now = Instant.now();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().issuer(TokenService.ISSUER).subject("1")
                        .issuedAt(now.minusSeconds(7200)).expiresAt(now.minusSeconds(3600)).build())).getTokenValue();
        mvc.perform(get("/test/protected").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").isString());
    }

    @Test
    void rejectsInvalidToken() throws Exception {
        mvc.perform(get("/test/protected").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").isString());
    }
}
