package com.eve.buy.bot.backend.domain.auth.dto;

import java.util.Set;

/** Die Angaben zum angemeldeten Charakter, die das Frontend anzeigt. */
public record UserProfileDto(
        Long characterId,
        String characterName,
        String portraitUrl,
        Set<String> roles
) {}