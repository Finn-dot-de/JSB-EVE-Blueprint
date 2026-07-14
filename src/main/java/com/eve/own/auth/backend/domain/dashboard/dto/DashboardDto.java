package com.eve.own.auth.backend.domain.dashboard.dto;

public record DashboardDto(
        String characterName,
        String portraitUrl,
        String corporationName,
        String allianceName,
        Double totalWalletBalance,
        int totalCharacters
) {}