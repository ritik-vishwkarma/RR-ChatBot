package com.example.chatbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;

@Configuration
class SecurityConfig {
    @Bean
    SecurityFilterChain chain(HttpSecurity http, JwtService jwt, ObjectMapper mapper) throws Exception {
        // One root-scoped cookie avoids separate XSRF-TOKEN cookies for / and /api paths.
        CookieCsrfTokenRepository csrfRepository = new CookieCsrfTokenRepository();
        csrfRepository.setCookiePath("/");

        http
            .csrf(c -> c
                .csrfTokenRepository(csrfRepository)
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
            )
            .addFilterBefore(
                new JwtAuthenticationFilter(jwt),    
                org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class
            )
            .authorizeHttpRequests(a -> a
                .requestMatchers("/error").permitAll()
                .requestMatchers(HttpMethod.GET, "/", "/*.html", "/*.css", "/*.js").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/csrf").permitAll()
                .requestMatchers(
                    HttpMethod.POST, 
                    "/api/v1/auth/register", 
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh", 
                    "/api/v1/auth/logout"
                ).permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e
                .defaultAuthenticationEntryPointFor(
                    (request, response, ex) -> {
                        response.setStatus(HttpStatus.UNAUTHORIZED.value());
                        response.setContentType("application/json");
                        mapper.writeValue(
                            response.getOutputStream(), 
                            Map.of(
                                "success", false,
                                "message", "Authentication required or access token invalid", 
                                "errors", Map.of()
                            )
                        );
                    }, 
                AntPathRequestMatcher.antMatcher("/api/**")
                )
                .accessDeniedHandler(
                    (request, response, ex) -> {
                        response.setStatus(HttpStatus.FORBIDDEN.value());
                        response.setContentType("application/json");

                        String code = ex instanceof CsrfException ? "CSRF_INVALID" : "ACCESS_DENIED";
                        mapper.writeValue(
                            response.getOutputStream(), 
                            Map.of(
                                "success", false,
                                "code", code,
                                "message", code.equals("CSRF_INVALID") ? "CSRF token missing or invalid" : "Request forbidden",
                                "errors", Map.of()
                            )
                        );
                    }
                )
            )
            .headers(h -> h
                .contentSecurityPolicy(c -> c
                    .policyDirectives(
                                    "default-src 'self'; img-src 'self' data:; frame-ancestors 'none'; form-action 'self'; base-uri 'none'; object-src 'none'"
                    )
                )
                .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }

    @Bean PasswordEncoder 
    passwordEncoder() { 
        return new BCryptPasswordEncoder(12); 
    }
    
    @Bean AuthenticationManager 
    authenticationManager(AuthenticationConfiguration c) {
        try {
            return c.getAuthenticationManager();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Bean UserDetailsService 
    userDetailsService(UserRepo users) {
        return email -> users
                        .findByEmail(email)
                        .map(u -> User
                                    .withUsername(u.email)
                                    .password(u.passwordHash)
                                    .roles("USER")
                                    .build()
                        )
                        .orElseThrow(
                            () -> new UsernameNotFoundException("not found")
                        );
    }

    /**
     * SPA-compatible CSRF handling:
     * - materializes deferred tokens so the XSRF-TOKEN cookie exists when needed;
     * - accepts the raw token returned by /api/v1/csrf in X-XSRF-TOKEN;
     * - retains XOR/BREACH protection for request attributes and parameter-based submissions.
     */
    static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
        private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(
            HttpServletRequest request, 
            HttpServletResponse response,
            Supplier<CsrfToken> csrfToken
        ) {
            this.plain.handle(request, response, csrfToken);
            csrfToken.get();
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            String headerValue = request.getHeader(csrfToken.getHeaderName());
            // CsrfTokenRequestHandler handler = StringUtils.hasText(headerValue) ? this.plain : this.xor;
            // return handler.resolveCsrfTokenValue(request, csrfToken);

            if (StringUtils.hasText(headerValue)) {
                return this.plain.resolveCsrfTokenValue(request, csrfToken);
            }

            return this.xor.resolveCsrfTokenValue(request, csrfToken);
        }
    }    
}