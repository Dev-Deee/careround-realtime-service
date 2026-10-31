package com.dayo.careround.realtimeservice.config;

import com.dayo.careround.realtimeservice.security.StompAuthChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.SimpleBrokerRegistration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer, DisposableBean {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    @Value("${app.realtime.allowed-origins:http://localhost:*}")
    private String[] allowedOrigins;

    // STOMP heart-beat interval (ms) in both directions; 0 disables. Hospital networks, proxies and load balancers
    // drop idle connections, and a client that stops answering is detected and closed by the broker.
    @Value("${app.realtime.heartbeat-ms:10000}")
    private long heartbeatMs;

    // Dedicated, not a bean: a TaskScheduler bean would also become the @Scheduled scheduler (the expiry sweep).
    private ThreadPoolTaskScheduler heartbeatScheduler;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins);
        registry.setErrorHandler(new StompErrorHandler());
    }

    // In-memory broker for /topic. Each instance only fans out to its own sockets; Redis is the
    // cross-instance backplane. No application destination prefix: clients never SEND.
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        SimpleBrokerRegistration broker = registry.enableSimpleBroker("/topic");
        if (heartbeatMs > 0) {
            heartbeatScheduler = new ThreadPoolTaskScheduler();
            heartbeatScheduler.setPoolSize(1);
            heartbeatScheduler.setThreadNamePrefix("stomp-heartbeat-");
            heartbeatScheduler.initialize();
            broker.setHeartbeatValue(new long[]{heartbeatMs, heartbeatMs}).setTaskScheduler(heartbeatScheduler);
        }
    }

    @Override
    public void destroy() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdown();
        }
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
