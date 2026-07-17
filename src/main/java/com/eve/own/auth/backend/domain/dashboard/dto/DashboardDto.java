package com.eve.own.auth.backend.domain.dashboard.dto;

import com.eve.own.auth.backend.domain.character.dto.AccountDtos;

import java.util.List;

public record DashboardDto(
        String characterName,
        String portraitUrl,
        Long corporationId,
        String corporationName,
        Long allianceId,
        String allianceName,
        Double totalWalletBalance,
        Long totalSkillPoints,
        int totalCharacters,
        List<AccountDtos.LinkedCharacterDto> linkedCharacters,
        AccountDtos.DashboardAssetSummaryDto assets,
        AccountDtos.DashboardAffiliationsDto affiliations,
        List<AccountDtos.LoyaltyPointDto> rawLoyaltyPoints
) {}

