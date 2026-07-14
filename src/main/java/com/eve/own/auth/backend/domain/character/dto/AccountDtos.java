package com.eve.own.auth.backend.domain.character.dto;

import java.util.List;

public class AccountDtos {

    public record EveAccountDto(
            String mainCharacterName,
            String corporationName,
            String allianceName,
            Double totalIsk,
            Long totalSkillPoints,
            int characterCount,
            List<AssetGroupDto> assetGroups,
            List<LoyaltyPointDto> loyaltyPoints
    ) {}

    public record AssetGroupDto(String groupName, Long quantity, String imageUrl) {}
    public record LoyaltyPointDto(String factionName, Integer amount) {}
}

