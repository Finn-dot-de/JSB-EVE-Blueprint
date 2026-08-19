package com.eve.own.auth.backend.domain.groups.service;

import com.eve.own.auth.backend.common.EveImageUrls;
import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.auth.entity.SystemRole;
import com.eve.own.auth.backend.domain.auth.repository.SystemRoleRepository;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.groups.dto.AuthGroupDtos;
import com.eve.own.auth.backend.domain.groups.entity.AuthGroup;
import com.eve.own.auth.backend.domain.groups.entity.AuthGroupRequest;
import com.eve.own.auth.backend.domain.groups.repository.AuthGroupRepository;
import com.eve.own.auth.backend.domain.groups.repository.AuthGroupRequestRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Die Gruppen (SIGs): Beitrittsanfragen, Austritte, Rauswuerfe, Entscheidungen
 * und die Pflege der Gruppen selbst.
 *
 * <p>Eine Gruppe ist genau eine Rolle. Aufnehmen heisst deshalb: den Rollennamen
 * an das {@code roles}-Set des Charakters haengen und speichern, Austreten: ihn
 * wieder herausnehmen - alles Weitere erledigt der bestehende Rollen- und
 * Discord-Sync von dort aus.</p>
 *
 * <p>Auch die Zustaendigkeit sind Rollen: {@code leaderRoleNames} sagt, wer
 * ueber die Anfragen einer Gruppe entscheidet - zustaendig ist, wer mindestens
 * eine davon traegt. Damit hat jede Pruefung hier dieselbe Form: die
 * Ueberschneidung zweier ohnehin geladener Rollenmengen.</p>
 *
 * <p>Saemtliche Rechtepruefungen sitzen hier und nicht in der Oberflaeche. Die
 * Oberflaeche blendet Knoepfe aus; das ist Bequemlichkeit, kein Schutz. Wer den
 * Endpunkt direkt aufruft, sieht von ihr nichts.</p>
 */
@Slf4j
@Service
public class AuthGroupService {

    private static final String DECISION_APPROVE = "approve";
    private static final String DECISION_REJECT = "reject";

    /**
     * Der Sichtkreis: wer erfahren darf, WER in einer Gruppe ist.
     *
     * <p>Eine einzige benannte Menge, und das ist der Zweck: eine weitere Rolle
     * kommt mit genau einer Zeile hinzu und wirkt sofort auf alle drei Stellen -
     * auf die Mitgliederliste, auf {@code memberCount} und auf
     * {@code canViewMembers}. Getrennte Abfragen haetten die Erweiterung an
     * mehreren Stellen gebraucht, und eine davon bliebe beim naechsten Mal
     * stehen.</p>
     *
     * <p>Dieselben vier Namen wie in
     * {@link com.eve.own.auth.backend.common.AccessRules#FLEET_STAFF_OR_LEADERSHIP},
     * mit dem der Endpunkt am {@code AuthGroupController} markiert ist - wer dort
     * etwas aendert, aendert es hier mit. {@code ROLE_A38} steht als Zeichenkette
     * da und nicht als Konstante aus {@link SystemRoles}: die Rolle entsteht aus
     * einem Ingame-Titel, und {@link SystemRoles} fuehrt ausschliesslich die
     * Rollen, die die Anwendung selbst vergibt.</p>
     *
     * <p>Eine weitere Rolle war im Gespraech ("69"); sie steht weder im
     * Rollenkatalog noch traegt sie jemand, und geraten wird hier nichts.
     * Sobald es sie gibt, gehoert sie in diese Aufzaehlung - eine Zeile, sonst
     * nichts.</p>
     *
     * <p>Ausdruecklich NICHT der Kreis, der entfernen darf. Dort gilt weiterhin
     * die Leitung genau dieser Gruppe oder {@link #isAdmin}: ein A38 sieht, wer
     * in der Gruppe ist, wirft aber niemanden hinaus. Die beiden Kreise
     * ueberschneiden sich nur zufaellig und duerfen nie zu einem werden.</p>
     */
    private static final Set<String> MEMBER_VIEWER_ROLES = Set.of(
            SystemRoles.DIRECTOR, SystemRoles.CEO, SystemRoles.IT_ADMIN, "ROLE_A38");

    /** Beschreibung fuer eine Rolle, die erst durch ihre Gruppe entsteht. */
    private static final String ROLE_DESCRIPTION_PREFIX = "Mitgliedschaft in der Gruppe ";

    private final AuthGroupRepository groupRepo;
    private final AuthGroupRequestRepository requestRepo;
    private final CharacterRepository characterRepo;
    private final SystemRoleRepository systemRoleRepo;

    public AuthGroupService(AuthGroupRepository groupRepo,
                            AuthGroupRequestRepository requestRepo,
                            CharacterRepository characterRepo,
                            SystemRoleRepository systemRoleRepo) {
        this.groupRepo = groupRepo;
        this.requestRepo = requestRepo;
        this.characterRepo = characterRepo;
        this.systemRoleRepo = systemRoleRepo;
    }

    // ==================================================================
    // Was jedes Mitglied sieht und tun darf
    // ==================================================================

