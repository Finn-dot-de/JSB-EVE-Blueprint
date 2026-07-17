package com.eve.own.auth.backend.domain.dashboard.service;

import com.eve.own.auth.backend.domain.character.dto.AccountDtos;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.CharacterStats;
import com.eve.own.auth.backend.domain.character.repository.CharacterAssetRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterLpRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterStatsRepository;
import com.eve.own.auth.backend.domain.dashboard.dto.DashboardDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class DashboardService {

    private final CharacterRepository characterRepo;
    private final CharacterStatsRepository statsRepo;
    private final CharacterAssetRepository assetRepo;
    private final CharacterLpRepository lpRepo;

    public DashboardService(CharacterRepository characterRepo,
                            CharacterStatsRepository statsRepo,
                            CharacterAssetRepository assetRepo,
                            CharacterLpRepository lpRepo) {
        this.characterRepo = characterRepo;
        this.statsRepo = statsRepo;
        this.assetRepo = assetRepo;
        this.lpRepo = lpRepo;
    }

    @Transactional(readOnly = true)
    public DashboardDto getDashboardData(Long requestingCharacterId) {

        // 1. Account-Charaktere laden (Main + Alts)
        Character reqChar = characterRepo.findById(requestingCharacterId)
                .orElseThrow(() -> new RuntimeException("Charakter nicht gefunden"));

        Long mainId = reqChar.getMainCharacterId() != null ? reqChar.getMainCharacterId() : reqChar.getId();
        List<Character> accountCharacters = characterRepo.findByMainCharacterId(mainId);

        // Liste mit allen IDs für die DB-Queries vorbereiten
        List<Long> characterIds = accountCharacters.stream().map(Character::getId).toList();

        // 2. Stats (ISK & SP) aggregieren
        double totalIsk = 0.0;
        long totalSp = 0L;
        List<CharacterStats> statsList = statsRepo.findAllById(characterIds);
        for (CharacterStats stats : statsList) {
            if (stats.getWalletBalance() != null) totalIsk += stats.getWalletBalance();
            if (stats.getSkillPoints() != null) totalSp += stats.getSkillPoints();
        }

        // 3. Assets über SDE gruppieren
        List<Object[]> rawAssets = assetRepo.aggregateAssetsByGroup(characterIds);

        // Unsere Haupt-Eimer für das Frontend
        java.util.Map<String, Long> subcapital = new java.util.LinkedHashMap<>();
        java.util.Map<String, Long> capital = new java.util.LinkedHashMap<>();
        java.util.Map<String, Long> industrial = new java.util.LinkedHashMap<>();
        java.util.Map<String, Long> notable = new java.util.LinkedHashMap<>();
        java.util.Map<String, Long> structures = new java.util.LinkedHashMap<>();

        // Standard-Struktur mit 0 initialisieren
        List.of("Frigate", "Destroyer", "Cruiser", "Battlecruiser", "Battleship").forEach(k -> subcapital.put(k, 0L));
        List.of("Dreadnought", "Carrier", "Supercarrier", "Force Auxiliary", "Titan").forEach(k -> capital.put(k, 0L));
        List.of("Mining", "Hauler", "Industrial Command Ship", "Capital Industrial").forEach(k -> industrial.put(k, 0L));
        List.of("Skill Injector", "Skill Extractor").forEach(k -> notable.put(k, 0L));
        List.of("Citadel", "Refinery", "Engineering Complex").forEach(k -> structures.put(k, 0L));

        for (Object[] row : rawAssets) {
            String group = (String) row[0];
            Long quantity = ((Number) row[1]).longValue();

            // --- SUBCAPITAL CLASSIFICATION ---
            if (Set.of("Frigate", "Assault Frigate", "Covert Ops", "Electronic Attack Ship", "Interceptor", "Logistics Frigate", "Stealth Bomber", "Expedition Frigate").contains(group)) {
                subcapital.put("Frigate", subcapital.get("Frigate") + quantity);
            }
            else if (Set.of("Destroyer", "Command Destroyer", "Tactical Destroyer").contains(group)) {
                subcapital.put("Destroyer", subcapital.get("Destroyer") + quantity);
            }
            else if (Set.of("Cruiser", "Heavy Assault Cruiser", "Heavy Interdiction Cruiser", "Logistics", "Force Recon Ship", "Combat Recon Ship", "Strategic Cruiser").contains(group)) {
                subcapital.put("Cruiser", subcapital.get("Cruiser") + quantity);
            }
            else if (Set.of("Combat Battlecruiser", "Attack Battlecruiser", "Command Ship").contains(group)) {
                subcapital.put("Battlecruiser", subcapital.get("Battlecruiser") + quantity);
            }
            else if (Set.of("Battleship", "Black Ops", "Marauder").contains(group)) {
                subcapital.put("Battleship", subcapital.get("Battleship") + quantity);
            }

            // --- NOTABLE CLASSIFICATION ---
            else if (group.contains("Skill Injector")) {
                notable.put("Skill Injector", notable.get("Skill Injector") + quantity);
            }
            else if (group.contains("Skill Extractor")) {
                notable.put("Skill Extractor", notable.get("Skill Extractor") + quantity);
            }

            // --- STRUCTURES CLASSIFICATION ---
            else if (group.equals("Citadel")) {
                structures.put("Citadel", structures.get("Citadel") + quantity);
            }
            else if (group.equals("Refinery")) {
                structures.put("Refinery", structures.get("Refinery") + quantity);
            }
            else if (group.equals("Engineering Complex")) {
                structures.put("Engineering Complex", structures.get("Engineering Complex") + quantity);
            }

            // --- CAPITAL CLASSIFICATION ---
            else if (group.contains("Dreadnought")) {
                capital.put("Dreadnought", capital.get("Dreadnought") + quantity);
            }
            else if (group.equals("Carrier")) {
                capital.put("Carrier", capital.get("Carrier") + quantity);
            }
            else if (group.equals("Supercarrier")) {
                capital.put("Supercarrier", capital.get("Supercarrier") + quantity);
            }
            else if (group.equals("Titan")) {
                capital.put("Titan", capital.get("Titan") + quantity);
            }
            else if (group.equals("Force Auxiliary") || group.equals("Logistics Cruiser")) {
                capital.put("Force Auxiliary", capital.get("Force Auxiliary") + quantity);
            }

            // --- INDUSTRIAL CLASSIFICATION (Deine Anpassung!) ---
            else if (Set.of("Mining Barge", "Exhumer").contains(group)) {
                industrial.put("Mining", industrial.get("Mining") + quantity);
            }
            else if (Set.of("Hauler", "Blockade Runner", "Deep Space Transport", "Industrial Ship", "Transport Ship").contains(group)) {
                industrial.put("Hauler", industrial.get("Hauler") + quantity);
            }
            else if (java.util.Objects.equals("Industrial Command Ship", group)) {
                industrial.put("Industrial Command Ship", industrial.get("Industrial Command Ship") + quantity);
            }
            else if (Set.of("Freighter", "Jump Freighter", "Capital Industrial Ship").contains(group)) {
                industrial.put("Capital Industrial", industrial.get("Capital Industrial") + quantity);
            }
        }

        var assetSummary = new AccountDtos.DashboardAssetSummaryDto(
                subcapital,
                capital,
                industrial,
                notable,
                structures
        );

        // --- 4. AFFILIATIONS (Militias, Evermarks, Target LPs) ---
        java.util.Map<String, Long> militias = new java.util.LinkedHashMap<>();
        List.of("Amarr", "Gallente", "Minmatar", "Caldari", "Angel", "Guristas").forEach(k -> militias.put(k, 0L));

        for (Character c : accountCharacters) {
            if (c.getCorporation() != null && c.getCorporation().getFactionId() != null) {
                long factionId = c.getCorporation().getFactionId();
                if (factionId == 500007L) militias.put("Amarr", militias.get("Amarr") + 1);
                else if (factionId == 500004L) militias.put("Gallente", militias.get("Gallente") + 1);
                else if (factionId == 500002L) militias.put("Minmatar", militias.get("Minmatar") + 1);
                else if (factionId == 500001L) militias.put("Caldari", militias.get("Caldari") + 1);
                else if (factionId == 500011L) militias.put("Angel", militias.get("Angel") + 1);
                else if (factionId == 500010L) militias.put("Guristas", militias.get("Guristas") + 1);
            }
        }

        // LP-Abfrage ausführen
        List<Object[]> rawLps = lpRepo.aggregateLp(characterIds);

        long evermarks = 0L;
        long totalLpSum = 0L;
        java.util.Map<String, Long> targetLps = new java.util.LinkedHashMap<>();
        List.of("CONCORD", "FederalAdmin", "BloodRaiders", "FreedomExtension").forEach(k -> targetLps.put(k, 0L));

        for (Object[] row : rawLps) {
            long corpId = ((Number) row[0]).longValue();
            long amount = ((Number) row[1]).longValue();

            totalLpSum += amount;

            // Evermarks (Paragon: Corp 1000419)
            if (corpId == 1000419L) {
                evermarks += amount;
            }
            // Spezifische Corps filtern
            else if (corpId == 1000125L) { // CONCORD
                targetLps.put("CONCORD", amount);
            }
            else if (corpId == 1000119L) { // Federal Administration
                targetLps.put("FederalAdmin", amount);
            }
            else if (corpId == 1000134L) { // Blood Raiders
                targetLps.put("BloodRaiders", amount);
            }
            else if (corpId == 1000061L) { // Freedom Extension
                targetLps.put("FreedomExtension", amount);
            }
        }

        // Wir fügen das Gesamtergebnis (erste Box) in die LP-Map ein
        java.util.Map<String, Long> lpSummary = new java.util.LinkedHashMap<>();
        lpSummary.put("Total", totalLpSum);
        lpSummary.putAll(targetLps);

        var affiliationsSummary = new AccountDtos.DashboardAffiliationsDto(
                militias,
                evermarks,
                lpSummary
        );

        // 5. Allianz-Daten extrahieren
        String allianceName = null;
        Long allianceId = null;
        if (reqChar.getCorporation().getAlliance() != null) {
            allianceName = reqChar.getCorporation().getAlliance().getName();
            allianceId = reqChar.getCorporation().getAlliance().getId();
        }

        Long corpId = reqChar.getCorporation().getId();
        String portraitUrl = String.format("https://images.evetech.net/characters/%d/portrait?size=128", reqChar.getId());

        List<AccountDtos.LinkedCharacterDto> linkedCharacters = accountCharacters.stream()
                .map(c -> new AccountDtos.LinkedCharacterDto(
                        c.getId(),
                        c.getName(),
                        String.format("https://images.evetech.net/characters/%d/portrait?size=64", c.getId())
                )).toList();

        // 6. DTO abschicken
        return new DashboardDto(
                reqChar.getName(),
                portraitUrl,
                corpId,
                reqChar.getCorporation().getName(),
                allianceId,
                allianceName,
                totalIsk,
                totalSp,
                accountCharacters.size(),
                linkedCharacters,
                assetSummary,
                affiliationsSummary,
                new ArrayList<>()
        );
    }
}