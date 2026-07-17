package com.eve.own.auth.backend.domain.auth.dto;

import java.util.Set;

public record UserProfileDto(
        Long characterId,
        String characterName,
        String portraitUrl,
        Set<String> roles
) {}