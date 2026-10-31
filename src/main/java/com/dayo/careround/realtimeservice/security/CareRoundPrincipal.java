package com.dayo.careround.realtimeservice.security;

import java.util.UUID;

public record CareRoundPrincipal(
        UUID userId,
        String role,
        String grade,
        UUID assignedWardId
) {}
