package com.dayo.careround.realtimeservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    // Injected so token-expiry checks can be tested with a fixed clock.
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
