package com.eve.own.auth.backend.domain.fleet.service;

import com.eve.own.auth.backend.common.EveImageUrls;
import com.eve.own.auth.backend.domain.fleet.dto.ReadinessDtos;
import com.eve.own.auth.backend.domain.fleet.entity.FleetDoctrine;
import com.eve.own.auth.backend.domain.fleet.repository.FleetDoctrineRepository;
import com.eve.own.auth.backend.domain.fleet.repository.ReadinessQueryRepository;
import jakarta.persistence.Tuple;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Beantwortet: wer kann diese Doktrin heute wirklich stellen?
 *
 * <p>Geprueft wird das vollstaendige Fit. Der Hangar-Check gilt der Huelle -
 * Module sind Verbrauchsgut und werden nachgekauft, ein Schiff nicht. Der
 * Skill-Check dagegen umfasst Rumpf, Module, Drohnen und Ladung: wer die
 * Voraussetzungen eines verbauten Moduls nicht erfuellt, bekommt es nicht
 * online und ist mit dem Fit nutzlos, auch wenn er das Schiff fliegen kann.</p>
 *
 * <p>Deshalb ist die Pruefeinheit der Fit und nicht die Huelle. Zwei Fits
 * desselben Schiffs stellen unterschiedliche Anforderungen und stehen darum
 * getrennt im Board.</p>
 */
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
                .filter(name -> !name.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    // ==================================================================
    // 1. Das kombinierte Readiness-Board
    // ==================================================================

    @Transactional(readOnly = true)
    public ReadinessDtos.DoctrineReadinessDto checkReadiness(String doctrineName) {
        List<FitSpec> fits = doctrineFits(doctrineName);
        Roster roster = loadRoster();

        if (fits.isEmpty()) {
            return new ReadinessDtos.DoctrineReadinessDto(
                    doctrineName, roster.accounts.size(), 0, List.of());
        }

        // Huellen und skillrelevante Typen getrennt: der Hangar interessiert
        // sich nur fuer das Schiff, der Skill-Check fuer alles im Fit.
        List<Long> hullIds = fits.stream().map(FitSpec::hullTypeId).distinct().toList();
        List<Long> skillTypeIds = fits.stream()
                .flatMap(fit -> fit.skillRelevantTypeIds().stream())
                .distinct()
                .toList();

        Map<Long, Map<Long, Long>> ownership = loadOwnership(hullIds);
        SkillContext context = loadSkillContext(skillTypeIds);

        List<ReadinessDtos.FitReadinessDto> result = fits.stream()
                .map(fit -> buildFitReadiness(fit, roster, ownership, context))
                .toList();

        return new ReadinessDtos.DoctrineReadinessDto(
                doctrineName, roster.accounts.size(), result.size(), result);
    }

    private ReadinessDtos.FitReadinessDto buildFitReadiness(
            FitSpec fit, Roster roster,
            Map<Long, Map<Long, Long>> ownership, SkillContext context) {

        List<ReadinessDtos.RequiredSkillDto> required =
                context.requirementsOf(fit.skillRelevantTypeIds());
        int skillsRequired = required.size();
        int hullSkillsRequired = context.requirementsOf(List.of(fit.hullTypeId())).size();

        List<ReadinessDtos.AccountReadinessDto> ready = new ArrayList<>();
        List<ReadinessDtos.AccountReadinessDto> notReady = new ArrayList<>();
        long hullsTotal = 0;

        for (Account account : roster.accounts.values()) {
            long accountOwned = 0;
            int charactersOwning = 0;
            boolean accountCanFly = false;
            boolean accountAnySkillData = false;
            int pilotsCapable = 0;
            int pilotsReady = 0;
            int bestMet = 0;

            List<ReadinessDtos.CharacterReadinessDto> characters = new ArrayList<>();

            for (CharacterRef ref : account.characters) {
                long owned = ownership.getOrDefault(ref.characterId(), Map.of())
                        .getOrDefault(fit.hullTypeId(), 0L);
                if (owned > 0) charactersOwning++;
                accountOwned += owned;

                boolean hasData = context.charactersWithData.contains(ref.characterId());
                List<ReadinessDtos.MissingSkillDto> gaps = hasData
                        ? context.gapsOf(ref.characterId(), fit.skillRelevantTypeIds())
                        : List.of();
                boolean canFly = hasData && gaps.isEmpty();
                boolean canFlyHull = hasData
                        && context.gapsOf(ref.characterId(), List.of(fit.hullTypeId())).isEmpty();
                int met = hasData ? skillsRequired - gaps.size() : 0;

                if (hasData) accountAnySkillData = true;
                if (canFly) {
                    accountCanFly = true;
                    pilotsCapable++;
                    // Nur dieser Zaehler entscheidet ueber die Bereitschaft:
                    // Schiff und Skills muessen bei demselben Charakter liegen.
                    if (owned > 0) pilotsReady++;
                }
                if (hasData && met > bestMet) bestMet = met;

                characters.add(new ReadinessDtos.CharacterReadinessDto(
                        ref.characterId(), ref.name(), EveImageUrls.portrait(ref.characterId()), ref.main(),
                        owned, hasData, canFly, canFlyHull, met, skillsRequired, gaps));
            }

            hullsTotal += accountOwned;
            boolean hasShip = accountOwned > 0;

            /*
             * Bereit ist ein Account nur, wenn ein und derselbe Charakter das
             * Schiff hat UND den Fit fliegen kann. Zuvor wurden hier zwei
             * unabhaengige Oder-Verknuepfungen kombiniert: ein Alt mit den
             * Skills und ein Main mit dem Schiff ergaben zusammen "bereit",
             * obwohl keiner der beiden undocken kann. Der strengere
             * Skill-Check macht genau diesen Fall haeufiger.
             */
            boolean isReady = pilotsReady > 0;

            /*
             * Die aussichtsreichsten Charaktere nach oben.
             *
             * Umkehrung je Kriterium statt am Ende der Kette: ein
             * angehaengtes reversed() dreht den gesamten bis dahin
             * aufgebauten Vergleich um, nicht nur das zuletzt genannte
             * Kriterium. Bei mehreren Kriterien hebt sich das abwechselnd auf.
             */
            characters.sort(Comparator
                    .comparing((ReadinessDtos.CharacterReadinessDto c) -> c.owned() > 0 && c.canFly(),
                            Comparator.reverseOrder())
                    .thenComparing(c -> c.owned() > 0, Comparator.reverseOrder())
                    .thenComparing(ReadinessDtos.CharacterReadinessDto::canFly, Comparator.reverseOrder())
                    .thenComparing(ReadinessDtos.CharacterReadinessDto::characterName, String.CASE_INSENSITIVE_ORDER));

            ReadinessDtos.AccountReadinessDto row = new ReadinessDtos.AccountReadinessDto(
                    account.mainId, account.mainName, EveImageUrls.portrait(account.mainId),
                    account.corporationName,
                    accountOwned, charactersOwning, accountCanFly, pilotsCapable, accountAnySkillData,
                    bestMet, skillsRequired, hasShip, accountCanFly, isReady, characters);

            if (isReady) ready.add(row);
            else notReady.add(row);
        }

        ready.sort(Comparator.comparing(
                ReadinessDtos.AccountReadinessDto::mainName, String.CASE_INSENSITIVE_ORDER));

        // Wer am naechsten dran ist, steht oben: erst Schiff, dann Skills.
        // Umkehrung je Kriterium, siehe die Sortierung der Charaktere oben.
        notReady.sort(Comparator
                .comparing(ReadinessDtos.AccountReadinessDto::hasShip, Comparator.reverseOrder())
                .thenComparing(ReadinessDtos.AccountReadinessDto::hasSkills, Comparator.reverseOrder())
                .thenComparing(ReadinessDtos.AccountReadinessDto::bestSkillsMet, Comparator.reverseOrder())
                .thenComparing(ReadinessDtos.AccountReadinessDto::mainName, String.CASE_INSENSITIVE_ORDER));

        int accountsTotal = roster.accounts.size();
        double coverage = accountsTotal == 0 ? 0d : (double) ready.size() / accountsTotal;

        return new ReadinessDtos.FitReadinessDto(
                fit.fitId(), fit.fitName(),
                fit.hullTypeId(), fit.hullTypeName(),
                EftParserService.icon(fit.hullTypeId()), EftParserService.render(fit.hullTypeId()),
                fit.moduleCount(), required, hullSkillsRequired, fit.unresolved(),
                hullsTotal, ready.size(), accountsTotal, coverage, ready, notReady);
    }

    // ==================================================================
    // 2. EFT-Sandbox
    // ==================================================================

    /**
     * Prueft ein frei eingefuegtes Fitting gegen die Mannschaft.
     *
     * <p>Dieselbe Auswertung wie im Board - der Fit kommt nur aus dem
     * Textfeld statt aus der Doktrin.</p>
     */
    @Transactional(readOnly = true)
    public ReadinessDtos.SandboxResultDto sandbox(String eftString) {
        ReadinessDtos.ParsedFitDto fit = eftParser.parseAndResolve(eftString);
        FitSpec spec = specOf(null, fit.fitName(), fit);

        Roster roster = loadRoster();
        ReadinessDtos.FitReadinessDto board = buildFitReadiness(
                spec, roster,
                loadOwnership(List.of(spec.hullTypeId())),
                loadSkillContext(spec.skillRelevantTypeIds()));

        return new ReadinessDtos.SandboxResultDto(fit, board);
    }

    // ==================================================================
    // Die Fits einer Doktrin
    // ==================================================================

    /** Ein zu pruefender Fit: die Huelle und alles, was Skills verlangt. */
    private record FitSpec(Long fitId, String fitName, Long hullTypeId, String hullTypeName,
                           int moduleCount, List<Long> moduleTypeIds, List<String> unresolved) {

        /** Huelle und Module zusammen - die Grundlage des Skill-Checks. */
        List<Long> skillRelevantTypeIds() {
            List<Long> all = new ArrayList<>(moduleTypeIds.size() + 1);
            all.add(hullTypeId);
            all.addAll(moduleTypeIds);
            return all;
        }
    }

    private List<FitSpec> doctrineFits(String doctrineName) {
        List<FleetDoctrine> rows = doctrineRepo.findAll().stream()
                .filter(row -> doctrineName == null || doctrineName.isBlank()
                        || doctrineName.equalsIgnoreCase(row.getDoctrineName()))
                .toList();

        Map<String, Long> resolvedByName = resolveMissingShipTypeIds(rows);

        List<FitSpec> fits = new ArrayList<>();
        for (FleetDoctrine row : rows) {
            FitSpec fit = toFitSpec(row, resolvedByName);
            if (fit != null) fits.add(fit);
        }

        fits.sort(Comparator
                .comparing(FitSpec::hullTypeName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(fit -> Optional.ofNullable(fit.fitName()).orElse(""),
                        String.CASE_INSENSITIVE_ORDER));
        return fits;
    }

    /**
     * Macht aus einem gespeicherten Doktrin-Eintrag einen pruefbaren Fit.
     *
     * <p>Laesst sich der EFT-Text nicht lesen, faellt die Pruefung auf die
     * Huelle zurueck statt den Fit fallen zu lassen: ein unlesbares Fitting
     * darf ein Schiff nicht aus der Doktrin verschwinden lassen. Der Grund
     * steht dann in {@code unresolved} und ist im Board sichtbar.</p>
     *
     * @return {@code null}, wenn nicht einmal die Huelle bestimmbar ist
     */
    private FitSpec toFitSpec(FleetDoctrine row, Map<String, Long> resolvedByName) {
        String fitName = row.getName() != null && !row.getName().isBlank() ? row.getName() : null;

        if (row.getEftString() != null && !row.getEftString().isBlank()) {
            try {
                ReadinessDtos.ParsedFitDto parsed = eftParser.parseAndResolve(row.getEftString());
                return specOf(row.getId(), fitName != null ? fitName : parsed.fitName(), parsed);
            } catch (IllegalArgumentException e) {
                // Bewusst nur diese: der Parser meldet damit unbrauchbaren Text.
                // Ein weiteres Netz wuerde auch einen Datenbankfehler abfangen
                // und stillschweigend ein zu mildes Ergebnis liefern.
                log.warn("Fitting \"{}\" der Doktrin \"{}\" ist nicht lesbar, es wird nur die "
                                + "Huelle geprueft: {}",
                        fitName, row.getDoctrineName(), e.getMessage());
                return hullOnlySpec(row, resolvedByName, fitName,
                        "Fitting nicht lesbar, nur der Rumpf wurde geprueft - " + e.getMessage());
            }
        }
        return hullOnlySpec(row, resolvedByName, fitName, null);
    }

    private FitSpec specOf(Long fitId, String fitName, ReadinessDtos.ParsedFitDto parsed) {
        // LinkedHashSet: derselbe Modultyp steckt oft mehrfach im Fit, fuer den
        // Skill-Check zaehlt er einmal - die Reihenfolge bleibt nachvollziehbar.
        Set<Long> moduleTypeIds = new LinkedHashSet<>();
        for (ReadinessDtos.FitSlotGroupDto group : parsed.groups()) {
            for (ReadinessDtos.FitModuleDto module : group.modules()) {
                if (module.typeId() != null) moduleTypeIds.add(module.typeId());
                if (module.chargeTypeId() != null) moduleTypeIds.add(module.chargeTypeId());
            }
        }
        return new FitSpec(fitId, fitName, parsed.shipTypeId(), parsed.shipTypeName(),
                parsed.moduleCount(), List.copyOf(moduleTypeIds), parsed.unresolved());
    }

    private FitSpec hullOnlySpec(FleetDoctrine row, Map<String, Long> resolvedByName,
                                 String fitName, String reason) {
        Long typeId = row.getShipTypeId();
        if (typeId == null && row.getShipType() != null) {
            typeId = resolvedByName.get(row.getShipType().toLowerCase(Locale.ROOT));
        }
        if (typeId == null) return null;

        String typeName = row.getShipType() != null ? row.getShipType() : "Typ " + typeId;
        List<String> unresolved = reason != null ? List.of(reason) : List.of();
        return new FitSpec(row.getId(), fitName, typeId, typeName, 0, List.of(), unresolved);
    }

    private Map<String, Long> resolveMissingShipTypeIds(List<FleetDoctrine> fits) {
        List<String> unresolved = fits.stream()
                .filter(row -> row.getShipTypeId() == null)
                .map(FleetDoctrine::getShipType)
                .filter(Objects::nonNull)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        if (unresolved.isEmpty()) return Map.of();

        Map<String, Long> resolved = new HashMap<>();
        for (Tuple tuple : queryRepo.resolveTypesByName(unresolved)) {
            resolved.put(str(tuple, "lookup"), lng(tuple, "typeId"));
        }
        return resolved;
    }

    // ==================================================================
    // Datenbank-Lader
    // ==================================================================

    private Map<Long, Map<Long, Long>> loadOwnership(List<Long> typeIds) {
        Map<Long, Map<Long, Long>> ownership = new HashMap<>();
        for (Tuple tuple : queryRepo.hullOwnership(typeIds)) {
            ownership.computeIfAbsent(lng(tuple, "characterId"), key -> new HashMap<>())
                    .merge(lng(tuple, "typeId"), lng(tuple, "quantity"), Long::sum);
        }
        return ownership;
    }

    private SkillContext loadSkillContext(List<Long> typeIds) {
        Map<Long, List<ReadinessDtos.RequiredSkillDto>> requirements = new LinkedHashMap<>();
        for (Tuple tuple : queryRepo.skillRequirements(typeIds)) {
            requirements.computeIfAbsent(lng(tuple, "typeId"), key -> new ArrayList<>())
                    .add(new ReadinessDtos.RequiredSkillDto(
                            lng(tuple, "skillTypeId"),
                            Optional.ofNullable(str(tuple, "skillName"))
                                    .orElse("Skill " + lng(tuple, "skillTypeId")),
                            lng(tuple, "requiredLevel").intValue()));
        }

        Map<Long, Map<Long, List<ReadinessDtos.MissingSkillDto>>> gaps = new HashMap<>();
        for (Tuple tuple : queryRepo.skillGaps(typeIds)) {
            gaps.computeIfAbsent(lng(tuple, "characterId"), key -> new HashMap<>())
                    .computeIfAbsent(lng(tuple, "typeId"), key -> new ArrayList<>())
                    .add(new ReadinessDtos.MissingSkillDto(
                            lng(tuple, "skillTypeId"),
                            Optional.ofNullable(str(tuple, "skillName"))
                                    .orElse("Skill " + lng(tuple, "skillTypeId")),
                            lng(tuple, "requiredLevel").intValue(),
                            lng(tuple, "currentLevel").intValue()));
        }

        return new SkillContext(requirements, gaps, new HashSet<>(queryRepo.charactersWithSkillData()));
    }

    /**
     * Anforderungen und Luecken, aufgeschluesselt nach Typ.
     *
     * <p>Die Datenbank liefert sie je Typ; ein Fit besteht aber aus vielen
     * Typen, die sich Skills teilen. Ein Raketenschiff verlangt Missile
     * Launcher Operation II, seine Werfer verlangen dieselbe Faehigkeit
     * womoeglich auf Stufe IV. Zusammengefuehrt wird deshalb hier - und zwar
     * mit der jeweils <em>hoechsten</em> Anforderung, sonst waere der Check zu
     * milde.</p>
     */
    private record SkillContext(
            Map<Long, List<ReadinessDtos.RequiredSkillDto>> requirements,
            Map<Long, Map<Long, List<ReadinessDtos.MissingSkillDto>>> gaps,
            Set<Long> charactersWithData
    ) {

        /** Was diese Typen zusammen verlangen, je Skill die hoechste Stufe. */
        List<ReadinessDtos.RequiredSkillDto> requirementsOf(Collection<Long> typeIds) {
            Map<Long, ReadinessDtos.RequiredSkillDto> highest = new LinkedHashMap<>();
            for (Long typeId : typeIds) {
                for (ReadinessDtos.RequiredSkillDto skill : requirements.getOrDefault(typeId, List.of())) {
                    highest.merge(skill.skillTypeId(), skill,
                            (existing, candidate) -> existing.level() >= candidate.level()
                                    ? existing : candidate);
                }
            }
            return highest.values().stream()
                    .sorted(Comparator.comparing(ReadinessDtos.RequiredSkillDto::skillName,
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }

        /** Was diesem Charakter fuer diese Typen fehlt, je Skill die hoechste Stufe. */
        List<ReadinessDtos.MissingSkillDto> gapsOf(Long characterId, Collection<Long> typeIds) {
            Map<Long, List<ReadinessDtos.MissingSkillDto>> byType =
                    gaps.getOrDefault(characterId, Map.of());

            Map<Long, ReadinessDtos.MissingSkillDto> highest = new LinkedHashMap<>();
            for (Long typeId : typeIds) {
                for (ReadinessDtos.MissingSkillDto missing : byType.getOrDefault(typeId, List.of())) {
                    highest.merge(missing.skillTypeId(), missing,
                            (existing, candidate) -> existing.requiredLevel() >= candidate.requiredLevel()
                                    ? existing : candidate);
                }
            }
            return highest.values().stream()
                    .sorted(Comparator.comparing(ReadinessDtos.MissingSkillDto::skillName,
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    private Roster loadRoster() {
        Map<Long, Account> accounts = new LinkedHashMap<>();
        for (Tuple tuple : queryRepo.accountRoster()) {
            Long mainId = lng(tuple, "mainId");
            Long characterId = lng(tuple, "characterId");
            Account account = accounts.computeIfAbsent(mainId, key ->
                    new Account(mainId, str(tuple, "mainName"), str(tuple, "corporationName")));
            account.characters.add(new CharacterRef(
                    characterId, str(tuple, "characterName"), Objects.equals(characterId, mainId)));
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

    private static Long lng(Tuple tuple, String alias) {
        Object value = tuple.get(alias);
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static String str(Tuple tuple, String alias) {
        Object value = tuple.get(alias);
        return value == null ? null : String.valueOf(value);
    }
}
