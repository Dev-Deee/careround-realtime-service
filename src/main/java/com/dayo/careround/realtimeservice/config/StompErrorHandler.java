package com.dayo.careround.realtimeservice.config;

import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * Puts the auth reason ("Invalid or expired token", "Subscription to ... is not allowed") in the ERROR
 * frame instead of Spring's wrapper text. Any other failure gets a generic message so nothing leaks.
 */
public class StompErrorHandler extends StompSubProtocolErrorHandler {

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
        Throwable cause = ex;
        while (cause != null && !isAuthFailure(cause)) {
            cause = cause.getCause();
        }

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(cause != null ? cause.getMessage() : "The message could not be processed");
        accessor.setLeaveMutable(true);
        StompHeaderAccessor clientHeaders = clientMessage != null ? StompHeaderAccessor.wrap(clientMessage) : null;
        return handleInternal(accessor, new byte[0], ex, clientHeaders);
    }

    private static boolean isAuthFailure(Throwable t) {
        return t instanceof AuthenticationException || t instanceof AccessDeniedException;
    }
}
