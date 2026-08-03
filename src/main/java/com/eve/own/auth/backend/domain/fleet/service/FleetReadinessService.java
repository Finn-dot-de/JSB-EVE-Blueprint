package com.eve.own.auth.backend.domain.fleet.service;

import com.eve.own.auth.backend.domain.fleet.dto.ReadinessDtos;
import com.eve.own.auth.backend.domain.fleet.entity.FleetDoctrine;
import com.eve.own.auth.backend.domain.fleet.repository.FleetDoctrineRepository;
import com.eve.own.auth.backend.domain.fleet.repository.ReadinessQueryRepository;
import jakarta.persistence.Tuple;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
public class FleetReadinessService {

    private final FleetDoctrineRepository doctrineRepo;
    private final ReadinessQueryRepository queryRepo;
    private final EftParserService eftParser;

    public FleetReadinessService(FleetDoctrineRepository doctrineRepo,
                                 ReadinessQueryRepository queryRepo,
                                 EftParserService eftParser) {
        this.doctrineRepo = doctrineRepo;
        this.queryRepo = queryRepo;
        this.eftParser = eftParser;
    }

    @Transactional(readOnly = true)
    public List<String> doctrineNames() {
        return doctrineRepo.findAll().stream()
                .map(FleetDoctrine::getDoctrineName)
                .filter(Objects::nonNull)
                .filter(n -> !n.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    // ==================================================================
    // 1. Das Kombinierte Readiness-Board
    // ==================================================================
    @Transactional(readOnly = true)
    public ReadinessDtos.DoctrineReadinessDto checkReadiness(String doctrineName) {
        List<ReadinessDtos.HullDto> hulls = doctrineHulls(doctrineName);
        Roster roster = loadRoster();

        if (hulls.isEmpty()) {
            return new ReadinessDtos.DoctrineReadinessDto(doctrineName, roster.accounts.size(), 0, List.of());
        }

        List<Long> typeIds = hulls.stream().map(ReadinessDtos.HullDto::typeId).toList();
        Map<Long, Map<Long, Long>> ownership = loadOwnership(typeIds);
        SkillContext ctx = loadSkillContext(typeIds);

        List<ReadinessDtos.HullReadinessDto> result = hulls.stream()
                .map(hull -> buildHullReadiness(hull, roster, ownership, ctx))
                .toList();

        return new ReadinessDtos.DoctrineReadinessDto(doctrineName, roster.accounts.size(), result.size(), result);
    }

    private ReadinessDtos.HullReadinessDto buildHullReadiness(
            ReadinessDtos.HullDto hull, Roster roster,
            Map<Long, Map<Long, Long>> ownership, SkillContext ctx) {

        List<ReadinessDtos.RequiredSkillDto> required = ctx.requirements.getOrDefault(hull.typeId(), List.of());
        int skillsRequired = required.size();

        List<ReadinessDtos.AccountReadinessDto> ready = new ArrayList<>();
        List<ReadinessDtos.AccountReadinessDto> notReady = new ArrayList<>();
        long hullsTotal = 0;

        for (Account account : roster.accounts.values()) {
            long accountOwned = 0;
            int charactersOwning = 0;
            boolean accountCanFly = false;
            boolean accountAnySkillData = false;
            int pilotsCapable = 0;
            int bestMet = 0;

            List<ReadinessDtos.CharacterReadinessDto> characters = new ArrayList<>();

            for (CharacterRef ref : account.characters) {
                // Hangar Check
                long owned = ownership.getOrDefault(ref.characterId(), Map.of()).getOrDefault(hull.typeId(), 0L);
                if (owned > 0) charactersOwning++;
                accountOwned += owned;

                // Skill Check
                boolean hasData = ctx.charactersWithData.contains(ref.characterId());
                List<ReadinessDtos.MissingSkillDto> gaps = hasData
                        ? ctx.gaps.getOrDefault(ref.characterId(), Map.of()).getOrDefault(hull.typeId(), List.of())
                        : List.of();
                boolean canFly = hasData && gaps.isEmpty();
                int met = hasData ? skillsRequired - gaps.size() : 0;

                if (hasData) accountAnySkillData = true;
                if (canFly) { accountCanFly = true; pilotsCapable++; }
                if (hasData && met > bestMet) bestMet = met;

                characters.add(new ReadinessDtos.CharacterReadinessDto(
                        ref.characterId(), ref.name(), portrait(ref.characterId()), ref.main(),
                        owned, hasData, canFly, met, skillsRequired, gaps));
            }

            hullsTotal += accountOwned;
            boolean hasShip = accountOwned > 0;
            boolean hasSkills = accountCanFly;
            boolean isReady = hasShip && hasSkills;

            // Sortierung der Charaktere intern (Die besten nach oben)
            characters.sort(Comparator
                    .comparing((ReadinessDtos.CharacterReadinessDto c) -> c.owned() > 0 && c.canFly()).reversed()
                    .thenComparing(c -> c.owned() > 0).reversed()
                    .thenComparing(ReadinessDtos.CharacterReadinessDto::canFly).reversed()
                    .thenComparing(ReadinessDtos.CharacterReadinessDto::characterName, String.CASE_INSENSITIVE_ORDER));

            ReadinessDtos.AccountReadinessDto row = new ReadinessDtos.AccountReadinessDto(
                    account.mainId, account.mainName, portrait(account.mainId), account.corporationName,
                    accountOwned, charactersOwning, accountCanFly, pilotsCapable, accountAnySkillData,
                    bestMet, skillsRequired, hasShip, hasSkills, isReady, characters);

            if (isReady) ready.add(row);
            else notReady.add(row);
        }

        ready.sort(Comparator.comparing(ReadinessDtos.AccountReadinessDto::mainName, String.CASE_INSENSITIVE_ORDER));

        // Sortierung Not-Ready: Die, die am nächsten dran sind, stehen oben (Haben Schiff -> Haben Skills)
        notReady.sort(Comparator
                .comparing(ReadinessDtos.AccountReadinessDto::hasShip).reversed()
                .thenComparing(ReadinessDtos.AccountReadinessDto::hasSkills).reversed()
                .thenComparingInt(ReadinessDtos.AccountReadinessDto::bestSkillsMet).reversed()
                .thenComparing(ReadinessDtos.AccountReadinessDto::mainName, String.CASE_INSENSITIVE_ORDER));

        int accountsTotal = roster.accounts.size();
        double coverage = accountsTotal == 0 ? 0d : (double) ready.size() / accountsTotal;

        return new ReadinessDtos.HullReadinessDto(
                hull.typeId(), hull.typeName(), hull.iconUrl(), hull.renderUrl(), required,
                hullsTotal, ready.size(), accountsTotal, coverage, ready, notReady);
    }

    // ==================================================================
    // 2. EFT-Sandbox
    // ==================================================================
    @Transactional(readOnly = true)
    public ReadinessDtos.SandboxResultDto sandbox(String eftString) {
        ReadinessDtos.ParsedFitDto fit = eftParser.parseAndResolve(eftString);
        ReadinessDtos.HullDto hull = new ReadinessDtos.HullDto(
                fit.shipTypeId(), fit.shipTypeName(), fit.iconUrl(), fit.renderUrl(),
                fit.fitName() != null ? List.of(fit.fitName()) : List.of());

        Roster roster = loadRoster();
        List<Long> typeIds = List.of(hull.typeId());

        ReadinessDtos.HullReadinessDto board = buildHullReadiness(
                hull, roster, loadOwnership(typeIds), loadSkillContext(typeIds));

        return new ReadinessDtos.SandboxResultDto(fit, board);
    }

    // ==================================================================
    // Helfer & DB Lader
    // ==================================================================
    private List<ReadinessDtos.HullDto> doctrineHulls(String doctrineName) {
        List<FleetDoctrine> fits = doctrineRepo.findAll().stream()
                .filter(d -> doctrineName == null || doctrineName.isBlank()
                        || doctrineName.equalsIgnoreCase(d.getDoctrineName()))
                .toList();

        Map<String, Long> resolvedByName = resolveMissingShipTypeIds(fits);
        Map<Long, String> names = new LinkedHashMap<>();
        Map<Long, List<String>> fitNames = new LinkedHashMap<>();

        for (FleetDoctrine fit : fits) {
            Long typeId = fit.getShipTypeId();
            if (typeId == null && fit.getShipType() != null) {
                typeId = resolvedByName.get(fit.getShipType().toLowerCase(Locale.ROOT));
            }
            if (typeId == null) continue;

            names.putIfAbsent(typeId, fit.getShipType() != null ? fit.getShipType() : ("Typ " + typeId));
            if (fit.getName() != null && !fit.getName().isBlank()) {
                fitNames.computeIfAbsent(typeId, k -> new ArrayList<>()).add(fit.getName());
            }
        }

        return names.entrySet().stream()
                .map(e -> new ReadinessDtos.HullDto(
                        e.getKey(), e.getValue(),
                        EftParserService.icon(e.getKey()), EftParserService.render(e.getKey()),
                        fitNames.getOrDefault(e.getKey(), List.of())))
                .sorted(Comparator.comparing(ReadinessDtos.HullDto::typeName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private Map<String, Long> resolveMissingShipTypeIds(List<FleetDoctrine> fits) {
        List<String> unresolved = fits.stream()
                .filter(d -> d.getShipTypeId() == null)
                .map(FleetDoctrine::getShipType)
                .filter(Objects::nonNull)
                .map(n -> n.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        if (unresolved.isEmpty()) return Map.of();
        Map<String, Long> resolved = new HashMap<>();
        for (Tuple t : queryRepo.resolveTypesByName(unresolved)) {
            resolved.put(str(t, "lookup"), lng(t, "typeId"));
        }
        return resolved;
    }

    private Map<Long, Map<Long, Long>> loadOwnership(List<Long> typeIds) {
        Map<Long, Map<Long, Long>> ownership = new HashMap<>();
        for (Tuple t : queryRepo.hullOwnership(typeIds)) {
            ownership.computeIfAbsent(lng(t, "characterId"), k -> new HashMap<>())
                    .merge(lng(t, "typeId"), lng(t, "quantity"), Long::sum);
        }
        return ownership;
    }

    private SkillContext loadSkillContext(List<Long> typeIds) {
        Map<Long, List<ReadinessDtos.RequiredSkillDto>> requirements = new LinkedHashMap<>();
        for (Tuple t : queryRepo.skillRequirements(typeIds)) {
            requirements.computeIfAbsent(lng(t, "typeId"), k -> new ArrayList<>())
                    .add(new ReadinessDtos.RequiredSkillDto(
                            lng(t, "skillTypeId"),
                            Optional.ofNullable(str(t, "skillName")).orElse("Skill " + lng(t, "skillTypeId")),
                            lng(t, "requiredLevel").intValue()));
        }

        Map<Long, Map<Long, List<ReadinessDtos.MissingSkillDto>>> gaps = new HashMap<>();
        for (Tuple t : queryRepo.skillGaps(typeIds)) {
            gaps.computeIfAbsent(lng(t, "characterId"), k -> new HashMap<>())
                    .computeIfAbsent(lng(t, "typeId"), k -> new ArrayList<>())
                    .add(new ReadinessDtos.MissingSkillDto(
                            lng(t, "skillTypeId"),
                            Optional.ofNullable(str(t, "skillName")).orElse("Skill " + lng(t, "skillTypeId")),
                            lng(t, "requiredLevel").intValue(),
                            lng(t, "currentLevel").intValue()));
        }

        return new SkillContext(requirements, gaps, new HashSet<>(queryRepo.charactersWithSkillData()));
    }

    private record SkillContext(
            Map<Long, List<ReadinessDtos.RequiredSkillDto>> requirements,
            Map<Long, Map<Long, List<ReadinessDtos.MissingSkillDto>>> gaps,
            Set<Long> charactersWithData
    ) {}

    private Roster loadRoster() {
        Map<Long, Account> accounts = new LinkedHashMap<>();
        for (Tuple t : queryRepo.accountRoster()) {
            Long mainId = lng(t, "mainId");
            Long characterId = lng(t, "characterId");
            Account account = accounts.computeIfAbsent(mainId, k ->
                    new Account(mainId, str(t, "mainName"), str(t, "corporationName")));
            account.characters.add(new CharacterRef(
                    characterId, str(t, "characterName"), Objects.equals(characterId, mainId)));
        }
        return new Roster(accounts);
    }

    private record Roster(Map<Long, Account> accounts) {}
    private record CharacterRef(Long characterId, String name, boolean main) {}

    private static final class Account {
        final Long mainId;
        final String mainName;
        final String corporationName;
        final List<CharacterRef> characters = new ArrayList<>();
        Account(Long mainId, String mainName, String corporationName) {
            this.mainId = mainId;
            this.mainName = mainName;
            this.corporationName = corporationName;
        }
    }

    private static String portrait(Long characterId) {
        return "https://images.evetech.net/characters/" + characterId + "/portrait?size=64";
    }

    private static Long lng(Tuple t, String alias) {
        Object v = t.get(alias);
        return v == null ? 0L : ((Number) v).longValue();
    }

    private static String str(Tuple t, String alias) {
        Object v = t.get(alias);
        return v == null ? null : String.valueOf(v);
    }
}