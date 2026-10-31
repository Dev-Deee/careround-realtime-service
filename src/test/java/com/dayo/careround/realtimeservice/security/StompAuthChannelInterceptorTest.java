package com.dayo.careround.realtimeservice.security;

import com.dayo.careround.realtimeservice.services.SessionExpiryService;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@ExtendWith(OutputCaptureExtension.class)
class StompAuthChannelInterceptorTest {

    private static final UUID WARD_A = UUID.randomUUID();
    private static final UUID WARD_B = UUID.randomUUID();
    private static KeyPair keys;
    private static KeyPair otherKeys;

    private final MessageChannel channel = mock(MessageChannel.class);
    private SessionExpiryService sessions;
    private StompAuthChannelInterceptor interceptor;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        otherKeys = generator.generateKeyPair();
    }

    @BeforeEach
    void setUp() {
        sessions = new SessionExpiryService(mock(MessageChannel.class), Clock.systemUTC());
        interceptor = new StompAuthChannelInterceptor(new JwtVerificationService(keys.getPublic()), sessions);
    }

    @Test
    void validTokenAuthenticatesAndRecordsExpiry() {
        Message<?> result = interceptor.preSend(connect("s1", bearer(token("NURSE", WARD_A))), channel);

        JwtAuthenticationToken user = (JwtAuthenticationToken) StompHeaderAccessor.wrap(result).getUser();
        assertThat(user).isNotNull();
        CareRoundPrincipal principal = (CareRoundPrincipal) user.getPrincipal();
        assertThat(principal.role()).isEqualTo("NURSE");
        assertThat(principal.assignedWardId()).isEqualTo(WARD_A);
        assertThat(user.getAuthorities()).extracting(Object::toString).contains("ROLE_NURSE", "GRADE_STAFF_NURSE");
        assertThat(sessions.isExpired("s1")).isFalse();
    }

    @Test
    void missingTokenIsRejected() {
        assertThatThrownBy(() -> interceptor.preSend(connect("s1", null), channel))
                .isInstanceOf(BadCredentialsException.class).hasMessageContaining("Missing bearer token");
    }

    @Test
    void nonBearerHeaderIsRejected() {
        assertThatThrownBy(() -> interceptor.preSend(connect("s1", "Basic abc"), channel))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        String expired = token("NURSE", WARD_A, keys.getPrivate(), Instant.now().minus(Duration.ofMinutes(1)));
        assertThatThrownBy(() -> interceptor.preSend(connect("s1", bearer(expired)), channel))
                .isInstanceOf(BadCredentialsException.class).hasMessage("Invalid or expired token");
    }

    @Test
    void badSignatureIsRejected() {
        String forged = token("ADMIN", null, otherKeys.getPrivate(), Instant.now().plus(Duration.ofMinutes(15)));
        assertThatThrownBy(() -> interceptor.preSend(connect("s1", bearer(forged)), channel))
                .isInstanceOf(BadCredentialsException.class).hasMessage("Invalid or expired token");
    }

    @Test
    void garbageTokenIsRejected() {
        assertThatThrownBy(() -> interceptor.preSend(connect("s1", "Bearer not.a.jwt"), channel))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void nurseMaySubscribeToOwnWard() {
        Object user = connectAs("s1", "NURSE", WARD_A);
        assertThatCode(() -> interceptor.preSend(subscribe("s1", user, "/topic/wards/" + WARD_A), channel))
                .doesNotThrowAnyException();
    }

    @Test
    void doctorMaySubscribeToOwnWard() {
        Object user = connectAs("s1", "DOCTOR", WARD_A);
        assertThatCode(() -> interceptor.preSend(subscribe("s1", user, "/topic/wards/" + WARD_A), channel))
                .doesNotThrowAnyException();
    }

    @Test
    void nurseMayNotSubscribeToAnotherWard() {
        Object user = connectAs("s1", "NURSE", WARD_A);
        assertThatThrownBy(() -> interceptor.preSend(subscribe("s1", user, "/topic/wards/" + WARD_B), channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void nurseMayNotSubscribeToAdminTopic() {
        Object user = connectAs("s1", "NURSE", WARD_A);
        assertThatThrownBy(() -> interceptor.preSend(subscribe("s1", user, "/topic/admin/alerts"), channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void nurseWithoutWardMayNotSubscribeToAnyWard() {
        Object user = connectAs("s1", "NURSE", null);
        assertThatThrownBy(() -> interceptor.preSend(subscribe("s1", user, "/topic/wards/" + WARD_A), channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adminMaySubscribeToAnyWardAndAdminTopicAndIsLogged(CapturedOutput output) {
        Object user = connectAs("s1", "ADMIN", null);
        assertThatCode(() -> {
            interceptor.preSend(subscribe("s1", user, "/topic/wards/" + WARD_B), channel);
            interceptor.preSend(subscribe("s1", user, "/topic/admin/alerts"), channel);
        }).doesNotThrowAnyException();
        assertThat(output).contains("Admin subscription:").contains("destination=/topic/wards/" + WARD_B)
                .contains("destination=/topic/admin/alerts");
    }

    @Test
    void malformedWardIdIsDenied() {
        Object user = connectAs("s1", "ADMIN", null);
        for (String bad : List.of("/topic/wards/not-a-uuid", "/topic/wards/1-1-1-1-1", "/topic/wards/" + WARD_A + "/x", "/topic/wards/")) {
            assertThatThrownBy(() -> interceptor.preSend(subscribe("s1", user, bad), channel))
                    .as(bad).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void unknownDestinationIsDenied() {
        Object user = connectAs("s1", "ADMIN", null);
        for (String dest : List.of("/topic/other", "/queue/x", "/user/queue/errors", "/topic/wards")) {
            assertThatThrownBy(() -> interceptor.preSend(subscribe("s1", user, dest), channel))
                    .as(dest).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void subscribeWithoutConnectIsDenied() {
        assertThatThrownBy(() -> interceptor.preSend(subscribe("s1", null, "/topic/wards/" + WARD_A), channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void sendIsAlwaysDenied() {
        Object user = connectAs("s1", "ADMIN", null);
        StompHeaderAccessor send = StompHeaderAccessor.create(StompCommand.SEND);
        send.setSessionId("s1");
        send.setUser((JwtAuthenticationToken) user);
        send.setDestination("/topic/wards/" + WARD_A);
        assertThatThrownBy(() -> interceptor.preSend(MessageBuilder.createMessage(new byte[0], send.getMessageHeaders()), channel))
                .isInstanceOf(AccessDeniedException.class).hasMessage("Clients may not send messages");
    }

    @Test
    void subscribeAfterTokenExpiryIsDenied() {
        String shortLived = token("NURSE", WARD_A, keys.getPrivate(), Instant.now().plusMillis(1500));
        Message<?> connected = interceptor.preSend(connect("s1", bearer(shortLived)), channel);
        Object user = StompHeaderAccessor.wrap(connected).getUser();
        sessions.register("s1", Instant.now().minusSeconds(1)); // as if exp had passed
        assertThatThrownBy(() -> interceptor.preSend(subscribe("s1", user, "/topic/wards/" + WARD_A), channel))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("expired");
    }

    private Object connectAs(String sessionId, String role, UUID ward) {
        Message<?> result = interceptor.preSend(connect(sessionId, bearer(token(role, ward))), channel);
        return StompHeaderAccessor.wrap(result).getUser();
    }

    private static Message<byte[]> connect(String sessionId, String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId(sessionId);
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<byte[]> subscribe(String sessionId, Object user, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId(sessionId);
        accessor.setSubscriptionId("sub-0");
        accessor.setDestination(destination);
        if (user != null) {
            accessor.setUser((JwtAuthenticationToken) user);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String token(String role, UUID ward) {
        return token(role, ward, keys.getPrivate(), Instant.now().plus(Duration.ofMinutes(15)));
    }

    private static String token(String role, UUID ward, PrivateKey signingKey, Instant expiresAt) {
        return Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("role", role)
                .claim("grade", "NURSE".equals(role) ? "STAFF_NURSE" : null)
                .claim("assignedWardId", ward != null ? ward.toString() : null)
                .claim("permissions", "ADMIN".equals(role) ? List.of("VIEW_AUDIT_LOGS") : List.of())
                .issuedAt(Date.from(expiresAt.minus(Duration.ofMinutes(15))))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }
}
