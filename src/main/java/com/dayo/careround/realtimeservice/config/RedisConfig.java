package com.dayo.careround.realtimeservice.config;

import com.dayo.careround.realtimeservice.services.AlertRedisSubscriber;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Slf4j
@Configuration
public class RedisConfig {

    // Pub/sub is fire-and-forget: a message published while this instance is down or reconnecting is lost.
    // Clients recover missed alerts from notification-service's replay endpoint, not from Redis.
    @Bean
    public RedisMessageListenerContainer alertListenerContainer(RedisConnectionFactory connectionFactory,
                                                                AlertRedisSubscriber subscriber,
                                                                @Value("${app.realtime.redis-channel:careround.alerts}") String channel) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(channel));
        container.setErrorHandler(e -> log.error("Redis alert listener error: {}", e.toString(), e));
        log.info("Subscribing to Redis channel {}", channel);
        return container;
    }
}
