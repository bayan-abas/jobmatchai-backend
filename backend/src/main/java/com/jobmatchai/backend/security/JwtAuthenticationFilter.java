package com.jobmatchai.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private JwtService jwtService;

    @Autowired
    private TokenRevocationService tokenRevocationService;

    // רץ פעם אחת על כל בקשה נכנסת - שולף את הטוקן מה-header, מוודא שהוא תקף ולא בוטל, ואם כן שם אותו ב-SecurityContext
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            if (jwtService.isValid(token)) {

                String email = jwtService.extractEmail(token);
                String role = jwtService.extractRole(token);
                boolean revoked = email != null
                        && tokenRevocationService.isRevoked(email, jwtService.extractIssuedAt(token));

                if (!revoked && email != null && role != null
                        && SecurityContextHolder.getContext().getAuthentication() == null) {
                    // hasRole(...) של Spring Security מצפה לפריפיקס ROLE_
                    String authority = "ROLE_" + role.toUpperCase();

                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            email,
                            null,
                            List.of(new SimpleGrantedAuthority(authority))
                    );

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}