    /**
     * Alle Gruppen, je Gruppe angereichert um den Stand des Aufrufers.
     *
     * <p>Die Liste ist absichtlich fuer jeden Angemeldeten sichtbar: eine SIG,
     * von der niemand weiss, bekommt auch keine Anfragen. Sichtbar ist damit
     * aber nur, DASS es die Gruppe gibt - nicht, wer in ihr ist.</p>
     *
     * <p>{@code memberCount} bleibt deshalb ausserhalb von
     * {@link #MEMBER_VIEWER_ROLES} leer. Nebenwirkung mit Gewinn: fuer den
     * gewoehnlichen Nutzer entfaellt damit auch das Laden saemtlicher Charaktere,
     * das die Zaehlung braucht.</p>
     *
     * <p>{@code canViewMembers} sagt demselben Datensatz ausdruecklich, was die
     * fehlende Zahl bisher nur nebenbei verriet. Die Oberflaeche fragt das Feld
     * und schliesst nicht mehr aus einer fehlenden Auskunft auf ein Recht -
     * eine Ableitung, die niemand aufschreibt und die deshalb still falsch
     * wird, sobald Zahl und Liste einmal verschiedenen Kreisen folgen.</p>
     */
    @Transactional(readOnly = true)
    public List<AuthGroupDtos.GroupDto> groupsFor(Long characterId) {
        Character viewer = requireCharacter(characterId);
        List<AuthGroup> groups = groupRepo.findAllByOrderByNameAsc();

        Set<Long> pendingGroupIds =
                requestRepo.findByCharacterIdAndStatus(characterId, AuthGroupRequest.STATUS_PENDING)
                        .stream()
                        .map(AuthGroupRequest::getGroupId)
                        .collect(Collectors.toSet());

        // EINE Auswertung fuer alles, was am Sichtkreis haengt: das Kennzeichen
        // im Datensatz, die Mitgliederzahl und die Frage, ob die Zaehlung
        // ueberhaupt laufen muss. Zweimal gefragt koennten die drei
        // auseinanderlaufen - und der Datensatz behauptete dann ein Recht, das
        // der Endpunkt fuer die Liste verweigert.
        boolean canViewMembers = mayViewMembers(viewer);
        Map<String, Long> memberCounts = canViewMembers
                ? memberCounts(groups.stream()
                        .map(AuthGroup::getRoleName)
                        .collect(Collectors.toSet()))
                : Map.of();

        return groups.stream()
                .map(group ->
                        toGroupDto(group, viewer, pendingGroupIds, memberCounts, canViewMembers))
                .toList();
    }

    /**
     * Stellt eine Beitrittsanfrage.
     *
     * @throws IllegalArgumentException wenn die Gruppe unbekannt ist, der Charakter
     *     die Rolle bereits traegt oder schon eine offene Anfrage vorliegt
     */
    @Transactional
    public AuthGroupDtos.GroupRequestDto apply(Long characterId, Long groupId) {
        AuthGroup group = requireGroup(groupId);
        Character applicant = requireCharacter(characterId);

        if (applicant.hasRole(group.getRoleName())) {
            throw new IllegalArgumentException(
                    "Du bist bereits Mitglied von \"" + group.getName() + "\".");
        }

        // Ohne diese Sperre haeuft ein einzelner Klickfreudiger beliebig viele
        // offene Anfragen fuer dieselbe Gruppe an; die Verwaltung muesste sie
        // alle einzeln wegklicken, und nach der ersten Annahme bliebe der Rest
        // als Karteileichen stehen. Geprueft wird nur auf OFFENE Anfragen - eine
        // laengst entschiedene darf dem Wiedereintritt nach einem Austritt nicht
        // im Weg stehen.
        if (requestRepo.existsByGroupIdAndCharacterIdAndStatus(
                groupId, characterId, AuthGroupRequest.STATUS_PENDING)) {
            throw new IllegalArgumentException(
                    "Fuer \"" + group.getName() + "\" liegt bereits eine offene Anfrage vor.");
        }

        AuthGroupRequest request = new AuthGroupRequest();
        request.setGroupId(groupId);
        request.setCharacterId(characterId);
        request.setStatus(AuthGroupRequest.STATUS_PENDING);
        request.setRequestedAt(Instant.now());
        requestRepo.save(request);

        return toRequestDto(request, group, applicant);
    }

    /**
     * Verlaesst eine Gruppe: nimmt dem eigenen Charakter die Gruppenrolle ab.
     *
     * <p>Bewusst ohne Rueckfrage bei der Leitung. Ein Austritt nimmt niemandem
     * etwas weg ausser dem Austretenden selbst; ihn genehmigen zu lassen hiesse,
     * jemanden gegen seinen Willen in einer Gruppe zu halten, bis sich ein
     * Zustaendiger meldet. Der Weg zurueck fuehrt ueber einen neuen Antrag - der
     * Doppelantrag-Riegel sieht nur offene Anfragen und steht dem nicht im Weg.</p>
     *
     * <p>Nur fuer den eigenen Charakter: es gibt keinen Parameter, mit dem sich
     * ein Fremder hinauswerfen liesse. Wer jemanden anderen entfernen will,
     * braucht {@link #removeMember(Long, Long, Long)} - und dafuer die
     * Zustaendigkeit fuer diese Gruppe.</p>
     *
     * @throws IllegalArgumentException wenn die Gruppe unbekannt ist oder der
     *     Charakter ihre Rolle gar nicht traegt
     */
    @Transactional
    public void leave(Long characterId, Long groupId) {
        AuthGroup group = requireGroup(groupId);
        takeGroupRole(group, requireCharacter(characterId), "Du bist");
    }

    /**
     * Die Mitglieder einer Gruppe: die Charaktere, die ihre Rolle tragen.
     *
     * <p>Nur fuer {@link #MEMBER_VIEWER_ROLES}. Der Betrachter geht deshalb als
     * Parameter herein: bis hierher hatte die Methode keinen, und das war die
     * ausdrueckliche Aussage "wer die Gruppe sieht, sieht auch ihre Mitglieder".
     * Diese Aussage gilt nicht mehr. Wer beitreten und austreten will, braucht
     * die Namen der anderen nicht.</p>
     *
     * <p>Die Pruefung sitzt hier und nicht nur am Controller: die Annotation
     * dort haengt an einem Einstiegspunkt und faellt bei einem Umbau lautlos
     * weg. Und sie steht VOR dem Zusammenbauen der Liste, damit gar nicht erst
     * gelesen wird, was der Aufrufer nicht sehen darf.</p>
     *
     * <p>Ein Unberechtigter bekommt eine Ausnahme und keine leere Liste. Eine
     * leere Liste waere eine Falschaussage - sie behauptete "niemand ist drin"
     * und liesse sich von "Gruppe existiert, ist aber leer" nicht
     * unterscheiden.</p>
     *
     * <p>Sortiert nach Namen, damit die Liste bei jedem Laden gleich aussieht -
     * die Reihenfolge aus der Datenbank ist keine.</p>
     *
     * @throws AccessDeniedException wenn der Betrachter nicht zum Sichtkreis gehoert
     * @throws IllegalArgumentException wenn Gruppe oder Betrachter unbekannt sind
     */
    @Transactional(readOnly = true)
    public List<AuthGroupDtos.GroupMemberDto> membersOf(Long viewerId, Long groupId) {
        // Erst der Sichtkreis, dann die Gruppe - dieselbe Reihenfolge wie beim
        // Entfernen. Andernfalls beantwortete der Endpunkt auch einem
        // Unberechtigten, welche Gruppen-Ids es gibt: "unbekannt" gegen
        // "verboten" ist ein Unterschied, den man reihum abfragen kann.
        requireMemberViewer(viewerId);
        AuthGroup group = requireGroup(groupId);
        return membersWithRole(group.getRoleName()).stream()
                .map(member -> new AuthGroupDtos.GroupMemberDto(
                        member.getId(),
                        member.getName(),
                        EveImageUrls.portrait(member.getId())))
                .toList();
    }

