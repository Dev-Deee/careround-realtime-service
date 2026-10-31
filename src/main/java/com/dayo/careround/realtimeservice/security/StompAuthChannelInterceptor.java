package com.dayo.careround.realtimeservice.security;

import com.dayo.careround.realtimeservice.services.AlertBroadcastService;
import com.dayo.careround.realtimeservice.services.SessionExpiryService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authenticates STOMP CONNECT with the JWT in the frame's Authorization header and authorises every
 * SUBSCRIBE. Deny by default: anything not explicitly allowed below is rejected with a STOMP ERROR
 * frame, after which the server closes the socket.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Pattern WARD_TOPIC = Pattern.compile("^/topic/wards/([^/]+)$");

    private final JwtVerificationService jwtVerificationService;
    private final SessionExpiryService sessionExpiryService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message; // heartbeat
        }

        StompCommand command = accessor.getCommand();
        switch (command) {
            case CONNECT, STOMP -> authenticate(accessor);
            case SUBSCRIBE -> authorizeSubscribe(accessor);
            case UNSUBSCRIBE, DISCONNECT -> { }
            case SEND -> throw new AccessDeniedException("Clients may not send messages");
            default -> throw new AccessDeniedException("STOMP command " + command + " is not allowed");
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new BadCredentialsException("Missing bearer token in the CONNECT Authorization header");
        }

        Claims claims;
        try {
            claims = jwtVerificationService.verifyToken(header.substring(7));
        } catch (JwtException | IllegalArgumentException e) {
            throw new BadCredentialsException("Invalid or expired token");
        }

        JwtAuthenticationToken authentication = toAuthentication(claims);
        accessor.setUser(authentication);
        sessionExpiryService.register(accessor.getSessionId(), claims.getExpiration().toInstant());
    }

    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        if (!(accessor.getUser() instanceof JwtAuthenticationToken authentication)) {
            throw new AccessDeniedException("Not authenticated");
        }
        if (sessionExpiryService.isExpired(accessor.getSessionId())) {
            throw new AccessDeniedException("Access token expired: refresh the token and reconnect");
        }

        CareRoundPrincipal principal = (CareRoundPrincipal) authentication.getPrincipal();
        String destination = accessor.getDestination();
        boolean admin = "ADMIN".equals(principal.role());

        if (AlertBroadcastService.ADMIN_ALERTS_TOPIC.equals(destination)) {
            if (admin) {
                log.info("Admin subscription: userId={} destination={}", principal.userId(), destination);
                return;
            }
            throw deny(principal, destination);
        }

        Matcher ward = destination == null ? null : WARD_TOPIC.matcher(destination);
        if (ward == null || !ward.matches()) {
            throw deny(principal, destination);
        }
        UUID wardId = parseCanonicalUuid(ward.group(1));
        if (wardId == null) {
            throw deny(principal, destination);
        }
        if (admin) {
            log.info("Admin subscription: userId={} destination={}", principal.userId(), destination);
            return;
        }
        boolean clinical = "DOCTOR".equals(principal.role()) || "NURSE".equals(principal.role());
        if (clinical && wardId.equals(principal.assignedWardId())) {
            return;
        }
        throw deny(principal, destination);
    }

    private static AccessDeniedException deny(CareRoundPrincipal principal, String destination) {
        log.warn("Denied SUBSCRIBE: userId={} role={} destination={}", principal.userId(), principal.role(), destination);
        return new AccessDeniedException("Subscription to " + destination + " is not allowed");
    }

    // Rejects lenient forms UUID.fromString accepts (e.g. "1-1-1-1-1"), so only a real ward id matches.
    private static UUID parseCanonicalUuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equalsIgnoreCase(value) ? id : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Same claim mapping as JwtAuthFilter.
    private static JwtAuthenticationToken toAuthentication(Claims claims) {
        UUID userId = UUID.fromString(claims.getSubject());
        String role = claims.get("role", String.class);
        String grade = claims.get("grade", String.class);
        String wardIdStr = claims.get("assignedWardId", String.class);
        UUID assignedWardId = wardIdStr != null ? UUID.fromString(wardIdStr) : null;

        @SuppressWarnings("unchecked")
        List<String> permissionClaims = claims.get("permissions", List.class);

        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        if (grade != null) {
            authorities.add(new SimpleGrantedAuthority("GRADE_" + grade));
        }
        if (permissionClaims != null) {
            permissionClaims.forEach(p -> authorities.add(new SimpleGrantedAuthority("PERM_" + p)));
        }
        return new JwtAuthenticationToken(new CareRoundPrincipal(userId, role, grade, assignedWardId), authorities);
    }
}
