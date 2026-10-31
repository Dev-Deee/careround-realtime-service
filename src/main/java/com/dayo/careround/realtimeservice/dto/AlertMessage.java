package com.dayo.careround.realtimeservice.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Message on the Redis alerts channel, published by notification-service (schemaVersion 1, additive only).
 * Ids only: no names, no clinical detail. This record is also what is pushed to STOMP clients, so a field
 * added upstream is not forwarded until it is added here.
 *
 * @param kind     ALERT_RAISED | ALERT_RESOLVED | ALERT_ACKNOWLEDGED (kept as a string so a new kind is routed, not dropped)
 * @param alertId  Alert.publicId; null for ALERT_RESOLVED
 * @param eventId  Kafka record key of the source event (Alert.sourceEventId)
 * @param wardId   ward publicId; null when the ward could not be resolved (routed to the admin topic)
 */
public record AlertMessage(
        Integer schemaVersion,
        String kind,
        UUID alertId,
        String eventId,
        String type,
        UUID wardId,
        UUID patientId,
        Instant occurredAt,
        UUID acknowledgedBy
) {}