    /**
     * Wirft ein Mitglied aus der Gruppe: nimmt einem <b>fremden</b> Charakter die
     * Gruppenrolle ab.
     *
     * <p>Der einzige Einstiegspunkt dieses Features, der eine fremde
     * Charakter-ID entgegennimmt. Alle uebrigen nehmen den Charakter aus dem
     * Sicherheitskontext und koennen deshalb gar nichts anderes anfassen als den
     * Aufrufer selbst; hier entscheidet allein die Pruefung unten, wessen Rollen
     * geschrieben werden. Sie sitzt deswegen hier und nicht am Controller - eine
     * Annotation gehoert zu einem Einstiegspunkt, diese Regel zur Sache.</p>
     *
     * <p>Entfernt wird ausschliesslich {@code group.roleName}. Ein Charakter
     * traegt daneben Corp-, Titel- und Fuehrungsrollen; das ganze Rollen-Set zu
     * leeren wuerde ihn aus der halben Anwendung aussperren, und der
     * Discord-Sync raeumte still hinterher.</p>
     *
     * @throws AccessDeniedException wenn der Aufrufer fuer diese Gruppe nicht
     *     zustaendig ist oder als Leitung einen Admin entfernen will
     * @throws IllegalArgumentException wenn Gruppe oder Charakter unbekannt sind
     *     oder der Charakter die Rolle gar nicht traegt
     */
    @Transactional
    public void removeMember(Long actorId, Long groupId, Long characterId) {
        AuthGroup group = requireGroup(groupId);
        Character actor = requireCharacter(actorId);

        // Derselbe Kreis, der ueber die Anfragen dieser Gruppe entscheidet:
        // aufnehmen und entfernen sind dieselbe Befugnis von zwei Seiten. Ohne
        // die Pruefung an dieser Stelle koennte jeder Angemeldete den Endpunkt
        // mit einer fremden Charakter-Id aufrufen und reihum jede Mitgliedschaft
        // der Corporation abraeumen - der Discord-Sync zoege still nach, und der
        // Betroffene stuende ohne Erklaerung vor verschlossenen Kanaelen.
        boolean actorIsAdmin = isAdmin(actor);
        if (!isLeaderOf(group, actor) && !actorIsAdmin) {
            throw new AccessDeniedException(
                    "Aus dieser Gruppe entfernen nur ihre Leitung und die Fuehrung.");
        }

        Character member = requireCharacter(characterId);

        // ENTSCHIEDEN: Eine Leitung entfernt keinen globalen Admin, nur die
        // Fuehrung selbst darf das. Die Frage ist, welcher Schadensfall der
        // kleinere ist - und die Antwort faellt eindeutig aus, weil die eine
        // Richtung einen Ausweg hat und die andere nicht.
        // Verboten kostet nichts: ein Admin, der aus einer Gruppe heraus will,
        // klickt "Austreten" (leave) und ist draussen - ohne jemanden zu fragen.
        // Und soll er hinaus, ohne es zu wollen, tut es ein zweiter Admin.
        // Erlaubt dagegen kostet: eine Leitungsrolle haengt oft an einem
        // Ingame-Titel und wechselt mit ihm den Traeger. Ein einzelner
        // veraergerter oder uebernommener FC koennte damit genau die Personen
        // aus seiner SIG schneiden, die ihn beaufsichtigen sollen - Gruppe fuer
        // Gruppe, jedes Mal nur eine Rolle, jedes Mal unauffaellig, und der
        // Discord-Sync entzieht die Kanaele lautlos hinterher.
        // Ein Recht ohne Nutzen gegen ein Recht mit Missbrauchsweg: es faellt weg.
        if (!actorIsAdmin && isAdmin(member)) {
            throw new AccessDeniedException(
                    "Mitglieder der Fuehrung entfernt nur die Fuehrung selbst.");
        }

        takeGroupRole(group, member, member.getName() + " ist");

        // Am Charakter steht hinterher nur, dass eine Rolle fehlt - nie, wer sie
        // ihm genommen hat. Ohne diese Zeile ist die Frage "warum bin ich aus
        // der SIG geflogen?" nicht mehr zu beantworten.
        log.info("{} ({}) hat {} ({}) aus der Gruppe \"{}\" entfernt - Rolle {} abgenommen.",
                actor.getName(), actorId, member.getName(), characterId,
                group.getName(), group.getRoleName());
    }

