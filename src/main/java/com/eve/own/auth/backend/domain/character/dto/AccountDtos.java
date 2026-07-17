package com.eve.own.auth.backend.domain.character.dto;

import java.util.Map;

public class AccountDtos {

    public record DashboardAssetSummaryDto(
            Map<String, Long> subcapital,
            Map<String, Long> capital,
            Map<String, Long> industrial,
            Map<String, Long> notable,
            Map<String, Long> structures
    ) {}

    public record AssetGroupDto(String groupName, Long quantity, String imageUrl) {}
    public record LoyaltyPointDto(String factionName, Integer amount) {}
    public record LinkedCharacterDto(Long id, String name, String portraitUrl) {}
    public record DashboardAffiliationsDto(
            Map<String, Long> militias,
            Long evermarks,
            Map<String, Long> loyaltyPoints
    ) {}
}