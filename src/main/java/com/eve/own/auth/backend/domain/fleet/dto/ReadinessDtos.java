package com.eve.own.auth.backend.domain.fleet.dto;

import java.util.List;

public class ReadinessDtos {

    public record HullDto(Long typeId, String typeName, String iconUrl, String renderUrl,
                          List<String> fitNames) {}

    public record RequiredSkillDto(Long skillTypeId, String skillName, int level) {}

    public record MissingSkillDto(Long skillTypeId, String skillName, int requiredLevel, int currentLevel) {}

    // === DIE NEUE, KOMBINIERTE MATRIX ===

    public record CharacterReadinessDto(
            Long characterId, String characterName, String portraitUrl, boolean main,
            long owned, boolean skillDataAvailable, boolean canFly, int skillsMet, int skillsRequired,
            List<MissingSkillDto> missingSkills
    ) {}

    public record AccountReadinessDto(
            Long mainId, String mainName, String portraitUrl, String corporationName,
            long owned, int charactersOwning,
            boolean canFly, int pilotsCapable, boolean skillDataAvailable, int bestSkillsMet, int skillsRequired,
            boolean hasShip, boolean hasSkills, boolean isReady,
            List<CharacterReadinessDto> characters
    ) {}

    public record HullReadinessDto(
            Long typeId, String typeName, String iconUrl, String renderUrl,
            List<RequiredSkillDto> requiredSkills,
            long hullsTotal, int accountsReady, int accountsTotal, double coverage,
            List<AccountReadinessDto> ready,
            List<AccountReadinessDto> notReady
    ) {}

    public record DoctrineReadinessDto(
            String doctrineName, int accountsTotal, int hullsChecked, List<HullReadinessDto> hulls
    ) {}

    // --- EFT-Sandbox ---

    public record FitModuleDto(
            Long typeId, String typeName, String iconUrl, int quantity,
            String chargeName, Long chargeTypeId
    ) {}

    public record FitSlotGroupDto(String name, String icon, int moduleCount, List<FitModuleDto> modules) {}

    public record ParsedFitDto(
            Long shipTypeId, String shipTypeName, String fitName, String iconUrl, String renderUrl,
            int moduleCount, List<FitSlotGroupDto> groups, List<String> unresolved
    ) {}

    public record SandboxRequest(String eftString) {}

    public record SandboxResultDto(
            ParsedFitDto fit,
            HullReadinessDto board
    ) {}
}