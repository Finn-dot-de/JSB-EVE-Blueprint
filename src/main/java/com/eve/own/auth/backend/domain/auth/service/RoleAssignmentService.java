package com.eve.own.auth.backend.domain.auth.service;

import com.eve.own.auth.backend.common.EveImageUrls;
import com.eve.own.auth.backend.domain.auth.AuthRoleSource;
import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.auth.dto.RoleAssignmentDtos;
import com.eve.own.auth.backend.domain.auth.entity.RoleAssignmentAudit;
import com.eve.own.auth.backend.domain.auth.entity.SystemRole;
import com.eve.own.auth.backend.domain.auth.entity.TitleRoleMapping;
import com.eve.own.auth.backend.domain.auth.repository.RoleAssignmentAuditRepository;
import com.eve.own.auth.backend.domain.auth.repository.SystemRoleRepository;
import com.eve.own.auth.backend.domain.auth.repository.TitleRoleMappingRepository;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Weist einem einzelnen Charakter eine Rolle zu und entzieht sie wieder.
 *
 * <p>Bis hierher entstanden Rollen an drei Stellen, und keine davon war ein
 * Knopf: aus der Corp-Zugehoerigkeit, aus einem Ingame-Titel ueber
 * {@code title_role_mappings} und aus einer angenommenen Gruppenanfrage. Der
 * Weg von Hand fehlte - und mit ihm die vier Fallen, die er aufreisst. Sie sind
 * der eigentliche Inhalt dieser Klasse; das Setzen des Rollennamens selbst ist
 * eine Zeile.</p>
 *
 * <p><b>Falle 1 - der Sync raeumt still ab.</b>
 * {@link CharacterRoleService#applyRoles} baut den Rollensatz alle zehn Minuten
 * neu aus Corp-Zugehoerigkeit und Ingame-Titeln auf und rettet aus dem alten
 * Stand ausschliesslich die Rollen, die in {@code system_roles} als speziell
 * gefuehrt sind. Eine von Hand vergebene Rolle ohne dieses Kennzeichen ist beim
 * naechsten Lauf wieder weg - ohne Fehler, ohne Meldung, ohne dass jemand den
 * Zusammenhang findet. ENTSCHIEDEN: Das Zuweisen setzt das Kennzeichen selbst
 * (siehe {@link #grant}), statt zu warnen oder zu verweigern. Wer eine Rolle von
 * Hand vergibt, sagt damit "diese Rolle soll bleiben" - alles andere waere ein
 * Versprechen mit Ablaufdatum. Es ist dasselbe Vorgehen, mit dem
 * {@code AuthGroupService.ensureSpecialRole} schon heute die Gruppenrollen vor
 * demselben Sync rettet.</p>
 *
 * <p><b>Falle 2 - Titel-Rollen kommen zurueck.</b> Vergibt ein Ingame-Titel die
 * Rolle, traegt der naechste Sync sie wieder ein. Wer sie hier entzieht, hat sie
 * zehn Minuten spaeter zurueck. ENTSCHIEDEN: Das Entziehen wird verweigert
 * (siehe {@link #revoke}), und die Auskunft aus {@link #rolesOf} sagt es schon
 * vorher. Beides zusammen, denn eine Auskunft "aussichtslos" neben einem Dienst,
 * der es trotzdem tut, waere der schlimmere Zustand: der Klick meldete Erfolg,
 * und die Rolle kaeme wieder.</p>
 *
 * <p><b>Falle 3 - Herkunft.</b> Jede Zuweisung und jedes Entziehen erzeugt eine
 * Zeile in {@link RoleAssignmentAudit}. Am Charakter steht sonst nur der
 * Rollenname, nie woher er kommt.</p>
 *
 * <p><b>Falle 4 - die eigenen Rechte.</b> Ein Admin darf sich hier selbst
 * bedienen; verhindern liesse es sich ohnehin nicht (er legt sonst eine Gruppe
 * an und tritt ihr bei). Der Vorgang wird stattdessen ausdruecklich als
 * Selbstvergabe gekennzeichnet und protokolliert - genau wie die Selbstannahme
 * eines IT-Admins in {@code AuthGroupService.decide}.</p>
 *
 * <p>Saemtliche Pruefungen sitzen hier und nicht am Controller. Die Annotation
 * dort gehoert zu einem Einstiegspunkt, faellt bei einem Umbau lautlos weg und
 * schuetzt einen zweiten Aufrufer gar nicht. Ein Loch an dieser Stelle waere das
 * teuerste der Anwendung: wer beliebige Rollen verteilen kann, verteilt auch die
 * Rechte, mit denen er beim naechsten Mal nicht mehr geprueft wird.</p>
 */
@Slf4j
@Service
public class RoleAssignmentService {

    /** Beschreibung fuer eine Rolle, die erst durch diese Zuweisung entsteht. */
    private static final String DESCRIPTION_FROM_ASSIGNMENT = "Von Hand vergebene Rolle";

    private static final String NOTE_BUILT_IN =
            "Eingebaute Rolle - sie entsteht aus der Corp-Zugehoerigkeit oder einem "
                    + "Ingame-Titel und laesst sich hier weder vergeben noch entziehen.";

    private static final String NOTE_FREE =
            "Frei vergebbar. Beim Zuweisen wird die Rolle als dauerhaft markiert, "
                    + "damit der Rollen-Sync sie nicht wieder abraeumt.";

    private static final String NOTE_HELD =
            "Von Hand vergeben und dauerhaft - der Rollen-Sync laesst sie stehen.";

    private static final String NOTE_HELD_VOLATILE =
            "Diese Rolle ist NICHT als dauerhaft markiert. Der naechste Rollen-Sync "
                    + "nimmt sie wieder weg. Ein erneutes Zuweisen setzt die Markierung.";

    private final CharacterRepository characterRepo;
    private final SystemRoleRepository systemRoleRepo;
    private final TitleRoleMappingRepository titleRepo;
    private final RoleAssignmentAuditRepository auditRepo;
    private final RoleCatalogService roleCatalogService;

    public RoleAssignmentService(CharacterRepository characterRepo,
                                 SystemRoleRepository systemRoleRepo,
                                 TitleRoleMappingRepository titleRepo,
                                 RoleAssignmentAuditRepository auditRepo,
                                 RoleCatalogService roleCatalogService) {
        this.characterRepo = characterRepo;
        this.systemRoleRepo = systemRoleRepo;
        this.titleRepo = titleRepo;
        this.auditRepo = auditRepo;
        this.roleCatalogService = roleCatalogService;
    }

    // ==================================================================
    // Auskunft - was ein Klick bewirken wuerde
    // ==================================================================

    /**
     * Alle Rollen eines Charakters samt Bewertung: vergebbar, entziehbar,
     * ueberlebt den Sync.
     *
     * <p>Das ist die Auskunft, die die Oberflaeche braucht, um zu warnen BEVOR
     * jemand klickt. Ohne sie sieht der Admin einen Knopf, der bei einer
     * Titel-Rolle folgenlos bleibt, und versucht es dreimal, bevor er begreift,
     * dass es nicht an ihm liegt.</p>
     *
     * <p>Die Liste fuehrt getragene und nicht getragene Rollen zusammen: den
     * Rollenkatalog (eingebaute, selbst angelegte und aus Titeln entstandene
     * Rollen) und zusaetzlich alles, was der Charakter traegt, ohne dass es im
     * Katalog steht. Der zweite Teil ist kein Randfall - eine Rolle, deren
     * {@code system_roles}-Zeile jemand geloescht hat, bliebe sonst am Charakter
     * haengen und waere hier unsichtbar, also auch nicht mehr zu entziehen.</p>
     *
     * <p>Auch das Lesen ist Admin-Sache: die Liste zeigt vollstaendig, welche
     * Rechte ein Charakter hat und welche Titel sie ihm geben - das ist die
     * Landkarte des Rechtemodells und nichts, was jeder Angemeldete abrufen
     * koennen muss.</p>
     *
     * @throws AccessDeniedException wenn der Aufrufer kein Admin ist
     * @throws IllegalArgumentException wenn der Charakter unbekannt ist
     */
    @Transactional(readOnly = true)
    public RoleAssignmentDtos.CharacterRolesDto rolesOf(Long actorId, Long characterId) {
        requireAdmin(actorId);
        Character character = requireCharacter(characterId);

        Map<String, List<String>> titlesByRole = grantingTitlesInCorporationOf(character);
        Map<String, RoleCatalogService.AuthRoleDto> catalog = roleCatalogService.catalog().stream()
                .collect(Collectors.toMap(RoleCatalogService.AuthRoleDto::name,
                        Function.identity(), (first, second) -> first, LinkedHashMap::new));

        Set<String> allRoleNames = new LinkedHashSet<>(catalog.keySet());
        allRoleNames.addAll(character.getRoles());

        // Getragene zuerst, darin alphabetisch: die Oberflaeche zeigt oben, was
        // der Charakter HAT, und darunter, was er haben koennte. Aus einer
        // Streuung kaeme beides gemischt und bei jedem Laden anders.
        Comparator<RoleAssignmentDtos.RoleStateDto> getrageneZuerst =
                Comparator.comparing((RoleAssignmentDtos.RoleStateDto role) -> !role.held())
                        .thenComparing(RoleAssignmentDtos.RoleStateDto::roleName);

        List<RoleAssignmentDtos.RoleStateDto> roles = allRoleNames.stream()
                .map(roleName -> toRoleState(roleName, character, catalog.get(roleName),
                        titlesByRole.getOrDefault(roleName, List.of())))
                .sorted(getrageneZuerst)
                .toList();

        return new RoleAssignmentDtos.CharacterRolesDto(
                character.getId(),
                character.getName(),
                EveImageUrls.portrait(character.getId()),
                roles);
    }

    // ==================================================================
    // Zuweisen und Entziehen
    // ==================================================================

    /**
     * Gibt einem Charakter eine Rolle.
     *
     * <p>Die Reihenfolge der Pruefungen ist Absicht: erst das Recht des
     * Handelnden, dann die Rolle, dann der Charakter. Wer nichts darf, soll auch
     * nicht durch Ausprobieren erfahren, welche Charakter-IDs es gibt.</p>
     *
     * <p><b>Das Kennzeichen {@code is_special} wird hier gesetzt</b>, wenn es
     * fehlt. Das ist der Kern der Methode und keine Beigabe: ohne das Kennzeichen
     * nimmt {@code CharacterRoleService} die Rolle beim naechsten Lauf wieder
     * weg - lautlos, spaetestens nach zehn Minuten. Der Admin saehe die Rolle
     * gesetzt, spaeter waere sie fort, und niemand faende den Zusammenhang.</p>
     *
     * <p>Genau eine Ausnahme davon, und sie ist der Grund, warum diese Methode
     * ueberhaupt eine Verweigerung kennt: <b>vergibt ein Ingame-Titel diese Rolle
     * bereits</b> und ist sie noch nicht als dauerhaft markiert, bricht der
     * Vorgang ab. Das Kennzeichen gilt der Rolle und nicht diesem einen
     * Charakter - es zu setzen hiesse, sie kuenftig bei <em>jedem</em> Traeger
     * den Verlust des Titels ueberdauern zu lassen. Aus einer Rolle, die mit dem
     * Titel kommt und geht, wuerde damit eine, die nur noch kommt. Wer dem
     * Charakter die Rolle wirklich geben will, gibt ihm ingame den Titel oder
     * loest die Zuordnung.</p>
     *
     * @param reason freiwillige Angabe, warum - darf {@code null} oder leer sein
     * @return der geschriebene Nachweiseintrag; der Aufrufer soll sehen, was
     *     protokolliert wurde, ohne den Verlauf neu laden zu muessen
     * @throws AccessDeniedException wenn der Handelnde kein Admin ist
     * @throws IllegalArgumentException bei eingebauter Rolle, bereits getragener
     *     Rolle, unbekanntem Charakter oder einer noch nicht dauerhaften
     *     Titel-Rolle
     */
    @Transactional
    public RoleAssignmentDtos.RoleAuditDto grant(Long actorId, Long characterId,
                                                 String rawRoleName, String reason) {
        Character actor = requireAdmin(actorId);
        String roleName = requireAssignableName(rawRoleName);
        Character character = requireCharacter(characterId);

        if (character.hasRole(roleName)) {
            throw new IllegalArgumentException(
                    character.getName() + " traegt " + roleName + " bereits.");
        }

        ensureSurvivesSync(roleName, grantingTitlesInCorporationOf(character)
                .getOrDefault(roleName, List.of()));

        character.getRoles().add(roleName);
        characterRepo.save(character);

        return record(RoleAssignmentAudit.ACTION_GRANT, actor, character, roleName, reason);
    }

    /**
     * Nimmt einem Charakter eine Rolle wieder ab.
     *
     * <p>Entfernt wird ausschliesslich diese eine Rolle. Ein Charakter traegt
     * daneben Corp-, Titel- und Fuehrungsrollen; das ganze Set zu leeren sperrte
     * ihn aus der halben Anwendung aus, und der Discord-Sync raeumte still
     * hinterher.</p>
     *
     * <p><b>Eine Rolle, die ein Ingame-Titel vergibt, wird nicht entzogen</b>,
     * sondern der Vorgang bricht ab. Der Sync traegt sie beim naechsten Lauf
     * wieder ein - der Klick haette gemeldet, etwas bewirkt zu haben, und zehn
     * Minuten spaeter waere alles beim Alten. {@link #rolesOf} kennzeichnet
     * denselben Fall vorab als aussichtslos; die beiden duerfen nie
     * auseinanderfallen, sonst widerspricht der Dienst seiner eigenen Auskunft.</p>
     *
     * <p>Der bekannte Preis: traegt der Charakter den Titel gar nicht mehr und
     * haengt die Rolle nur noch als dauerhafte Handvergabe an ihm, laesst sie
     * sich hier trotzdem nicht abnehmen, solange die Zuordnung des Titels steht.
     * Wir wissen an dieser Stelle nicht, welche Titel er ingame traegt - das
     * saehe nur ein ESI-Aufruf mit einem Director-Token, und eine
     * Verwaltungsaktion, die an einem fremden Dienst scheitern kann, ist die
     * schlechtere Loesung. Der Ausweg steht auf derselben Seite: die
     * Titel-Zuordnung loesen, dann entziehen.</p>
     *
     * @param reason freiwillige Angabe, warum - darf {@code null} oder leer sein
     * @return der geschriebene Nachweiseintrag
     * @throws AccessDeniedException wenn der Handelnde kein Admin ist
     * @throws IllegalArgumentException bei eingebauter Rolle, nicht getragener
     *     Rolle, unbekanntem Charakter oder einer Titel-Rolle
     */
    @Transactional
    public RoleAssignmentDtos.RoleAuditDto revoke(Long actorId, Long characterId,
                                                  String rawRoleName, String reason) {
        Character actor = requireAdmin(actorId);
        String roleName = requireAssignableName(rawRoleName);
        Character character = requireCharacter(characterId);

        if (!character.hasRole(roleName)) {
            // Ein stilles "erledigt" verdeckte eine veraltete Anzeige oder einen
            // falsch verdrahteten Knopf - der Aufrufer glaubte dann, etwas
            // bewirkt zu haben. Dieselbe Ueberlegung wie in
            // AuthGroupService.takeGroupRole.
            throw new IllegalArgumentException(
                    character.getName() + " traegt " + roleName + " gar nicht.");
        }

        List<String> grantingTitles =
                grantingTitlesInCorporationOf(character).getOrDefault(roleName, List.of());
        if (!grantingTitles.isEmpty()) {
            throw new IllegalArgumentException(roleName + " kommt aus dem Ingame-Titel "
                    + String.join(", ", grantingTitles)
                    + " und waere beim naechsten Rollen-Sync wieder da. "
                    + "Loese zuerst die Titel-Zuordnung oder nimm den Titel ingame ab.");
        }

        character.getRoles().remove(roleName);
        characterRepo.save(character);

        return record(RoleAssignmentAudit.ACTION_REVOKE, actor, character, roleName, reason);
    }

    // ==================================================================
    // Der Nachweis
    // ==================================================================

    /**
     * Der Verlauf eines Charakters: wer ihm wann welche Rolle gab oder nahm.
     *
     * <p>Ein leerer Verlauf heisst "seit Einfuehrung des Nachweises nichts von
     * Hand geaendert" und NICHT "die Rollen waren schon immer da" - siehe
     * {@link RoleAssignmentAudit}.</p>
     *
     * @throws AccessDeniedException wenn der Aufrufer kein Admin ist
     */
    @Transactional(readOnly = true)
    public List<RoleAssignmentDtos.RoleAuditDto> auditFor(Long actorId, Long characterId) {
        requireAdmin(actorId);
        return toAuditDtos(auditRepo.findByCharacterIdOrderByOccurredAtDesc(characterId));
    }

    /**
     * Die juengsten Aenderungen ueber alle Charaktere - der Blick von oben.
     *
     * @throws AccessDeniedException wenn der Aufrufer kein Admin ist
     */
    @Transactional(readOnly = true)
    public List<RoleAssignmentDtos.RoleAuditDto> recentAudit(Long actorId) {
        requireAdmin(actorId);
        return toAuditDtos(auditRepo.findTop200ByOrderByOccurredAtDesc());
    }

    // ==================================================================
    // Innereien
    // ==================================================================

    /**
     * Schreibt den Nachweis und macht die Selbstvergabe sichtbar.
     *
     * <p>Der gemeinsame Ausgang von Zuweisen und Entziehen, und das ist Absicht:
     * waere die Zeile zweimal ausgeschrieben, koennte die eine Fassung das
     * Kennzeichen der Selbstvergabe vergessen oder den Zeitpunkt nicht setzen,
     * ohne dass es an der anderen auffiele.</p>
     *
     * <p>Die Logzeile ist kein Ersatz fuer die Tabelle, sondern ihr Echo an einer
     * Stelle, die ein Betreiber ohnehin liest. Bei einer Selbstvergabe steht sie
     * auf WARN: das ist der Vorgang, ueber den spaeter jemand stolpern soll -
     * derselbe Gedanke wie beim protokollierten Alleingang des IT-Admins in
     * {@code AuthGroupService.decide}.</p>
     */
    private RoleAssignmentDtos.RoleAuditDto record(String action, Character actor,
                                                   Character character, String roleName,
                                                   String reason) {
        boolean selfAssigned = actor.getId().equals(character.getId());

        RoleAssignmentAudit audit = new RoleAssignmentAudit();
        audit.setCharacterId(character.getId());
        audit.setRoleName(roleName);
        audit.setAction(action);
        audit.setActorCharacterId(actor.getId());
        audit.setSelfAssigned(selfAssigned);
        audit.setReason(blankToNull(reason));
        audit.setOccurredAt(Instant.now());
        // Der Rueckgabewert und nicht das uebergebene Objekt: die erzeugte ID
        // steht erst danach fest, und der Aufrufer bekommt den Eintrag zurueck.
        RoleAssignmentAudit saved = auditRepo.save(audit);

        if (selfAssigned) {
            log.warn("{} ({}) hat SICH SELBST die Rolle {} {} - Grund: {}",
                    actor.getName(), actor.getId(), roleName,
                    RoleAssignmentAudit.ACTION_GRANT.equals(action) ? "gegeben" : "genommen",
                    audit.getReason() != null ? audit.getReason() : "ohne Angabe");
        } else {
            log.info("{} ({}) hat {} ({}) die Rolle {} {} - Grund: {}",
                    actor.getName(), actor.getId(), character.getName(), character.getId(),
                    roleName,
                    RoleAssignmentAudit.ACTION_GRANT.equals(action) ? "gegeben" : "genommen",
                    audit.getReason() != null ? audit.getReason() : "ohne Angabe");
        }

        // Eine veraenderliche Karte und kein Map.of: bei einer Selbstvergabe
        // sind Handelnder und Betroffener derselbe Charakter, und Map.of wirft
        // bei einem doppelten Schluessel. Genau der Fall, den diese Methode
        // besonders sichtbar machen soll, waere damit der einzige, der scheitert.
        Map<Long, Character> involved = new LinkedHashMap<>();
        involved.put(actor.getId(), actor);
        involved.put(character.getId(), character);
        return toAuditDto(saved, involved);
    }

    /**
     * Sorgt dafuer, dass die Rolle den naechsten Rollen-Sync ueberlebt.
     *
     * <p>Ohne diesen Schritt waere jede Zuweisung ein Versprechen mit
     * Ablaufdatum: {@code CharacterRoleService} baut den Rollensatz alle zehn
     * Minuten neu auf und behaelt nur, was in {@code system_roles} als speziell
     * gefuehrt ist. Fehlt die {@code system_roles}-Zeile ganz, entsteht sie hier
     * - eine Rolle kann bisher allein aus einer Titel-Zuordnung existieren.</p>
     *
     * <p>Eine bestehende Beschreibung bleibt stehen: sie stammt dann aus dem
     * Rollenkatalog und ist dort mit Bedacht gesetzt worden.</p>
     *
     * <p>Traegt die Rolle das Kennzeichen bereits, wird nichts geschrieben - und
     * genau dann darf auch eine Titel-Rolle vergeben werden: die Entscheidung
     * "diese Rolle ueberdauert den Titelverlust" ist dann schon getroffen, und
     * zwar von jemandem, der sie im Rollenkatalog bewusst gesetzt hat.</p>
     */
    private void ensureSurvivesSync(String roleName, List<String> grantingTitles) {
        SystemRole role = systemRoleRepo.findById(roleName).orElse(null);
        if (role != null && role.isSpecial()) {
            return;
        }

        if (!grantingTitles.isEmpty()) {
            throw new IllegalArgumentException(roleName + " vergibt bereits der Ingame-Titel "
                    + String.join(", ", grantingTitles)
                    + ". Von Hand vergeben liesse sich die Rolle nur, indem sie als dauerhaft "
                    + "markiert wird - dann bliebe sie aber JEDEM Traeger auch nach dem Verlust "
                    + "des Titels erhalten. Vergib stattdessen den Titel ingame oder lege eine "
                    + "eigene Rolle an.");
        }

        SystemRole toSave = role != null ? role : new SystemRole();
        toSave.setRoleName(roleName);
        if (toSave.getDescription() == null || toSave.getDescription().isBlank()) {
            toSave.setDescription(DESCRIPTION_FROM_ASSIGNMENT);
        }
        toSave.setSpecial(true);
        systemRoleRepo.save(toSave);
    }

    /**
     * Bringt den eingegebenen Namen auf Rollenschreibweise und weist eingebaute
     * Rollen ab.
     *
     * <p>ENTSCHIEDEN: eingebaute Rollen ({@link SystemRoles#builtIn()}) lassen
     * sich hier weder vergeben noch entziehen. Sie entstehen auf zwei Wegen, und
     * beide vertragen den Eingriff nicht:</p>
     * <ul>
     *   <li>{@code ROLE_USER}, {@code ROLE_MEMBER}, {@code ROLE_MARAUDERS_ASSOCIATED}
     *       und {@code ROLE_GUEST} berechnet {@code CharacterRoleService} bei
     *       JEDEM Sync aus der Corp-Zugehoerigkeit neu. Von Hand gesetzt sind sie
     *       zehn Minuten spaeter wieder so, wie die Corporation es sagt - das
     *       Vergeben ist wirkungslos, das Entziehen ebenso.</li>
     *   <li>{@code ROLE_CEO}, {@code ROLE_DIRECTOR} und {@code ROLE_IT_ADMIN}
     *       kommen aus einem Ingame-Titel oder einer bewussten Eintragung im
     *       Rollenkatalog. Sie hier zu vergeben hiesse, sie nach Falle 1 als
     *       dauerhaft zu markieren - und damit wuerde eine Fuehrungsrolle den
     *       Verlust des Titels ueberdauern, dieselbe Gefahr, die
     *       {@code RoleCatalogService.save} und {@code AuthGroupService.saveGroup}
     *       schon heute abwehren. Nebenwirkung mit Gewinn: ueber diesen Weg kann
     *       sich niemand zum IT-Admin machen.</li>
     * </ul>
     *
     * <p>Die Normalisierung davor ist kein Komfort, sondern Notwehr: Rollennamen
     * werden ueberall als Zeichenkette verglichen. Ein "fleet commander" aus dem
     * Eingabefeld waere sonst eine andere Rolle als {@code ROLE_FLEET_COMMANDER},
     * griffe nie und liesse sich auch nicht wieder entziehen, weil der Katalog
     * ihn gar nicht kennt.</p>
     */
    private static String requireAssignableName(String rawRoleName) {
        String roleName = SystemRoles.normalize(rawRoleName);
        if (SystemRoles.isBuiltIn(roleName)) {
            throw new IllegalArgumentException(roleName
                    + " ist eine eingebaute Rolle. Sie entsteht aus der Corp-Zugehoerigkeit "
                    + "oder einem Ingame-Titel und laesst sich von Hand weder vergeben noch "
                    + "entziehen.");
        }
        return roleName;
    }

    /**
     * Welche Ingame-Titel der Corporation DIESES Charakters welche Rolle vergeben.
     *
     * <p>Eingeschraenkt auf seine Corporation, weil auch der Sync so arbeitet:
     * {@code CharacterRoleService.titleRoles} liest
     * {@code titleRepo.findByCorporationId}. Eine Zuordnung in einer anderen Corp
     * betrifft diesen Charakter nicht, und sie hier mitzuzaehlen wuerde
     * Zuweisungen abweisen, die voellig gefahrlos sind.</p>
     *
     * <p>Bewusst OHNE Nachfrage bei ESI, welche Titel der Charakter tatsaechlich
     * traegt. Das kostete einen Aufruf mit Director-Token je Seitenaufbau und
     * machte eine Verwaltungsseite von der Erreichbarkeit eines fremden Dienstes
     * abhaengig. Der Preis ist eine Warnung, die zu oft statt zu selten kommt:
     * gewarnt wird, sobald ein Titel dieser Corporation die Rolle vergeben
     * KOENNTE. In diese Richtung zu irren ist die harmlosere - eine ausbleibende
     * Warnung waere genau die Falle, die hier vermieden werden soll.</p>
     *
     * <p>Zuordnungen ohne Rollennamen zaehlen nicht mit: ein leerer Name heisst
     * ausdruecklich "dieser Titel vergibt nichts" (siehe
     * {@code TitleMappingService.saveMapping}).</p>
     */
    private Map<String, List<String>> grantingTitlesInCorporationOf(Character character) {
        Map<String, List<String>> titlesByRole = new LinkedHashMap<>();
        for (TitleRoleMapping mapping : titleRepo.findByCorporationId(
                character.getCorporation().getId())) {
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

    /**
     * Die Bewertung einer einzelnen Rolle fuer diesen Charakter.
     *
     * <p>Hier stehen die Regeln aus {@link #grant} und {@link #revoke} ein
     * zweites Mal - und zwar bewusst als <em>Vorhersage</em> derselben
     * Entscheidungen, damit die Oberflaeche warnen kann, ohne den Endpunkt
     * probeweise aufzurufen. Beide Seiten haengen an denselben drei Fakten
     * (eingebaut, Titel vergibt sie, dauerhaft markiert); laufen sie je
     * auseinander, zeigt die Oberflaeche einen Knopf, der 400 liefert, oder
     * verbirgt einen, der funktioniert haette.</p>
     *
     * @param catalogEntry der Katalogeintrag - {@code null} fuer eine Rolle, die
     *     der Charakter traegt, ohne dass sie irgendwo verzeichnet waere
     */
    private static RoleAssignmentDtos.RoleStateDto toRoleState(
            String roleName,
            Character character,
            RoleCatalogService.AuthRoleDto catalogEntry,
            List<String> grantingTitles) {

        boolean held = character.hasRole(roleName);
        boolean builtIn = SystemRoles.isBuiltIn(roleName);
        boolean byTitle = !grantingTitles.isEmpty();
        boolean survivesSync = catalogEntry != null && catalogEntry.special();

        // Vergebbar ist, was der Charakter noch nicht hat, was nicht eingebaut
        // ist und was nicht schon ein Titel vergibt - es sei denn, die Rolle
        // traegt die Dauerhaftigkeit ohnehin schon. Genau die Bedingung, an der
        // ensureSurvivesSync abbricht.
        boolean assignable = !held && !builtIn && (!byTitle || survivesSync);

        // Entziehbar ist nur, was der Charakter hat, was nicht eingebaut ist und
        // was kein Titel nachliefert. Das ist Falle 2 in einem Kennzeichen.
        boolean revocable = held && !builtIn && !byTitle;

        return new RoleAssignmentDtos.RoleStateDto(
                roleName,
                catalogEntry != null ? catalogEntry.description() : DESCRIPTION_FROM_ASSIGNMENT,
                catalogEntry != null ? catalogEntry.source() : AuthRoleSource.CUSTOM,
                held,
                survivesSync,
                assignable,
                revocable,
                grantingTitles,
                note(builtIn, byTitle, held, survivesSync, grantingTitles));
    }

    /** Der Satz, den die Oberflaeche anzeigt - die Begruendung steht genau einmal. */
    private static String note(boolean builtIn, boolean byTitle, boolean held,
                               boolean survivesSync, List<String> grantingTitles) {
        if (builtIn) {
            return NOTE_BUILT_IN;
        }
        if (byTitle) {
            String titel = "Ingame-Titel " + String.join(", ", grantingTitles);
            if (held) {
                return "Kommt aus dem " + titel + ". Ein Entzug haelt nicht - der naechste "
                        + "Rollen-Sync traegt sie in spaetestens zehn Minuten wieder ein.";
            }
            return survivesSync
                    ? "Vergibt der " + titel + ". Sie ist als dauerhaft markiert und laesst "
                            + "sich deshalb auch von Hand vergeben."
                    : "Vergibt der " + titel + ". Von Hand vergeben laesst sie sich nicht - sie "
                            + "muesste dafuer als dauerhaft markiert werden und bliebe dann JEDEM "
                            + "Traeger auch nach dem Verlust des Titels erhalten. Vergib den "
                            + "Titel ingame.";
        }
        if (!held) {
            return NOTE_FREE;
        }
        return survivesSync ? NOTE_HELD : NOTE_HELD_VOLATILE;
    }

    /**
     * Der Admin-Kreis, gelesen am Rollensatz der Entitaet statt am
     * Sicherheitskontext - dasselbe Vorgehen wie in {@code AuthGroupService},
     * {@code TitleMappingService} und {@code CorporationStatsService}.
     *
     * <p>Die drei Namen sind dieselben wie in
     * {@link com.eve.own.auth.backend.common.AccessRules#LEADERSHIP_OR_IT}, mit
     * dem die Endpunkte markiert sind. Wer dort etwas aendert, muss es hier
     * mitaendern - sonst verteilt ein Rollenkreis Rollen, der die Seite gar nicht
     * aufrufen darf.</p>
     *
     * @return der Handelnde, weil jeder Aufrufer ihn ohnehin fuer den Nachweis braucht
     */
    private Character requireAdmin(Long actorId) {
        Character actor = requireCharacter(actorId);
        boolean admin = actor.hasRole(SystemRoles.DIRECTOR)
                || actor.hasRole(SystemRoles.CEO)
                || actor.hasRole(SystemRoles.IT_ADMIN);
        if (!admin) {
            throw new AccessDeniedException(
                    "Rollen vergibt und entzieht nur die Fuehrung.");
        }
        return actor;
    }

    private Character requireCharacter(Long characterId) {
        return characterRepo.findById(characterId).orElseThrow(
                () -> new IllegalArgumentException("Charakter " + characterId + " ist unbekannt."));
    }

    /** Laedt die Namen zu den IDs des Nachweises in EINEM Zug statt je Zeile. */
    private List<RoleAssignmentDtos.RoleAuditDto> toAuditDtos(List<RoleAssignmentAudit> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        Set<Long> characterIds = new LinkedHashSet<>();
        entries.forEach(entry -> {
            characterIds.add(entry.getCharacterId());
            characterIds.add(entry.getActorCharacterId());
        });
        Map<Long, Character> byId = characterRepo.findAllById(characterIds).stream()
                .collect(Collectors.toMap(Character::getId, Function.identity()));
        return entries.stream().map(entry -> toAuditDto(entry, byId)).toList();
    }

    /** Der Charakter kann zwischenzeitlich verschwunden sein; die ID bleibt die beste Auskunft. */
    private static RoleAssignmentDtos.RoleAuditDto toAuditDto(RoleAssignmentAudit audit,
                                                              Map<Long, Character> byId) {
        return new RoleAssignmentDtos.RoleAuditDto(
                audit.getId(),
                audit.getCharacterId(),
                nameOf(byId, audit.getCharacterId()),
                EveImageUrls.portrait(audit.getCharacterId()),
                audit.getRoleName(),
                audit.getAction(),
                audit.getActorCharacterId(),
                nameOf(byId, audit.getActorCharacterId()),
                audit.isSelfAssigned(),
                audit.getReason(),
                audit.getOccurredAt());
    }

    private static String nameOf(Map<Long, Character> byId, Long characterId) {
        Character character = byId.get(characterId);
        return character != null ? character.getName() : "Charakter " + characterId;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
