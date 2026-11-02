package com.dayo.careround.realtimeservice.services;

import com.dayo.careround.realtimeservice.config.JacksonConfig;
import com.dayo.careround.realtimeservice.dto.AlertMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.DefaultMessage;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AlertRedisSubscriberTest {

    private final AlertBroadcastService broadcast = mock(AlertBroadcastService.class);
    private final AlertRedisSubscriber subscriber = new AlertRedisSubscriber(new JacksonConfig().objectMapper(), broadcast);

    @Test
    void validMessageIsBroadcastAndUnknownFieldsAreIgnored() throws Exception {
        UUID ward = UUID.randomUUID();
        subscriber.onMessage(message("""
                {"schemaVersion":1,"kind":"ALERT_RAISED","alertId":"%s","eventId":"emergency-declared-1",
                 "type":"PATIENT_EMERGENCY_DECLARED","wardId":"%s","patientId":"%s",
                 "occurredAt":"2026-10-31T10:00:00Z","acknowledgedBy":null,"addedLater":"x"}
                """.formatted(UUID.randomUUID(), ward, UUID.randomUUID())), null);

        ArgumentCaptor<AlertMessage> sent = ArgumentCaptor.forClass(AlertMessage.class);
        verify(broadcast).broadcast(sent.capture());
        assertThat(sent.getValue().wardId()).isEqualTo(ward);
        assertThat(AlertBroadcastService.destinationFor(sent.getValue())).isEqualTo("/topic/wards/" + ward);
    }

    @Test
    void nullWardRoutesToAdminTopic() {
        AlertMessage alert = new AlertMessage(1, "ALERT_RAISED", UUID.randomUUID(), "e1", "PATIENT_EMERGENCY_DECLARED",
                null, UUID.randomUUID(), null, null, null, null, null, null);
        assertThat(AlertBroadcastService.destinationFor(alert)).isEqualTo("/topic/admin/alerts");
    }

    @Test
    void targetedAlertGoesOnlyToThePersonalTopicAndCarriesTheNewFields() throws Exception {
        UUID ward = UUID.randomUUID(), doctor = UUID.randomUUID(), task = UUID.randomUUID();
        subscriber.onMessage(message("""
                {"schemaVersion":1,"kind":"ALERT_RAISED","alertId":"%s","eventId":"medication-overdue-1:doctor",
                 "type":"MEDICATION_OVERDUE","wardId":"%s","patientId":"%s","occurredAt":"2026-11-02T10:00:00Z",
                 "targetUserId":"%s","severity":"CRITICAL","requiresAck":true,"taskPublicId":"%s"}
                """.formatted(UUID.randomUUID(), ward, UUID.randomUUID(), doctor, task)), null);
        ArgumentCaptor<AlertMessage> sent = ArgumentCaptor.forClass(AlertMessage.class);
        verify(broadcast).broadcast(sent.capture());
        AlertMessage a = sent.getValue();
        assertThat(AlertBroadcastService.destinationFor(a)).isEqualTo("/topic/users/" + doctor); // not the ward topic
        assertThat(a.severity()).isEqualTo("CRITICAL");
        assertThat(a.requiresAck()).isTrue();
        assertThat(a.taskPublicId()).isEqualTo(task);
        assertThat(new JacksonConfig().objectMapper().writeValueAsString(a))
                .contains("\"targetUserId\":\"" + doctor).contains("\"severity\":\"CRITICAL\"")
                .contains("\"requiresAck\":true").contains("\"taskPublicId\":\"" + task);
    }

    @Test
    void malformedMessagesAreDroppedWithoutThrowing() throws Exception {
        for (String bad : new String[]{"not json", "{\"kind\":\"ALERT_RAISED\"}", "{\"kind\":\"ALERT_RAISED\",\"eventId\":\"e\",\"patientId\":\"nope\"}", "null"}) {
            subscriber.onMessage(message(bad), null);
        }
        verify(broadcast, never()).broadcast(any());
    }

    private static DefaultMessage message(String body) {
        return new DefaultMessage("careround.alerts".getBytes(StandardCharsets.UTF_8), body.getBytes(StandardCharsets.UTF_8));
    }
}
