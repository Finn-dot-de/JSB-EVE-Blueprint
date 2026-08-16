package com.eve.own.auth.backend.domain.dashboard.service;

import com.eve.own.auth.backend.common.EveImageUrls;
import com.eve.own.auth.backend.domain.character.dto.AccountDtos;
import com.eve.own.auth.backend.domain.character.entity.Alliance;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.CharacterStats;
import com.eve.own.auth.backend.domain.character.repository.CharacterAssetRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterLpRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterStatsRepository;
import com.eve.own.auth.backend.domain.dashboard.LoyaltyCorporation;
import com.eve.own.auth.backend.domain.dashboard.Militia;
import com.eve.own.auth.backend.domain.dashboard.ShipCategory;
import com.eve.own.auth.backend.domain.dashboard.ShipClass;
import com.eve.own.auth.backend.domain.dashboard.dto.DashboardDto;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stellt die Startseite eines Accounts zusammen: Vermoegen, Bestaende und
 * Zugehoerigkeiten ueber alle Charaktere hinweg.
 */
@Slf4j
@Service
public class DashboardService {

    /** Spaltenpositionen der Bestandsabfrage. */
    private static final int COL_GROUP_NAME = 0;
    private static final int COL_QUANTITY = 1;

    /** Spaltenpositionen der Loyalitaetspunkt-Abfrage. */
    private static final int COL_CORPORATION_ID = 0;
    private static final int COL_AMOUNT = 1;

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
        Character character = characterRepo.findById(requestingCharacterId).orElseThrow(
                () -> new IllegalArgumentException("Charakter " + requestingCharacterId + " ist unbekannt."));

        List<Character> accountCharacters = characterRepo.findByMainCharacterId(character.getAccountId());
        List<Long> characterIds = accountCharacters.stream().map(Character::getId).toList();

        Wealth wealth = sumWealth(characterIds);
        Alliance alliance = character.getCorporation().getAlliance();

        return new DashboardDto(
                character.getName(),
                EveImageUrls.portrait(character.getId(), EveImageUrls.SIZE_LARGE),
                character.getCorporation().getId(),
                character.getCorporation().getName(),
                alliance != null ? alliance.getId() : null,
                alliance != null ? alliance.getName() : null,
                wealth.isk(),
                wealth.skillPoints(),
                accountCharacters.size(),
                linkedCharacters(accountCharacters),
                assetSummary(characterIds),
                affiliations(accountCharacters, characterIds),
                List.of());
    }

    /** Wallet und Skillpunkte des gesamten Accounts. */
    private record Wealth(double isk, long skillPoints) {}

    private Wealth sumWealth(List<Long> characterIds) {
        double isk = 0.0;
        long skillPoints = 0L;
        for (CharacterStats stats : statsRepo.findAllById(characterIds)) {
            if (stats.getWalletBalance() != null) {
                isk += stats.getWalletBalance();
            }
            if (stats.getSkillPoints() != null) {
                skillPoints += stats.getSkillPoints();
            }
        }
        return new Wealth(isk, skillPoints);
    }

    private static List<AccountDtos.LinkedCharacterDto> linkedCharacters(List<Character> characters) {
        return characters.stream()
                .map(character -> new AccountDtos.LinkedCharacterDto(
                        character.getId(), character.getName(), EveImageUrls.portrait(character.getId())))
                .toList();
    }

    // ==================================================================
    // Bestaende
    // ==================================================================

    /**
     * Verdichtet die Bestaende zu den Kaesten des Dashboards.
     *
     * <p>Die Zuordnung Gruppe-zu-Kasten liegt in {@link ShipClass}; hier wird nur
     * noch aufaddiert. Gruppen ohne Kasten - Module, Munition, Rohstoffe - fallen
     * bewusst heraus: das Dashboard zeigt Schiffe und Strukturen.</p>
     */
    private AccountDtos.DashboardAssetSummaryDto assetSummary(List<Long> characterIds) {
        Map<ShipCategory, Map<String, Long>> counters = new EnumMap<>(ShipCategory.class);
        for (ShipCategory category : ShipCategory.values()) {
            counters.put(category, ShipClass.emptyCounters(category));
        }

        for (Object[] row : assetRepo.aggregateAssetsByGroup(characterIds)) {
            String groupName = (String) row[COL_GROUP_NAME];
            long quantity = ((Number) row[COL_QUANTITY]).longValue();
            ShipClass.ofGroup(groupName).ifPresent(shipClass ->
                    counters.get(shipClass.category()).merge(shipClass.label(), quantity, Long::sum));
        }

        return new AccountDtos.DashboardAssetSummaryDto(
                counters.get(ShipCategory.SUBCAPITAL),
                counters.get(ShipCategory.CAPITAL),
                counters.get(ShipCategory.INDUSTRIAL),
                counters.get(ShipCategory.NOTABLE),
                counters.get(ShipCategory.STRUCTURES));
    }

    // ==================================================================
    // Zugehoerigkeiten
    // ==================================================================

    private AccountDtos.DashboardAffiliationsDto affiliations(List<Character> accountCharacters,
                                                             List<Long> characterIds) {
        Map<String, Long> militias = Militia.emptyCounters();
        for (Character character : accountCharacters) {
            if (character.getCorporation() == null) {
                continue;
            }
            Militia.ofFaction(character.getCorporation().getFactionId())
                    .ifPresent(militia -> militias.merge(militia.label(), 1L, Long::sum));
        }
        return loyaltySummary(characterIds).toDto(militias);
    }

    /** Gesamtsumme, Evermarks und die einzeln ausgewiesenen Corporations. */
    private LoyaltySummary loyaltySummary(List<Long> characterIds) {
        Map<String, Long> perCorporation = LoyaltyCorporation.emptyCounters();
        long total = 0L;
        long evermarks = 0L;

        for (Object[] row : lpRepo.aggregateLp(characterIds)) {
            long corporationId = ((Number) row[COL_CORPORATION_ID]).longValue();
            long amount = ((Number) row[COL_AMOUNT]).longValue();

            total += amount;
            if (corporationId == LoyaltyCorporation.PARAGON_CORPORATION_ID) {
                evermarks += amount;
            } else {
                LoyaltyCorporation.ofCorporation(corporationId)
                        .ifPresent(corporation -> perCorporation.merge(corporation.key(), amount, Long::sum));
            }
        }
        return new LoyaltySummary(total, evermarks, perCorporation);
    }

    private record LoyaltySummary(long total, long evermarks, Map<String, Long> perCorporation) {

        AccountDtos.DashboardAffiliationsDto toDto(Map<String, Long> militias) {
            // Die Gesamtsumme steht als erste Kachel vor den einzelnen Corporations.
            Map<String, Long> loyaltyPoints = new LinkedHashMap<>();
            loyaltyPoints.put(LoyaltyCorporation.TOTAL_KEY, total);
            loyaltyPoints.putAll(perCorporation);
            return new AccountDtos.DashboardAffiliationsDto(militias, evermarks, loyaltyPoints);
        }
    }
}
