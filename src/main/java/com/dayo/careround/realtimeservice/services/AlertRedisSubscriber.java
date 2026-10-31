package com.dayo.careround.realtimeservice.services;

import com.dayo.careround.realtimeservice.dto.AlertMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

// Never throws: a bad message is logged and dropped so the listener keeps receiving.
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertRedisSubscriber implements MessageListener {

    private static final int MAX_LOGGED_BODY = 300;

    private final ObjectMapper objectMapper;
    private final AlertBroadcastService alertBroadcastService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);

        AlertMessage alert;
        try {
            alert = objectMapper.readValue(body, AlertMessage.class);
            validate(alert);
        } catch (Exception e) {
            log.warn("Dropping malformed alert message: {} | body: {}", e.getMessage(), abbreviate(body));
            return;
        }

        try {
            alertBroadcastService.broadcast(alert);
        } catch (Exception e) {
            log.error("Failed to push alert eventId={}: {}", alert.eventId(), e.toString(), e);
        }
    }

    private static void validate(AlertMessage alert) {
        if (alert == null) {
            throw new IllegalArgumentException("empty message");
        }
        if (alert.kind() == null || alert.kind().isBlank()) {
            throw new IllegalArgumentException("kind is required");
        }
        if (alert.eventId() == null || alert.eventId().isBlank()) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (alert.patientId() == null) {
            throw new IllegalArgumentException("patientId is required");
        }
    }

    private static String abbreviate(String body) {
        return body.length() <= MAX_LOGGED_BODY ? body : body.substring(0, MAX_LOGGED_BODY) + "...";
    }
}
