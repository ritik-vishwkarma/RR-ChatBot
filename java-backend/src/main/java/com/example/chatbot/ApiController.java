package com.example.chatbot;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.web.csrf.CsrfToken;

@RestController
@RequestMapping("/api/v1")
class ApiController {
    private static final Logger log = LoggerFactory.getLogger(ApiController.class);
    record Cred(
        @NotBlank 
        @Email 
        @Size(max = 254) 
        String email, 
        @NotBlank 
        @Size(max = 72) 
        String password
    ) {}
    record Msg(
        @NotBlank 
        @Size(max = 8000) 
        String content) {}
    record UserData(String id, String email) {}

    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final UserRepo users; 
    private final ChatRepo chats; 
    private final MessageRepo msgs;
    private final AiWebhook ai; 
    private final RateLimiter limiter; 
    private final AuthenticationManager am;
    private final PasswordEncoder enc; 
    private final JwtService jwt; 
    private final RefreshTokenService refreshTokens;
    private final boolean cookieSecure;

    ApiController(
        UserRepo users, 
        ChatRepo chats, 
        MessageRepo msgs, 
        AiWebhook ai, 
        RateLimiter limiter,
        AuthenticationManager am, 
        PasswordEncoder enc, 
        JwtService jwt, 
        RefreshTokenService refreshTokens,
        @Value("${app.cookies.secure:false}") boolean cookieSecure
    ) {
        this.users = users; 
        this.chats = chats; 
        this.msgs = msgs; 
        this.ai = ai; 
        this.limiter = limiter;
        this.am = am; 
        this.enc = enc; 
        this.jwt = jwt; 
        this.refreshTokens = refreshTokens; 
        this.cookieSecure = cookieSecure;
    }

    private static String owner(Authentication a) { 
        return "local:" + a.getName()
                            .trim()
                            .toLowerCase(Locale.ROOT); 
    }

    private static ResponseStatusException http(HttpStatus s) { 
        return new ResponseStatusException(s); 
    }

    private static String normalizeEmail(String email) { 
        return email.trim().toLowerCase(Locale.ROOT); 
    }

