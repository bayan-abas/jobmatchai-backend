package com.jobmatchai.backend.config;

import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.security.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;

// @EnableWebSecurity is what makes Spring Boot treat this class's SecurityFilterChain as the
// application's real web security configuration; without it (and without any other
// UserDetailsService/AuthenticationManager/AuthenticationProvider bean in the context - see
// userDetailsService()/authenticationManager() below), Spring Boot's
// UserDetailsServiceAutoConfiguration falls back to generating its own single in-memory user
// with a random logged password and wires the default AuthenticationManager to it - which is
// exactly the "Using generated security password" / "inMemoryUserDetailsManager" log output
// this class exists to prevent, and which causes every real request (including /api/auth/login,
// even though it's permitAll() below) to be evaluated against the wrong, empty user store.
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Value("${app.cors.allowed-origin}")
    private String allowedOrigin;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        (request, response, authException) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required")
                ))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/*/test").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/users/register", "/api/users/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                                "/api/auth/forgot-password", "/api/auth/reset-password").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/payments/webhook").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/jobs/all", "/api/jobs/{id}").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/external-jobs/all", "/api/external-jobs/{id}").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // Backs the framework's AuthenticationManager with the real users table instead of Spring
    // Boot's auto-generated single in-memory user. The app's own /api/auth/login flow
    // (AuthService) checks credentials manually against UserRepository + BCrypt and never calls
    // through this bean directly - JwtAuthenticationFilter is what actually authenticates every
    // other request, straight from the JWT's signed claims, with no UserDetailsService lookup
    // needed. This bean's job is purely to give Spring Security a real UserDetailsService so it
    // stops substituting the fake one (and to have one wired correctly for any future code -
    // e.g. an @PreAuthorize check or a manager-based login path - that does need it).
    @Bean
    public UserDetailsService userDetailsService(UserRepository userRepository) {
        return email -> {
            User user = userRepository.findByEmail(email);
            if (user == null) {
                throw new UsernameNotFoundException("No user found for email: " + email);
            }

            String role = user.getRole() != null ? user.getRole().toUpperCase() : "CANDIDATE";

            return org.springframework.security.core.userdetails.User
                    .withUsername(user.getEmail())
                    .password(user.getPassword())
                    .authorities("ROLE_" + role)
                    .build();
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // Standard Spring Security pattern: this assembles the real AuthenticationManager from
    // whatever UserDetailsService/PasswordEncoder beans exist in the context (the ones defined
    // above), rather than letting Spring Boot autoconfigure one against the generated in-memory
    // user.
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        // APP_CORS_ALLOWED_ORIGIN accepts a comma-separated list, not just one origin - a
        // self-hosted deployment commonly needs to allow both the real production frontend
        // domain and a local dev frontend (http://localhost:5173) against the same backend.
        List<String> origins = List.of(allowedOrigin.split(","))
                .stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
