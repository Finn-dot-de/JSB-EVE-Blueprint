package com.eve.own.auth.backend.domain.fleet.service;

import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.fleet.dto.SkillPlanDtos;
import com.eve.own.auth.backend.domain.fleet.entity.DoctrineSkillPlan;
import com.eve.own.auth.backend.domain.fleet.entity.SkillPlan;
import com.eve.own.auth.backend.domain.fleet.entity.SkillPlanEntry;
import com.eve.own.auth.backend.domain.fleet.repository.DoctrineSkillPlanRepository;
import com.eve.own.auth.backend.domain.fleet.repository.ReadinessQueryRepository;
import com.eve.own.auth.backend.domain.fleet.repository.SkillPlanEntryRepository;
import com.eve.own.auth.backend.domain.fleet.repository.SkillPlanRepository;
import jakarta.persistence.Tuple;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verwaltet die Skillplaene und ihre Zuordnung zu Fittings.
 *
 * <p>Ein Skillplan ergaenzt, was die Stammdaten nicht hergeben. Die
 * Voraussetzungen eines Moduls sagen nur, ob es sich einschalten laesst -
 * nicht, ob der Pilot damit etwas ausrichtet. Reaktoren, Kondensator und
 * Schild gehoeren zu keinem Modul und tauchen in keiner Voraussetzung auf,
 * entscheiden aber darueber, ob ein Fitting ueberhaupt laeuft.</p>
 */
@Service
public class SkillPlanService {

    /** Eine Zeile eines eingefuegten Plans: Name, dahinter die Stufe. */
    private static final Pattern PLAN_LINE =
            Pattern.compile("^(.*?)[\\s:]+([1-5]|I{1,3}|IV|V)$", Pattern.CASE_INSENSITIVE);

    private static final Map<String, Integer> ROMAN_LEVELS = Map.of(
            "I", 1, "II", 2, "III", 3, "IV", 4, "V", 5);

    /** Ohne Stufenangabe gilt die hoechste - ein Plan meint fast immer "auf V". */
    private static final int DEFAULT_LEVEL = 5;

    private static final int MAX_LEVEL = 5;
    private static final int MIN_LEVEL = 1;

    private final SkillPlanRepository planRepo;
    private final SkillPlanEntryRepository entryRepo;
    private final DoctrineSkillPlanRepository linkRepo;
    private final ReadinessQueryRepository queryRepo;
    private final CharacterRepository characterRepo;

    public SkillPlanService(SkillPlanRepository planRepo,
                            SkillPlanEntryRepository entryRepo,
                            DoctrineSkillPlanRepository linkRepo,
                            ReadinessQueryRepository queryRepo,
                            CharacterRepository characterRepo) {
        this.planRepo = planRepo;
        this.entryRepo = entryRepo;
        this.linkRepo = linkRepo;
        this.queryRepo = queryRepo;
        this.characterRepo = characterRepo;
    }

    // ==================================================================
    // Lesen
    // ==================================================================

