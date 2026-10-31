package com.dayo.careround.realtimeservice.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtVerificationService jwtVerificationService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        try {
            Claims claims = jwtVerificationService.verifyToken(token);

            UUID userId = UUID.fromString(claims.getSubject());
            String role = claims.get("role", String.class);
            String grade = claims.get("grade", String.class);
            String wardIdStr = claims.get("assignedWardId", String.class);
            UUID assignedWardId = wardIdStr != null ? UUID.fromString(wardIdStr) : null;

            @SuppressWarnings("unchecked")
            List<String> permissionClaims = claims.get("permissions", List.class);

            CareRoundPrincipal principal = new CareRoundPrincipal(userId, role, grade, assignedWardId);

            List<GrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            if (grade != null) {
                authorities.add(new SimpleGrantedAuthority("GRADE_" + grade));
            }
            if (permissionClaims != null) {
                permissionClaims.forEach(p -> authorities.add(new SimpleGrantedAuthority("PERM_" + p)));
            }

            JwtAuthenticationToken authToken = new JwtAuthenticationToken(principal, authorities);
            SecurityContextHolder.getContext().setAuthentication(authToken);

        } catch (Exception e) {
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }
}