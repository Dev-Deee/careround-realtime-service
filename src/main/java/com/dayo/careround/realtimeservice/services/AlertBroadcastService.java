package com.dayo.careround.realtimeservice.services;

import com.dayo.careround.realtimeservice.dto.AlertMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertBroadcastService {

    public static final String WARD_TOPIC_PREFIX = "/topic/wards/";
    public static final String ADMIN_ALERTS_TOPIC = "/topic/admin/alerts";
    public static final String USER_TOPIC_PREFIX = "/topic/users/";

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    // Targeted alerts go only to their recipient's personal topic. Ward alerts go to that ward's topic; alerts whose
    // ward could not be resolved go to admins.
    public static String destinationFor(AlertMessage alert) {
        if (alert.targetUserId() != null) return USER_TOPIC_PREFIX + alert.targetUserId();
        return alert.wardId() != null ? WARD_TOPIC_PREFIX + alert.wardId() : ADMIN_ALERTS_TOPIC;
    }

    public void broadcast(AlertMessage alert) throws JsonProcessingException {
        String destination = destinationFor(alert);
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
        headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
        headers.setLeaveMutable(true);
        messagingTemplate.send(destination,
                MessageBuilder.createMessage(objectMapper.writeValueAsBytes(alert), headers.getMessageHeaders()));
        log.info("Pushed {} eventId={} to {}", alert.kind(), alert.eventId(), destination);
    }
}