    @Transactional(readOnly = true)
    public List<SkillPlanDtos.SkillPlanDto> list() {
        List<SkillPlan> plans = planRepo.findAll();
        if (plans.isEmpty()) {
            return List.of();
        }

        Map<Long, List<SkillPlanDtos.SkillEntryDto>> entriesByPlan = entriesByPlan(
                plans.stream().map(SkillPlan::getId).toList());
        Map<Long, Integer> usage = new HashMap<>();
        for (DoctrineSkillPlan link : linkRepo.findAll()) {
            usage.merge(link.getPlanId(), 1, Integer::sum);
        }

        return plans.stream()
                .map(plan -> new SkillPlanDtos.SkillPlanDto(
                        plan.getId(), plan.getName(), plan.getDescription(),
                        entriesByPlan.getOrDefault(plan.getId(), List.of()),
                        usage.getOrDefault(plan.getId(), 0)))
                .sorted(Comparator.comparing(SkillPlanDtos.SkillPlanDto::name,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Skill-Vorschlaege fuer die Auswahl beim Zusammenstellen eines Plans. */
    @Transactional(readOnly = true)
    public List<SkillPlanDtos.SkillOptionDto> searchSkills(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return queryRepo.searchSkills(query.trim(), limit).stream()
                .map(tuple -> new SkillPlanDtos.SkillOptionDto(
                        lng(tuple, "typeId"), str(tuple, "typeName")))
                .toList();
    }

    // ==================================================================
    // Schreiben
    // ==================================================================

    /**
     * Legt einen Plan an oder schreibt ihn fort.
     *
     * <p>Die Eintraege werden komplett ersetzt: das Formular schickt immer den
     * vollstaendigen Stand, und ein Abgleich Zeile fuer Zeile brächte hier
     * nichts ausser Gelegenheiten, etwas zu uebersehen.</p>
     *
     * @throws IllegalArgumentException bei leerem Namen oder belegtem Namen
     */
    @Transactional
    public SkillPlanDtos.SkillPlanDto save(Long editorCharacterId, SkillPlanDtos.SaveSkillPlanDto dto) {
        String name = dto.name() == null ? "" : dto.name().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Der Plan braucht einen Namen.");
        }

        Optional<SkillPlan> sameName = planRepo.findByNameIgnoreCase(name);
        if (sameName.isPresent() && !sameName.get().getId().equals(dto.id())) {
            throw new IllegalArgumentException("Ein Plan namens \"" + name + "\" existiert bereits.");
        }

        SkillPlan plan = dto.id() == null
                ? newPlan(editorCharacterId)
                : planRepo.findById(dto.id()).orElseThrow(
                        () -> new IllegalArgumentException("Plan " + dto.id() + " ist unbekannt."));
        plan.setName(name);
        plan.setDescription(trimmedOrNull(dto.description()));
        SkillPlan saved = planRepo.save(plan);

        entryRepo.deleteByPlanId(saved.getId());
        List<SkillPlanDtos.SkillEntryDto> skills = normalize(dto.skills());
        for (SkillPlanDtos.SkillEntryDto skill : skills) {
            SkillPlanEntry entry = new SkillPlanEntry();
            entry.setPlanId(saved.getId());
            entry.setSkillTypeId(skill.skillTypeId());
            entry.setSkillName(skill.skillName());
            entry.setRequiredLevel(skill.level());
            entryRepo.save(entry);
        }

        return new SkillPlanDtos.SkillPlanDto(saved.getId(), saved.getName(), saved.getDescription(),
                skills, linkRepo.findAll().stream()
                        .filter(link -> link.getPlanId().equals(saved.getId()))
                        .toList().size());
    }

    /** Loescht einen Plan samt seiner Eintraege und aller Verknuepfungen. */
    @Transactional
    public void delete(Long planId) {
        if (!planRepo.existsById(planId)) {
            throw new IllegalArgumentException("Plan " + planId + " ist unbekannt.");
        }
        // Zuerst die Verknuepfungen: eine zurueckbleibende Zeile zeigte sonst
        // auf einen Plan, den es nicht mehr gibt.
        linkRepo.deleteByPlanId(planId);
        entryRepo.deleteByPlanId(planId);
        planRepo.deleteById(planId);
    }

    /** Legt fest, welche Plaene an einem Fitting haengen. */
    @Transactional
    public void assignToDoctrine(Long doctrineId, List<Long> planIds) {
        linkRepo.deleteByDoctrineId(doctrineId);
        if (planIds == null || planIds.isEmpty()) {
            return;
        }

        for (Long planId : new LinkedHashSet<>(planIds)) {
            if (!planRepo.existsById(planId)) {
                throw new IllegalArgumentException("Plan " + planId + " ist unbekannt.");
            }
            DoctrineSkillPlan link = new DoctrineSkillPlan();
            link.setDoctrineId(doctrineId);
            link.setPlanId(planId);
            linkRepo.save(link);
        }
    }

    // ==================================================================
    // Einfuegen eines fertigen Plantexts
    // ==================================================================

    /**
     * Liest einen eingefuegten Plantext ein, wie ihn EVE und EVEMon ausgeben.
     *
     * <p>Erkannt werden "Power Grid Management V" ebenso wie
     * "Power Grid Management 5". Fehlt die Stufe, gilt {@value #DEFAULT_LEVEL} -
     * ein Skillplan meint fast immer die hoechste Stufe, und die Zeile laesst
     * sich anschliessend im Formular jederzeit korrigieren.</p>
     */
    @Transactional(readOnly = true)
    public SkillPlanDtos.ImportResultDto importPlanText(String planText) {
        if (planText == null || planText.isBlank()) {
            return new SkillPlanDtos.ImportResultDto(List.of(), List.of());
        }

        // Reihenfolge der Eingabe bleibt erhalten, Dubletten fliegen raus.
        Map<String, Integer> levelByName = new LinkedHashMap<>();
        for (String rawLine : planText.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }

            Matcher matcher = PLAN_LINE.matcher(line);
            String skillName = matcher.matches() ? matcher.group(1).trim() : line;
            int level = matcher.matches() ? levelOf(matcher.group(2)) : DEFAULT_LEVEL;
            if (skillName.isEmpty()) {
                continue;
            }
            // Steht ein Skill mehrfach drin, gilt die hoechste Stufe.
            levelByName.merge(skillName, level, Math::max);
        }

        Map<String, Tuple> resolved = new HashMap<>();
        for (Tuple tuple : queryRepo.resolveSkillsByName(
                levelByName.keySet().stream().map(n -> n.toLowerCase(Locale.ROOT)).toList())) {
            resolved.put(str(tuple, "lookup"), tuple);
        }

        List<SkillPlanDtos.SkillEntryDto> skills = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        levelByName.forEach((skillName, level) -> {
            Tuple match = resolved.get(skillName.toLowerCase(Locale.ROOT));
            if (match == null) {
                unresolved.add(skillName);
            } else {
                skills.add(new SkillPlanDtos.SkillEntryDto(
                        lng(match, "typeId"), str(match, "typeName"), level));
            }
        });

        return new SkillPlanDtos.ImportResultDto(skills, unresolved);
    }

    // ==================================================================
    // Fuer den Readiness-Check
    // ==================================================================

    /**
     * Die Plaene eines Fittings, zu einer Anforderung zusammengefasst.
     *
     * @param planNames die Namen der beteiligten Plaene, fuer die Anzeige
     * @param skills je Skill die hoechste geforderte Stufe ueber alle Plaene
     */
    public record DoctrineSkillsDto(List<String> planNames, List<SkillPlanDtos.SkillEntryDto> skills) {}

    /**
     * Was die verknuepften Plaene je Fitting verlangen.
     *
     * <p>Traegt ein Fitting mehrere Plaene, die denselben Skill nennen, gilt
     * die hoechste Stufe - sonst waere die Anforderung von der Reihenfolge
     * der Verknuepfung abhaengig.</p>
     */
    @Transactional(readOnly = true)
    public Map<Long, DoctrineSkillsDto> skillsByDoctrine(Collection<Long> doctrineIds) {
        if (doctrineIds == null || doctrineIds.isEmpty()) {
            return Map.of();
        }

        List<DoctrineSkillPlan> links = linkRepo.findByDoctrineIdIn(doctrineIds);
        if (links.isEmpty()) {
            return Map.of();
        }

        Set<Long> planIds = links.stream().map(DoctrineSkillPlan::getPlanId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<Long, String> planNames = new HashMap<>();
        planRepo.findAllById(planIds).forEach(plan -> planNames.put(plan.getId(), plan.getName()));
        Map<Long, List<SkillPlanDtos.SkillEntryDto>> entriesByPlan = entriesByPlan(planIds);

        Map<Long, List<Long>> plansByDoctrine = new LinkedHashMap<>();
        for (DoctrineSkillPlan link : links) {
            plansByDoctrine.computeIfAbsent(link.getDoctrineId(), key -> new ArrayList<>())
                    .add(link.getPlanId());
        }

        Map<Long, DoctrineSkillsDto> result = new LinkedHashMap<>();
        plansByDoctrine.forEach((doctrineId, ids) -> {
            List<String> names = new ArrayList<>();
            Map<Long, SkillPlanDtos.SkillEntryDto> highest = new LinkedHashMap<>();
            for (Long planId : ids) {
                Optional.ofNullable(planNames.get(planId)).ifPresent(names::add);
                for (SkillPlanDtos.SkillEntryDto skill : entriesByPlan.getOrDefault(planId, List.of())) {
                    highest.merge(skill.skillTypeId(), skill,
                            (existing, candidate) -> existing.level() >= candidate.level()
                                    ? existing : candidate);
                }
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            result.put(doctrineId, new DoctrineSkillsDto(List.copyOf(names),
                    highest.values().stream()
                            .sorted(Comparator.comparing(SkillPlanDtos.SkillEntryDto::skillName,
                                    String.CASE_INSENSITIVE_ORDER))
                            .toList()));
        });
        return result;
    }

    // ==================================================================
    // Interna
    // ==================================================================

    private Map<Long, List<SkillPlanDtos.SkillEntryDto>> entriesByPlan(Collection<Long> planIds) {
        Map<Long, List<SkillPlanDtos.SkillEntryDto>> byPlan = new LinkedHashMap<>();
        for (SkillPlanEntry entry : entryRepo.findByPlanIdIn(planIds)) {
            byPlan.computeIfAbsent(entry.getPlanId(), key -> new ArrayList<>())
                    .add(new SkillPlanDtos.SkillEntryDto(
                            entry.getSkillTypeId(), entry.getSkillName(), entry.getRequiredLevel()));
        }
        byPlan.values().forEach(list -> list.sort(
                Comparator.comparing(SkillPlanDtos.SkillEntryDto::skillName, String.CASE_INSENSITIVE_ORDER)));
        return byPlan;
    }

    private SkillPlan newPlan(Long editorCharacterId) {
        SkillPlan plan = new SkillPlan();
        plan.setCreatedAt(Instant.now());
        plan.setCreatedBy(characterRepo.findById(editorCharacterId)
                .map(Character::getName)
                .orElse(null));
        return plan;
    }

    /** Dubletten raus, Stufen in den gueltigen Bereich, unbrauchbare Zeilen weg. */
    private static List<SkillPlanDtos.SkillEntryDto> normalize(List<SkillPlanDtos.SkillEntryDto> skills) {
        if (skills == null) {
            return List.of();
        }
        Map<Long, SkillPlanDtos.SkillEntryDto> highest = new LinkedHashMap<>();
        for (SkillPlanDtos.SkillEntryDto skill : skills) {
            if (skill == null || skill.skillTypeId() == null
                    || skill.skillName() == null || skill.skillName().isBlank()) {
                continue;
            }
            int level = Math.clamp(skill.level(), MIN_LEVEL, MAX_LEVEL);
            SkillPlanDtos.SkillEntryDto clean = new SkillPlanDtos.SkillEntryDto(
                    skill.skillTypeId(), skill.skillName().trim(), level);
            highest.merge(clean.skillTypeId(), clean,
                    (existing, candidate) -> existing.level() >= candidate.level()
                            ? existing : candidate);
        }
        return highest.values().stream()
                .sorted(Comparator.comparing(SkillPlanDtos.SkillEntryDto::skillName,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static int levelOf(String token) {
        String upper = token.toUpperCase(Locale.ROOT);
        Integer roman = ROMAN_LEVELS.get(upper);
        if (roman != null) {
            return roman;
        }
        try {
            return Math.clamp(Integer.parseInt(upper), MIN_LEVEL, MAX_LEVEL);
        } catch (NumberFormatException e) {
            return DEFAULT_LEVEL;
        }
    }

    private static String trimmedOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Long lng(Tuple tuple, String alias) {
        Object value = tuple.get(alias);
        return value == null ? null : ((Number) value).longValue();
    }

    private static String str(Tuple tuple, String alias) {
        Object value = tuple.get(alias);
        return value == null ? null : String.valueOf(value);
    }
}
