package com.dayo.careround.realtimeservice.services;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SessionExpiryServiceTest {

    private final MessageChannel outbound = mock(MessageChannel.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-31T10:00:00Z"));
    private final SessionExpiryService service = new SessionExpiryService(outbound, clock);

    @Test
    void sweepClosesOnlySessionsPastExpiryWithAClearReason() {
        service.register("live", clock.instant().plusSeconds(600));
        service.register("stale", clock.instant().plusSeconds(10));

        clock.advanceSeconds(30);
        service.closeExpiredSessions();

        ArgumentCaptor<Message<?>> sent = ArgumentCaptor.forClass(Message.class);
        verify(outbound, times(1)).send(sent.capture());
        StompHeaderAccessor error = StompHeaderAccessor.wrap(sent.getValue());
        assertThat(error.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(error.getSessionId()).isEqualTo("stale");
        assertThat(error.getMessage()).isEqualTo(SessionExpiryService.EXPIRED_REASON);
        assertThat(service.trackedSessions()).isEqualTo(1);
        assertThat(service.isExpired("live")).isFalse();
    }

    @Test
    void sweepClosesASessionOnlyOnce() {
        service.register("stale", clock.instant().plusSeconds(5));
        clock.advanceSeconds(10);

        service.closeExpiredSessions();
        service.closeExpiredSessions();

        verify(outbound, times(1)).send(any());
    }

    @Test
    void sweepDoesNothingBeforeExpiry() {
        service.register("live", clock.instant().plusSeconds(60));
        service.closeExpiredSessions();
        verify(outbound, never()).send(any());
    }

    @Test
    void unknownSessionCountsAsExpired() {
        assertThat(service.isExpired("never-connected")).isTrue();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }

        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
