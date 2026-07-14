package com.eve.own.auth.backend.domain.auth.dto;

public record UserProfileDto(
        Long characterId,
        String characterName,
        String portraitUrl
) {}