    /**
     * Die offenen Anfragen, die dieser Charakter bearbeiten darf.
     *
     * <p>Wer weder eine Leitungsrolle noch eine Admin-Rolle traegt, bekommt eine
     * leere Liste - der Reiter "Verwaltung" blendet sich dann von selbst aus.</p>
     */
    @Transactional(readOnly = true)
    public List<AuthGroupDtos.GroupRequestDto> openRequestsFor(Long characterId) {
        Character viewer = requireCharacter(characterId);
        boolean admin = isAdmin(viewer);

        // REGEL 3: Eine Leitung sieht ausschliesslich Anfragen ihrer eigenen
        // Gruppen. Ohne diese Einschraenkung laege der komplette Antragsverkehr
        // aller SIGs offen - wer sich wo bewirbt, geht eine fremde Leitung
        // nichts an, und abgelehnt werden koennte es ohnehin nicht (Regel 1).
        List<AuthGroup> visibleGroups = admin
                ? groupRepo.findAllByOrderByNameAsc()
                : groupsLedBy(viewer);
        if (visibleGroups.isEmpty()) {
            return List.of();
        }

        Map<Long, AuthGroup> groupsById = visibleGroups.stream()
                .collect(Collectors.toMap(AuthGroup::getId, Function.identity(),
                        (first, second) -> first, LinkedHashMap::new));

        List<AuthGroupRequest> requests = requestRepo.findByStatusAndGroupIdIn(
                AuthGroupRequest.STATUS_PENDING, groupsById.keySet());

        Map<Long, Character> applicants = charactersById(requests.stream()
                .map(AuthGroupRequest::getCharacterId)
                .collect(Collectors.toSet()));

        return requests.stream()
                // Der eigene Antrag taucht hier nicht auf - ausser beim IT-Admin,
                // der ihn entscheiden darf. Sonst gaebe es hier einen Knopf, der
                // zuverlaessig 403 liefert, oder umgekehrt ein Recht fuer etwas,
                // das man gar nicht sieht. Den Stand seiner eigenen Anfrage sieht
                // jeder Antragsteller im Reiter "Gruppen".
                .filter(request -> !request.getCharacterId().equals(characterId)
                        || viewer.hasRole(SystemRoles.IT_ADMIN))
                .filter(request -> groupsById.containsKey(request.getGroupId()))
                .sorted(Comparator.comparing(AuthGroupRequest::getRequestedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(request -> toRequestDto(request,
                        groupsById.get(request.getGroupId()),
                        applicants.get(request.getCharacterId())))
                .toList();
    }

    /**
     * Nimmt eine Anfrage an oder lehnt sie ab.
     *
     * <p>Bei der Annahme bekommt der Charakter die Rolle der Gruppe. Der
     * Discord-Sync liest {@code character.getRoles()} und verteilt sie beim
     * naechsten Lauf; hier ist nichts weiter anzustossen.</p>
     *
     * @param decision {@code approve} oder {@code reject}
     * @throws IllegalArgumentException bei unbekannter Anfrage, bereits getroffener
     *     Entscheidung oder unbekanntem {@code decision}
     * @throws AccessDeniedException wenn der Entscheider nicht zustaendig ist
     */
    @Transactional
    public void decide(Long deciderId, Long requestId, String decision) {
        boolean approve = parseDecision(decision);

        AuthGroupRequest request = requestRepo.findById(requestId).orElseThrow(
                () -> new IllegalArgumentException("Anfrage " + requestId + " ist unbekannt."));
        if (!AuthGroupRequest.STATUS_PENDING.equals(request.getStatus())) {
            throw new IllegalArgumentException("Ueber diese Anfrage wurde bereits entschieden.");
        }

        AuthGroup group = requireGroup(request.getGroupId());
        Character decider = requireCharacter(deciderId);

        // REGEL 2: Niemand entscheidet ueber seinen eigenen Antrag - auch kein
        // Traeger der Leitungsrolle und auch kein Admin. Ohne diese Zeile waere
        // der Antragsweg fuer genau die Personen eine Formalie, die ihn
        // ueberwachen sollen: ein Klick auf den eigenen Antrag, und die Rolle
        // sitzt. Dass die Leitung inzwischen an einer Rolle haengt, lockert die
        // Regel nicht, sondern macht sie ertraeglich: frueher blieb der Antrag
        // des einzigen Leiters fuer immer liegen, heute springt ein anderer
        // Traeger derselben Rolle ein.
        // Ausnahme fuer den IT-Admin, und zwar NUR fuer ihn: eine bewusste
        // Lockerung, keine Nachlaessigkeit. Er kann sich dieselbe Rolle ohnehin
        // unmittelbar in der Rechteverwaltung eintragen - die Sperre war ihm
        // gegenueber Symbolik, kein Schutz, und sie hinderte ihn daran, den
        // Antragsweg zu erproben. Fuer Leitung, DIRECTOR und CEO bleibt sie.
        if (request.getCharacterId().equals(deciderId)) {
            if (!decider.hasRole(SystemRoles.IT_ADMIN)) {
                throw new AccessDeniedException(
                        "Ueber den eigenen Antrag entscheidet man nicht selbst.");
            }
            // Sichtbar machen, was sonst niemand mehr nachvollziehen koennte:
            // Am Charakter steht danach nur die Rolle, nicht ihre Herkunft.
            log.info("IT-Admin {} entscheidet ueber den EIGENEN Antrag {} "
                    + "(Gruppe \"{}\", Rolle {}).",
                    deciderId, request.getId(), group.getName(), group.getRoleName());
        }

        // REGEL 1: Nur wer die Leitungsrolle genau dieser Gruppe traegt oder
        // Admin ist. Ohne die Pruefung an dieser Stelle koennte jeder Angemeldete
        // den Endpunkt direkt aufrufen und sich ueber einen fremden Antrag hinweg
        // jede beliebige Gruppenrolle verteilen lassen - die Oberflaeche zeigt
        // den Reiter zwar nicht, sie haelt aber niemanden auf.
        if (!isLeaderOf(group, decider) && !isAdmin(decider)) {
            throw new AccessDeniedException(
                    "Ueber Anfragen dieser Gruppe entscheiden nur ihre Leitung und die Fuehrung.");
        }

        request.setStatus(approve
                ? AuthGroupRequest.STATUS_APPROVED
                : AuthGroupRequest.STATUS_REJECTED);
        request.setDecidedAt(Instant.now());
        request.setDecidedByCharacterId(deciderId);
        requestRepo.save(request);

        if (approve) {
            Character applicant = requireCharacter(request.getCharacterId());
            applicant.getRoles().add(group.getRoleName());
            characterRepo.save(applicant);
        }
    }

    // ==================================================================
    // Pflege der Gruppen - der Fuehrung vorbehalten
    // ==================================================================

    /**
     * Legt eine Gruppe an oder aendert sie - nur fuer Admins.
     *
     * <p>Nebenbei entsteht die zugehoerige {@link SystemRole} mit
     * {@code special = true}. Das ist keine Kosmetik: {@code CharacterRoleService}
     * baut das Rollen-Set bei jedem Sync aus Corp-Zugehoerigkeit und Ingame-Titeln
     * neu auf und behaelt davon nur die als speziell markierten Rollen. Eine
     * Gruppenrolle ohne diese Markierung waere zehn Minuten nach der Aufnahme
     * wieder verschwunden - samt Discord-Rolle, und ohne dass jemand wuesste, warum.</p>
     *
     * <p>Fehlt ein Rollenname, wird er aus dem Gruppennamen abgeleitet. Der Admin
     * musste die Rolle bis dahin vorher von Hand im Rollenkatalog anlegen und
     * dann hier auswaehlen - zwei Schritte, von denen der erste gern vergessen
     * wurde, mit dem Ergebnis einer Gruppe, deren Rolle es nicht gab.</p>
     *
     * @throws AccessDeniedException wenn der Bearbeiter kein Admin ist
     * @throws IllegalArgumentException bei leerem Namen, eingebauter Rolle,
     *     bereits anderweitig vergebener Rolle oder untauglicher Leitungsrolle
     */
    @Transactional
    public AuthGroupDtos.GroupDto saveGroup(Long editorId, AuthGroupDtos.SaveGroupDto dto) {
        Character editor = requireAdmin(editorId);

        String name = trimmed(dto.name());
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Die Gruppe braucht einen Namen.");
        }

        // Der Vorschlag aus dem Gruppennamen ist nur der Rueckfall: schickt die
        // Oberflaeche einen eigenen Namen mit, gilt dieser. "Wurmloch SIG" wird
        // so zu ROLE_WURMLOCH_SIG, ohne dass jemand die Schreibregel kennen muss.
        String roleName = SystemRoles.normalize(
                blankToNull(dto.roleName()) != null ? dto.roleName() : name);
        // Eine eingebaute Rolle als Gruppenrolle waere fatal: sie bekaeme unten
        // special = true und damit wuerde eine einmal vergebene Fuehrungsrolle
        // jede Neuberechnung ueberdauern - auch nach dem Verlust des Titels.
        if (SystemRoles.isBuiltIn(roleName)) {
            throw new IllegalArgumentException(
                    roleName + " ist eine eingebaute Rolle und taugt nicht als Gruppenrolle.");
        }

        AuthGroup group = dto.id() == null
                ? new AuthGroup()
                : groupRepo.findById(dto.id()).orElseThrow(
                        () -> new IllegalArgumentException("Gruppe " + dto.id() + " ist unbekannt."));

        // Zwei Gruppen auf derselben Rolle liessen sich nicht auseinanderhalten:
        // wer in die eine aufgenommen wird, gilt automatisch auch in der anderen
        // als Mitglied.
        String previousRoleName = group.getRoleName();
        if (!roleName.equals(previousRoleName) && groupRepo.existsByRoleName(roleName)) {
            throw new IllegalArgumentException(
                    "Die Rolle " + roleName + " gehoert bereits zu einer anderen Gruppe.");
        }

        // Erst pruefen, dann schreiben: eine untaugliche Leitungsrolle soll die
        // Gruppe gar nicht erst veraendern.
        Set<String> leaderRoleNames = normalizedLeaderRoles(dto.leaderRoleNames());

        group.setName(name);
        group.setDescription(blankToNull(dto.description()));
        group.setRoleName(roleName);
        // Die Sammlung wird geleert und neu gefuellt statt ausgetauscht: an einer
        // verwalteten Entitaet ist sie eine Hibernate-Sammlung, und ein Austausch
        // des Behaelters kostet sie ihre Aenderungsverfolgung.
        group.getLeaderRoleNames().clear();
        group.getLeaderRoleNames().addAll(leaderRoleNames);
        groupRepo.save(group);

        ensureSpecialRole(roleName, name);

        // Mit Mitgliederzahl und mit gesetztem canViewMembers, ohne erneute
        // Pruefung: hierher kommt nur, wen requireAdmin durchgelassen hat, und
        // der Admin-Kreis liegt vollstaendig im Sichtkreis. Ein zweites
        // mayViewMembers taeuschte eine Entscheidung vor, die es an dieser
        // Stelle nicht gibt. Beide Felder haengen auch hier an demselben einen
        // Wert - die Zusicherung des Datensatzes gilt an jedem Ausgang.
        return toGroupDto(group, editor, Set.of(), memberCounts(Set.of(roleName)), true);
    }

    /**
     * Loescht eine Gruppe samt ihrer Anfragen - nur fuer Admins.
     *
     * <p>Die Rolle bleibt stehen. Sie kann an einem Ingame-Titel haengen oder von
     * Hand vergeben worden sein; sie hier mitzuloeschen naehme Charakteren Rechte,
     * die mit der Gruppe nie etwas zu tun hatten. Ihre Pflege gehoert in den
     * Rollenkatalog.</p>
     *
     * @throws AccessDeniedException wenn der Bearbeiter kein Admin ist
     */
    @Transactional
    public void deleteGroup(Long editorId, Long groupId) {
        requireAdmin(editorId);
        if (!groupRepo.existsById(groupId)) {
            throw new IllegalArgumentException("Gruppe " + groupId + " ist unbekannt.");
        }
        // Zuerst die Anfragen: eine verwaiste Anfrage zeigte auf eine Gruppe, die
        // es nicht mehr gibt, und liesse sich weder anzeigen noch entscheiden.
        requestRepo.deleteByGroupId(groupId);
        groupRepo.deleteById(groupId);
    }

    // ==================================================================
    // Innereien
    // ==================================================================

    /**
     * Der Admin-Kreis, gelesen an der Entitaet statt am Sicherheitskontext -
     * dasselbe Vorgehen wie in {@code TitleMappingService} und
     * {@code CorporationStatsService}.
     *
     * <p>Die drei Namen sind dieselben wie in
     * {@link com.eve.own.auth.backend.common.AccessRules#LEADERSHIP_OR_IT}. Wer
     * dort etwas aendert, muss es hier mitaendern - sonst entscheidet ein
     * Rollenkreis ueber Anfragen, der die Gruppenverwaltung gar nicht aufrufen darf.</p>
     */
    private boolean isAdmin(Character character) {
        return character.hasRole(SystemRoles.DIRECTOR)
                || character.hasRole(SystemRoles.CEO)
                || character.hasRole(SystemRoles.IT_ADMIN);
    }

    /**
     * Ob dieser Charakter erfahren darf, wer in einer Gruppe ist.
     *
     * <p>Gelesen am Rollen-Set der Entitaet wie {@link #isAdmin} und nicht am
     * Sicherheitskontext - sonst haetten die beiden Kreise zwei verschiedene
     * Quellen, und ein Rollen-Sync koennte sie auseinanderlaufen lassen.</p>
     *
     * <p>ENTSCHIEDEN: Eine Leitung OHNE eine dieser Rollen sieht die
     * Mitgliederliste NICHT. Sie fuehrt zwar eine Gruppe, der Nutzer hat sie im
     * Sichtkreis aber nicht genannt, und das ist keine Nachlaessigkeit, sondern
     * der Punkt: Leitungsrollen sind frei eintragbar (jeder FC-, Recruiter- oder
     * Ausbilder-Titel taugt dafuer). Waere "Leitung" hier ein Zugang, wuechse
     * der Sichtkreis kuenftig mit jeder Gruppe, die jemand anlegt - unbemerkt
     * und ohne dass irgendwo stuende, wer inzwischen mitliest. Genau davon soll
     * diese Aenderung wegfuehren.</p>
     *
     * <p>Der Preis ist bekannt und wird bewusst gezahlt: eine Leitung ausserhalb
     * des Kreises kann weiterhin entfernen, sieht die Namen dazu aber nicht.
     * Ihre eigentliche Aufgabe - ueber Anfragen entscheiden - braucht den
     * Antragsteller und nicht die Mitgliederliste. Wer die Liste wirklich
     * braucht, bekommt ROLE_A38; das ist ein Eintrag im Rollenkatalog und
     * nachvollziehbar, eine stillschweigende Regel waere es nicht.</p>
     */
    private static boolean mayViewMembers(Character character) {
        return MEMBER_VIEWER_ROLES.stream().anyMatch(character::hasRole);
    }

    /**
     * Der Riegel vor jeder Auskunft ueber die Mitglieder einer Gruppe.
     *
     * <p>Ohne ihn liefe der Endpunkt weiter fuer jeden Angemeldeten: die
     * Oberflaeche blendet den Aufklapp-Pfeil zwar aus, aber
     * {@code GET /api/groups/{id}/members} steht offen, und heraus kaeme die
     * vollstaendige Namensliste samt Charakter-Ids jeder SIG.</p>
     */
    private void requireMemberViewer(Long viewerId) {
        if (!mayViewMembers(requireCharacter(viewerId))) {
            throw new AccessDeniedException(
                    "Wer in einer Gruppe ist, sehen nur die Fuehrung, die technische "
                            + "Administration und die Ausbilder.");
        }
    }

    /**
     * Die Pflege der Gruppen ist Admin-Sache - hier steht der Riegel dafuer.
     *
     * <p>Der {@code AuthGroupAdminController} traegt zwar bereits ein
     * klassenweites {@code @PreAuthorize}, doch das ist eine Eigenschaft des
     * einen Einstiegspunkts und nicht der Sache. Faellt die Annotation bei einem
     * Umbau weg oder bekommt die Pflege einen zweiten Aufrufer, waere das Loch
     * das gefaehrlichste des ganzen Features: wer eine Gruppe anlegen darf,
     * traegt sich selbst als Leitung ein und vergibt sich anschliessend jede
     * Rolle - am Antragsweg vorbei und ohne dass es jemandem auffiele.</p>
     *
     * @return der Bearbeiter, weil der Aufrufer ihn ohnehin fuer die Antwort braucht
     */
    private Character requireAdmin(Long editorId) {
        Character editor = requireCharacter(editorId);
        if (!isAdmin(editor)) {
            throw new AccessDeniedException(
                    "Gruppen legt nur die Fuehrung an, aendert sie und loescht sie.");
        }
        return editor;
    }

    /**
     * Ob dieser Charakter eine der Leitungsrollen dieser Gruppe traegt.
     *
     * <p>Eine Ueberschneidung, kein Vergleich: es genuegt <b>eine</b> passende
     * Rolle. Die Schleife laeuft ueber die Leitungsrollen und nicht ueber den
     * Rollensatz des Charakters, weil die erste Menge die kleinere ist - meist
     * ein oder zwei Eintraege gegen ein Dutzend.</p>
     *
     * <p>Die leere Menge ist der tragende Fall und keine Randerscheinung: eine
     * Gruppe ohne hinterlegte Leitung hat keine Leitung, es entscheiden nur die
     * Admins. {@code anyMatch} beantwortet das von selbst mit {@code false} -
     * ein "keine Leitung eingetragen, also darf jeder" darf hier nie entstehen.</p>
     */
    private static boolean isLeaderOf(AuthGroup group, Character character) {
        Set<String> leaderRoles = group.getLeaderRoleNames();
        return leaderRoles != null && leaderRoles.stream().anyMatch(character::hasRole);
    }

    /**
     * Die Gruppen, von denen dieser Charakter mindestens eine Leitungsrolle traegt.
     *
     * <p>Der leere Rollensatz wird hier abgefangen und geht nicht als
     * {@code IN ()} an die Datenbank.</p>
     */
    private List<AuthGroup> groupsLedBy(Character character) {
        Set<String> roles = character.getRoles();
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        return groupRepo.findByLeaderRoleNameIn(roles);
    }

    /** {@code true} heisst annehmen, {@code false} ablehnen. */
    private static boolean parseDecision(String decision) {
        String normalized = decision == null ? "" : decision.trim().toLowerCase(Locale.ROOT);
        if (DECISION_APPROVE.equals(normalized)) {
            return true;
        }
        if (DECISION_REJECT.equals(normalized)) {
            return false;
        }
        throw new IllegalArgumentException(
                "\"" + decision + "\" ist keine Entscheidung. Erlaubt sind "
                        + DECISION_APPROVE + " und " + DECISION_REJECT + ".");
    }

    /**
     * Sorgt dafuer, dass die Rolle existiert und eine Neuberechnung ueberdauert.
     *
     * <p>{@code special = true} ist der Kern dieser Methode, nicht ihre Beigabe:
     * {@code CharacterRoleService.applyRoles} baut das Rollen-Set eines
     * Charakters bei jedem Sync neu aus Corp-Zugehoerigkeit und Ingame-Titeln
     * auf und rettet aus dem alten Stand ausschliesslich die Rollen, die in
     * {@code system_roles} als speziell gefuehrt sind
     * ({@code retainedSpecialRoles}, gespeist aus {@code findByIsSpecialTrue}).
     * Eine Gruppenrolle ohne dieses Kennzeichen waere beim naechsten Lauf - alle
     * zehn Minuten - wieder weg: die Mitgliedschaft loeste sich still auf, die
     * Discord-Rolle gleich mit, und die APPROVED-Zeile der Anfrage stuende
     * weiterhin da, als sei alles in Ordnung.</p>
     *
     * <p>Eine bereits vorhandene Rolle wird wiederverwendet und nicht ersetzt:
     * sie kann laengst an einem Ingame-Titel oder an Charakteren haengen. Auch
     * ihre Beschreibung bleibt stehen - sie stammt dann aus dem Rollenkatalog
     * und ist dort mit Bedacht gesetzt worden. Das Kennzeichen wird trotzdem
     * gesetzt, denn ohne es gilt der Absatz darueber auch fuer sie.</p>
     */
    private void ensureSpecialRole(String roleName, String groupName) {
        SystemRole role = systemRoleRepo.findById(roleName).orElseGet(SystemRole::new);
        role.setRoleName(roleName);
        if (role.getDescription() == null || role.getDescription().isBlank()) {
            role.setDescription(ROLE_DESCRIPTION_PREFIX + groupName);
        }
        role.setSpecial(true);
        systemRoleRepo.save(role);
    }

    /**
     * Bringt die eingetragenen Leitungsrollen auf Rollenschreibweise; leer
     * heisst "keine Leitung".
     *
     * <p>Ob die Rollen im Katalog stehen, wird bewusst nicht geprueft: die
     * meisten Rollen entstehen erst dadurch, dass ein Ingame-Titel sie vergibt,
     * und stehen dann in {@code title_role_mappings} statt in
     * {@code system_roles}. Eine Existenzpruefung gegen {@code system_roles}
     * wuerde also genau die ueblichen Leitungsrollen (FC, Recruiter) zurueckweisen.</p>
     *
     * <p>Eingebaute Rollen sind dagegen ausgeschlossen, und zwar jede einzelne
     * der Menge: {@code ROLE_USER}, {@code ROLE_MEMBER} und {@code ROLE_GUEST}
     * traegt praktisch jeder - eine davon genuegte, damit jeder Angemeldete ueber
     * die Anfragen dieser Gruppe entscheiden duerfte, auch ueber die seiner
     * Freunde. Weil schon <em>eine</em> passende Rolle zustaendig macht, waere
     * eine Pruefung "wenigstens eine taugt" hier wertlos. Die drei
     * Fuehrungsrollen wiederum entscheiden ohnehin schon ueber jede Gruppe.</p>
     *
     * <p>Leere Eintraege fallen still heraus: die Oberflaeche schickt fuer eine
     * noch nicht ausgefuellte Zeile eine leere Zeichenkette, und die soll keinen
     * Fehler ausloesen, sondern schlicht keine Leitungsrolle bedeuten.</p>
     */
    private static Set<String> normalizedLeaderRoles(Collection<String> rawLeaderRoleNames) {
        if (rawLeaderRoleNames == null) {
            return Set.of();
        }
        // LinkedHashSet: die Menge entdoppelt zwei verschiedene Schreibweisen
        // derselben Rolle ("fc strat" und "ROLE_FC_STRAT"), behaelt aber die
        // eingegebene Reihenfolge - so steht die Antwort in der Reihenfolge des
        // Formulars und nicht in der einer Streuung.
        Set<String> leaderRoleNames = new LinkedHashSet<>();
        for (String rawLeaderRoleName : rawLeaderRoleNames) {
            if (rawLeaderRoleName == null || rawLeaderRoleName.isBlank()) {
                continue;
            }
            String leaderRoleName = SystemRoles.normalize(rawLeaderRoleName);
            if (SystemRoles.isBuiltIn(leaderRoleName)) {
                throw new IllegalArgumentException(leaderRoleName
                        + " ist eine eingebaute Rolle und taugt nicht als Leitungsrolle.");
            }
            leaderRoleNames.add(leaderRoleName);
        }
        return leaderRoleNames;
    }

    /**
     * @param canViewMembers das Ergebnis von {@link #mayViewMembers(Character)}
     *     fuer den Betrachter - EINMAL ausgewertet und hier hineingereicht, weil
     *     daraus zwei Felder entstehen, die nie auseinanderlaufen duerfen: die
     *     Mitgliederzahl und das gleichnamige Kennzeichen im Datensatz.
     *     <p>Die Zahl ist eine Auskunft ueber die Mitglieder wie die Liste
     *     selbst, nur grober - deshalb haengt sie am selben Sichtkreis, und
     *     ausserhalb davon {@code null} statt {@code 0}, weil die Null
     *     behaupten wuerde, die Gruppe sei leer.
     *     <p>Das Kennzeichen sagt dasselbe ausdruecklich, damit die Oberflaeche
     *     es nicht aus dem Fehlen der Zahl erraten muss. Ein zweiter Aufruf von
     *     {@code mayViewMembers} an dieser Stelle haette die Kopplung nur
     *     verschoben: zwei Auswertungen koennen sich unterscheiden, ein
     *     durchgereichter Wert nicht.
     */
    private AuthGroupDtos.GroupDto toGroupDto(AuthGroup group,
                                              Character viewer,
                                              Set<Long> pendingGroupIds,
                                              Map<String, Long> memberCounts,
                                              boolean canViewMembers) {
        return new AuthGroupDtos.GroupDto(
                group.getId(),
                group.getName(),
                group.getDescription(),
                group.getRoleName(),
                // Sortiert, weil die Sammlung am Ende eine Streuung ist: ohne
                // feste Ordnung wechselten die Etiketten in der Tabelle bei
                // jedem Laden die Plaetze.
                group.getLeaderRoleNames().stream().sorted().toList(),
                canViewMembers
                        ? memberCounts.getOrDefault(group.getRoleName(), 0L)
                        : null,
                canViewMembers,
                viewer.hasRole(group.getRoleName()),
                pendingGroupIds.contains(group.getId()),
                isLeaderOf(group, viewer));
    }

    /**
     * Nimmt dem Charakter die Rolle dieser Gruppe ab - der gemeinsame Kern von
     * Austritt und Rauswurf.
     *
     * <p>Beide Wege enden in genau diesen drei Zeilen, und das ist Absicht: hier
     * steht, was "kein Mitglied mehr" heisst. Waere die Rollenentnahme ein
     * zweites Mal ausgeschrieben, koennte die eine Fassung ein
     * {@code characterRepo.save} vergessen oder statt der einen Rolle das ganze
     * Set leeren, ohne dass es an der anderen auffiele.</p>
     *
     * <p>Entfernt wird <b>nur</b> {@code group.getRoleName()}. Corp-, Titel- und
     * Fuehrungsrollen des Charakters bleiben unangetastet - sie haben mit dieser
     * Gruppe nichts zu tun.</p>
     *
     * <p>{@code subject} ist der Satzanfang der Fehlermeldung ("Du bist",
     * "Name ist"). Er ist der einzige Unterschied zwischen den beiden Wegen:
     * dem Austretenden sagt man "du", ueber ein entferntes Mitglied spricht man
     * in der dritten Person - eine Meldung "Du bist kein Mitglied" bei einem
     * Rauswurf liesse den Leiter an seiner eigenen Mitgliedschaft zweifeln.</p>
     *
     * @throws IllegalArgumentException wenn der Charakter die Rolle gar nicht
     *     traegt. Ein stilles "erledigt" verdeckte eine veraltete Anzeige oder
     *     einen falsch verdrahteten Knopf - der Aufrufer glaubte dann, etwas
     *     bewirkt zu haben.
     */
    private void takeGroupRole(AuthGroup group, Character member, String subject) {
        if (!member.hasRole(group.getRoleName())) {
            throw new IllegalArgumentException(
                    subject + " kein Mitglied von \"" + group.getName() + "\".");
        }
        member.getRoles().remove(group.getRoleName());
        characterRepo.save(member);
    }

    /**
     * Die Traeger einer Rolle, nach Namen sortiert.
     *
     * <p>Aus demselben Ladevorgang wie {@link #memberCounts(Set)} und aus
     * demselben Grund: {@code roles} haengt als EAGER-Sammlung am Charakter,
     * ohne den Entity-Graph von {@code findAllWithCorporation} holte Hibernate
     * die Rollen jedes einzelnen Charakters mit einer eigenen Abfrage nach.</p>
     *
     * <p>Sortiert ohne Ruecksicht auf Gross- und Kleinschreibung: EVE-Namen
     * beginnen mal so, mal so, und eine Liste, in der "alpha" hinter "Zulu"
     * steht, sieht nach einem Fehler aus.</p>
     */
    private List<Character> membersWithRole(String roleName) {
        return characterRepo.findAllWithCorporation().stream()
                .filter(character -> character.hasRole(roleName))
                .sorted(Comparator.comparing(Character::getName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    /**
     * Wie viele Charaktere die genannten Rollen tragen.
     *
     * <p>Gezaehlt wird aus einem einzigen Ladevorgang statt mit einer Abfrage je
     * Gruppe. {@code roles} haengt als EAGER-Sammlung am Charakter - ohne den
     * Entity-Graph von {@code findAllWithCorporation} holte Hibernate die Rollen
     * jedes einzelnen Charakters mit einer eigenen Abfrage nach.</p>
     *
     * <p>Gezaehlt werden Charaktere, nicht Accounts: die Rolle haengt am
     * Charakter, und genau ihm gibt der Discord-Sync sie auch weiter. Und
     * gezaehlt werden Mitglieder, nicht Bewerber - eine Gruppe mit einer offenen
     * Anfrage und ohne Aufnahme steht hier zu Recht auf null.</p>
     */
    private Map<String, Long> memberCounts(Set<String> roleNames) {
        if (roleNames.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> counts = new HashMap<>();
        for (Character character : characterRepo.findAllWithCorporation()) {
            for (String roleName : character.getRoles()) {
                if (roleNames.contains(roleName)) {
                    counts.merge(roleName, 1L, Long::sum);
                }
            }
        }
        return counts;
    }

    /** Der Charakter kann zwischenzeitlich verschwunden sein; die ID bleibt die beste Auskunft. */
    private static AuthGroupDtos.GroupRequestDto toRequestDto(AuthGroupRequest request,
                                                             AuthGroup group,
                                                             Character applicant) {
        Long characterId = request.getCharacterId();
        return new AuthGroupDtos.GroupRequestDto(
                request.getId(),
                group.getId(),
                group.getName(),
                characterId,
                applicant != null ? applicant.getName() : "Charakter " + characterId,
                EveImageUrls.portrait(characterId),
                request.getStatus(),
                request.getRequestedAt());
    }

    private Map<Long, Character> charactersById(Set<Long> characterIds) {
        if (characterIds.isEmpty()) {
            return Map.of();
        }
        return characterRepo.findAllById(characterIds).stream()
                .collect(Collectors.toMap(Character::getId, Function.identity()));
    }

    private Character requireCharacter(Long characterId) {
        return characterRepo.findById(characterId).orElseThrow(
                () -> new IllegalArgumentException("Charakter " + characterId + " ist unbekannt."));
    }

    private AuthGroup requireGroup(Long groupId) {
        return groupRepo.findById(groupId).orElseThrow(
                () -> new IllegalArgumentException("Gruppe " + groupId + " ist unbekannt."));
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
