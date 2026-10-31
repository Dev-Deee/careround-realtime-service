package com.dayo.careround.realtimeservice.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Access tokens live 15 minutes but a socket can stay open longer. Each session's token exp is recorded at
 * CONNECT; the sweep sends an ERROR frame to sessions past exp (Spring then closes the socket) so the client
 * refreshes its token and reconnects.
 */
@Slf4j
@Service
public class SessionExpiryService {

    public static final String EXPIRED_REASON = "Access token expired: refresh the token and reconnect";

    private final Map<String, Instant> expiries = new ConcurrentHashMap<>();
    private final MessageChannel clientOutboundChannel;
    private final Clock clock;

    // @Lazy: the outbound channel is built by the broker configuration, which itself needs this service
    // (through StompAuthChannelInterceptor).
    public SessionExpiryService(@Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel, Clock clock) {
        this.clientOutboundChannel = clientOutboundChannel;
        this.clock = clock;
    }

    public void register(String sessionId, Instant expiresAt) {
        expiries.put(sessionId, expiresAt);
    }

    public boolean isExpired(String sessionId) {
        Instant expiresAt = expiries.get(sessionId);
        return expiresAt == null || !expiresAt.isAfter(clock.instant());
    }

    public int trackedSessions() {
        return expiries.size();
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        expiries.remove(event.getSessionId());
    }

    @Scheduled(fixedDelayString = "${app.realtime.session-sweep-ms:30000}")
    public void closeExpiredSessions() {
        Instant now = clock.instant();
        expiries.forEach((sessionId, expiresAt) -> {
            if (!expiresAt.isAfter(now) && expiries.remove(sessionId, expiresAt)) {
                log.info("Closing STOMP session {}: token expired at {}", sessionId, expiresAt);
                StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
                accessor.setMessage(EXPIRED_REASON);
                accessor.setSessionId(sessionId);
                clientOutboundChannel.send(MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()));
            }
        });
    }
}
