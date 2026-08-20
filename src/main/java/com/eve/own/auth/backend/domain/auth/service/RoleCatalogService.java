package com.eve.own.auth.backend.domain.auth.service;

import com.eve.own.auth.backend.domain.auth.AuthRoleSource;
import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.auth.entity.SystemRole;
import com.eve.own.auth.backend.domain.auth.entity.TitleRoleMapping;
import com.eve.own.auth.backend.domain.auth.repository.SystemRoleRepository;
import com.eve.own.auth.backend.domain.auth.repository.TitleRoleMappingRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fuehrt alle Rollen zusammen, die es in dieser Anwendung gibt.
 *
 * <p>Rollen entstehen an drei Stellen, und keine davon kennt die anderen: die
 * eingebauten stehen in {@link SystemRoles}, die selbst angelegten in
 * {@code system_roles}, und weitere entstehen laufend dadurch, dass
 * {@link CharacterRoleService} fuer einen neuen Ingame-Titel automatisch eine
 * Zuordnung anlegt. Wer wissen will, welche Rollen vergeben werden koennen,
 * muss alle drei Quellen befragen - genau das passiert hier einmal statt an
 * jeder Aufrufstelle erneut.</p>
 */
@Service
public class RoleCatalogService {

    /**
     * Was die eingebauten Rollen bedeuten.
     *
     * <p>Die Namen kommen aus {@link SystemRoles}, damit hier kein Literal steht,
     * das beim Umbenennen zurueckbleibt. Eine Rolle ohne Eintrag verschwindet
     * nicht, sie bekommt nur den Ersatztext.</p>
     */
    private static final Map<String, String> BUILT_IN_DESCRIPTIONS = Map.of(
            SystemRoles.USER, "Basis-Recht fuer alle angemeldeten Charaktere",
            // Nicht mehr "Mitglied einer zugelassenen Corporation": Diese Rolle
            // kommt seit der Umstellung ausschliesslich aus dem Ingame-Titel
            // "Member", nicht aus der blossen Zugehoerigkeit.
            SystemRoles.MEMBER, "Vollmitglied - kommt aus dem Ingame-Titel Member",
            SystemRoles.MARAUDERS, "Mitglied der Haupt-Corporation",
            SystemRoles.GUEST, "Angemeldet, aber in keiner zugelassenen Corporation",
            SystemRoles.CEO, "Fuehrung der Corporation",
            SystemRoles.DIRECTOR, "Fuehrung der Corporation",
            SystemRoles.IT_ADMIN, "Technische Administration");

    private static final String WITHOUT_DESCRIPTION = "Ohne Beschreibung";

    private static final String FROM_TITLE = "Automatisch aus einem Ingame-Titel entstanden";

    private final SystemRoleRepository systemRoleRepo;
    private final TitleRoleMappingRepository titleRepo;

    public RoleCatalogService(SystemRoleRepository systemRoleRepo,
                              TitleRoleMappingRepository titleRepo) {
        this.systemRoleRepo = systemRoleRepo;
        this.titleRepo = titleRepo;
    }

    /**
     * Eine Rolle samt Herkunft.
     *
     * @param special ob die Rolle eine Neuberechnung der Rollen ueberdauert, also
     *     von Hand vergeben bleibt statt aus Titel und Corp neu abgeleitet zu werden
     * @param grantingTitles die Ingame-Titel, die diese Rolle derzeit vergeben
     */
    public record AuthRoleDto(String name,
                              String description,
                              AuthRoleSource source,
                              boolean special,
                              List<String> grantingTitles) {}

    /** Alle bekannten Rollen, gruppiert nach Herkunft und darin alphabetisch. */
    @Transactional(readOnly = true)
    public List<AuthRoleDto> catalog() {
        Map<String, SystemRole> stored = new LinkedHashMap<>();
        systemRoleRepo.findAll().forEach(role -> stored.put(role.getRoleName(), role));

        Map<String, List<String>> titlesByRole = titlesByRole();
        Map<String, AuthRoleDto> catalog = new LinkedHashMap<>();

        for (String name : SystemRoles.builtIn()) {
            catalog.put(name, new AuthRoleDto(
                    name,
                    describe(stored.get(name), BUILT_IN_DESCRIPTIONS.get(name)),
                    AuthRoleSource.BUILT_IN,
                    isSpecial(stored.get(name)),
                    titlesByRole.getOrDefault(name, List.of())));
        }

        stored.forEach((name, role) -> catalog.computeIfAbsent(name, key -> new AuthRoleDto(
                key,
                describe(role, WITHOUT_DESCRIPTION),
                AuthRoleSource.CUSTOM,
                role.isSpecial(),
                titlesByRole.getOrDefault(key, List.of()))));

        titlesByRole.forEach((name, grantingTitles) -> catalog.computeIfAbsent(name,
                key -> new AuthRoleDto(
                        key, FROM_TITLE, AuthRoleSource.TITLE, false, grantingTitles)));

        return catalog.values().stream()
                .sorted(Comparator.comparing(AuthRoleDto::source).thenComparing(AuthRoleDto::name))
                .toList();
    }