    /** Public, safe endpoint used by the vanilla-JS client to bootstrap a missing CSRF cookie. */
    @GetMapping("/csrf")
    ResponseEntity<ApiResponse<Map<String, String>>> csrf(CsrfToken csrfToken, HttpServletResponse response) {
        // SecurityConfig's SPA CSRF handler materializes XSRF-TOKEN before this method runs.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return ResponseEntity.ok(
            ApiResponse.ok(
                "CSRF token initialized", 
                Map.of("token", csrfToken.getToken())
            )
        );
    }

    @PostMapping("/auth/register")
    ResponseEntity<ApiResponse<UserData>> register(
        @Valid 
        @RequestBody Cred c, 
        HttpServletRequest rq
    ) {
        if (!limiter.allow("reg:" + rq.getRemoteAddr(), 5, 3_600_000)) {     
            throw http(HttpStatus.TOO_MANY_REQUESTS);
        }
        String email = normalizeEmail(c.email());
        String password = c.password();

        int passwordLength = password.codePointCount(0, password.length());
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;

        if (passwordLength < 12 || passwordBytes > 72) {
            throw http(HttpStatus.BAD_REQUEST);
        }

        if (users.existsByEmail(email)) {
            throw http(HttpStatus.CONFLICT);
        }

        try {
            Domain.UserAccount user = new Domain.UserAccount(
                                        email, 
                                        enc.encode(password)
                                    );

            user = users.saveAndFlush(user);

            UserData userData = new UserData(
                                    user.id.toString(),
                                    user.email
                                );

            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(
                        ApiResponse.ok(
                            "Account created", userData
                        )
                    );

        } catch (DataIntegrityViolationException e) { 
            throw http(HttpStatus.CONFLICT); 
        }
    }

    @PostMapping("/auth/login")
    ResponseEntity<ApiResponse<Map<String, String>>> login(
        @Valid 
        @RequestBody Cred c, 
        HttpServletRequest rq, 
        HttpServletResponse rs
    ) {
        String email = normalizeEmail(c.email());
        String password = c.password();

        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw http(HttpStatus.UNAUTHORIZED);
        }

        if (!limiter.allow("login:" + rq.getRemoteAddr() + ":" + email, 10, 600_000)){
            throw http(HttpStatus.TOO_MANY_REQUESTS);
        }

        Authentication auth;
        try { 
            auth = am.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, c.password())); 
        }

        catch (AuthenticationException e) { 
            throw http(HttpStatus.UNAUTHORIZED); 
        }

        JwtService.Issued access = jwt.issue(auth.getName());
        RefreshTokenService.Issued refresh = refreshTokens.create(auth.getName());

        setAccessCookie(rs, access.token(), access.expiresIn());
        setRefreshCookie(rs, refresh.rawToken(), refresh.expiresAt());

        return ResponseEntity.ok(
            ApiResponse.ok(
                "Login successful", 
                Map.of("email", auth.getName())
            )
        );
    }

    @PostMapping("/auth/refresh")
    ResponseEntity<ApiResponse<Map<String, String>>> refresh(
        @CookieValue(
            name = "REFRESH_TOKEN", 
            required = false
        ) String raw, 
        HttpServletResponse rs
    ) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(raw);
        if (!rotation.valid()) { 
            clearAuthCookies(rs); 
            throw http(HttpStatus.UNAUTHORIZED); 
        }

        JwtService.Issued access = jwt.issue(rotation.email());

        setAccessCookie(rs, access.token(), access.expiresIn());
        setRefreshCookie(rs, rotation.rawToken(), rotation.expiresAt());

        return ResponseEntity.ok(
            ApiResponse.ok(
                "Token refreshed", 
                Map.of("authenticated", "true")
            )
        );
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(
        @CookieValue(
            name = "REFRESH_TOKEN", 
            required = false
        ) String raw, 
        HttpServletResponse rs
    ) {
        refreshTokens.revoke(raw); 
        
        clearAuthCookies(rs); 
        
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    ApiResponse<Map<String, String>> me(Authentication a) {
        String email = a.getName();

        return ApiResponse.ok(
            "Current user retrieved", 
            Map.of(
                "name", email.substring(0, email.indexOf('@') < 0 ? email.length() : email.indexOf('@')), 
                "email", email
            )
        );
    }

    private void setAccessCookie(
        HttpServletResponse rs, 
        String raw, 
        long maxAgeSeconds
    ) {
        ResponseCookie cookie = ResponseCookie
                                .from("ACCESS_TOKEN", raw)
                                .httpOnly(true)
                                .secure(cookieSecure)
                                .sameSite("Lax")
                                .path("/api/v1")
                                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                                .build();

        rs.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void setRefreshCookie(
        HttpServletResponse rs, 
        String raw, 
        Instant expiry
    ) {
        long maxAge = Math.max(
            0, 
            Duration.between(Instant.now(), expiry).getSeconds()
        );

        ResponseCookie cookie = ResponseCookie
                                .from("REFRESH_TOKEN", raw)
                                .httpOnly(true)
                                .secure(cookieSecure)
                                .sameSite("Lax")
                                .path("/api/v1/auth")
                                .maxAge(Duration.ofSeconds(maxAge))
                                .build();

        rs.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
    
    private void clearAuthCookies(HttpServletResponse rs) {
        ResponseCookie accessCookie = ResponseCookie
                                        .from("ACCESS_TOKEN", "")
                                        .httpOnly(true)
                                        .secure(cookieSecure)
                                        .sameSite("Lax")
                                        .path("/api/v1")
                                        .maxAge(Duration.ZERO)
                                        .build();

        ResponseCookie refreshCookie = ResponseCookie
                                        .from("REFRESH_TOKEN", "")
                                        .httpOnly(true)
                                        .secure(cookieSecure)
                                        .sameSite("Lax")
                                        .path("/api/v1/auth")
                                        .maxAge(Duration.ZERO)
                                        .build();

        rs.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());
        rs.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
    }

    @GetMapping("/chats")
    ApiResponse<List<Map<String, String>>> list(Authentication a) {
        return ApiResponse.ok(
            "Chats retrieved", 
            chats.findByOwnerOrderByCreatedAtDesc(owner(a)).stream().map(c -> Map.of("id", c.id.toString(), "title", c.title)).toList());
    }

    @PostMapping("/chats")
    ResponseEntity<ApiResponse<Map<String, String>>> create(Authentication a) {
        Domain.Chat c = chats.save(new Domain.Chat(owner(a)));
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.ok(
                    "Chat created", 
                    Map.of("id", c.id.toString(), "title", c.title)
                )
            );
    }

    @GetMapping("/chats/{id}/messages")
    ApiResponse<List<Map<String, String>>> messages(
        @PathVariable UUID id, 
        Authentication a
    ) {
        chats.findByIdAndOwner(
            id, 
            owner(a)).orElseThrow(() -> http(HttpStatus.NOT_FOUND)
        );

        return ApiResponse.ok(
            "Messages retrieved", 
            msgs.findByChatIdOrderByIdAsc(id).stream().map(m -> Map.of("role", m.role, "content", m.content)).toList()
        );
    }

    @PostMapping("/chats/{id}/messages")
    ApiResponse<Map<String, String>> send(
        @PathVariable UUID id, 
        @Valid
        @RequestBody Msg m, 
        Authentication a
    ) {
        String o = owner(a);

        // if (!limiter.allow("msg:" + o, 20, 60_000)){
        //     throw http(HttpStatus.TOO_MANY_REQUESTS);
        // }
        if (!limiter.allow("day:" + o, 50, 86_400_000) || !limiter.allow("global:ai", 500, 86_400_000)) {
            throw http(HttpStatus.TOO_MANY_REQUESTS);
        }

        Domain.Chat c = chats.findByIdAndOwner(id, o).orElseThrow(() -> http(HttpStatus.NOT_FOUND));

        if (!inFlight.add(id)){
            throw http(HttpStatus.CONFLICT);
        }
        
        try {
            List<Domain.Message> last = new ArrayList<>(msgs.findTop20ByChatIdOrderByIdDesc(id));
            Collections.reverse(last);

            List<Map<String, String>> history = new ArrayList<>(last.stream().map(x -> Map.of("role", x.role, "content", x.content)).toList());
            history.add(Map.of("role", "user", "content", m.content()));

            String reply;

            try { 
                reply = ai.ask(history); 
            } catch (Exception e) { 
                log.warn("AI webhook failed: {}", e.toString()); 
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI service unavailable"); 
            }

            msgs.saveAll(
                List.of(
                    new Domain.Message(id, "user", m.content()), 
                    new Domain.Message(id, "assistant", reply)
                )
            );

            if ("New chat".equals(c.title)) { 
                c.title = m.content().substring(0, Math.min(32, m.content().length())); 
                chats.save(c); 
            }

            return ApiResponse.ok(
                "Reply generated", 
                Map.of("role", "assistant", "content", reply)
            );
        } finally { 
            inFlight.remove(id); 
        }
    }
}