    /** Nur die Namen, alphabetisch - fuer Aufrufer, die keine Herkunft brauchen. */
    @Transactional(readOnly = true)
    public List<String> roleNames() {
        return catalog().stream().map(AuthRoleDto::name).sorted().toList();
    }

    /**
     * Legt eine eigene Rolle an oder aendert ihre Beschreibung.
     *
     * <p>Eingebaute Rollen bleiben aussen vor. Sie sind im Code fest verdrahtet,
     * eine abweichende Beschreibung waere bestenfalls verwirrend - und ein
     * {@code special}-Vermerk darauf haette Folgen: er liesse eine einmal
     * vergebene Fuehrungsrolle jede Neuberechnung ueberdauern, auch wenn der
     * zugehoerige Ingame-Titel laengst weg ist.</p>
     *
     * @return die gespeicherte Rolle mit ihrem endgueltigen, normalisierten Namen
     * @throws IllegalArgumentException bei leerem Namen oder einer eingebauten Rolle
     */
    @Transactional
    public AuthRoleDto save(String rawName, String description, boolean special) {
        String roleName = SystemRoles.normalize(rawName);
        if (SystemRoles.isBuiltIn(roleName)) {
            throw new IllegalArgumentException(
                    roleName + " ist eine eingebaute Rolle und laesst sich nicht aendern.");
        }

        SystemRole role = systemRoleRepo.findById(roleName).orElseGet(SystemRole::new);
        role.setRoleName(roleName);
        role.setDescription(trimmedOrNull(description));
        role.setSpecial(special);
        systemRoleRepo.save(role);

        return new AuthRoleDto(
                roleName,
                describe(role, WITHOUT_DESCRIPTION),
                AuthRoleSource.CUSTOM,
                special,
                titlesByRole().getOrDefault(roleName, List.of()));
    }

    /**
     * Loescht eine selbst angelegte Rolle.
     *
     * <p>Vergibt noch ein Titel diese Rolle, bricht der Vorgang ab. Andernfalls
     * verschwaende die Rolle nur aus der Liste und wuerde vom naechsten Sync
     * trotzdem weiter verteilt - ein Recht, das niemand mehr sieht.</p>
     *
     * @throws IllegalArgumentException wenn die Rolle eingebaut, unbekannt oder noch in Gebrauch ist
     */
    @Transactional
    public void delete(String roleName) {
        if (SystemRoles.isBuiltIn(roleName)) {
            throw new IllegalArgumentException(
                    roleName + " ist eine eingebaute Rolle und laesst sich nicht loeschen.");
        }

        List<String> grantingTitles = titlesByRole().getOrDefault(roleName, List.of());
        if (!grantingTitles.isEmpty()) {
            throw new IllegalArgumentException(roleName + " wird noch von "
                    + String.join(", ", grantingTitles)
                    + " vergeben. Loese zuerst diese Zuordnung.");
        }
        if (!systemRoleRepo.existsById(roleName)) {
            throw new IllegalArgumentException("Die Rolle " + roleName + " ist unbekannt.");
        }
        systemRoleRepo.deleteById(roleName);
    }

    /** Welche Titel welche Rolle vergeben - Titel ohne Rolle zaehlen nicht mit. */
    private Map<String, List<String>> titlesByRole() {
        Map<String, List<String>> titlesByRole = new LinkedHashMap<>();
        for (TitleRoleMapping mapping : titleRepo.findAll()) {
            String roleName = mapping.getRoleName();
            if (roleName == null || roleName.isBlank()) {
                continue;
            }
            titlesByRole.computeIfAbsent(roleName, key -> new ArrayList<>())
                    .add(titleNameOf(mapping));
        }
        return titlesByRole;
    }

    /** Alte Zuordnungen tragen noch keinen Titelnamen; die ID ist dann die beste Auskunft. */
    private static String titleNameOf(TitleRoleMapping mapping) {
        return mapping.getTitleName() != null && !mapping.getTitleName().isBlank()
                ? mapping.getTitleName()
                : "Titel " + mapping.getTitleId();
    }

    private static String describe(SystemRole stored, String fallback) {
        return Optional.ofNullable(stored)
                .map(SystemRole::getDescription)
                .filter(description -> !description.isBlank())
                .orElse(fallback != null ? fallback : WITHOUT_DESCRIPTION);
    }

    private static boolean isSpecial(SystemRole stored) {
        return stored != null && stored.isSpecial();
    }

    private static String trimmedOrNull(String description) {
        return description == null || description.isBlank() ? null : description.trim();
    }
}
