package com.eve.own.auth.backend.domain.groups.controller;

import com.eve.own.auth.backend.common.AccessRules;
import com.eve.own.auth.backend.common.CurrentUser;
import com.eve.own.auth.backend.domain.groups.dto.AuthGroupDtos;
import com.eve.own.auth.backend.domain.groups.service.AuthGroupService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Die Gruppen (SIGs), wie jedes Mitglied sie sieht: beitreten, wieder austreten
 * und - mit der Leitungsrolle - ueber Anfragen entscheiden.
 *
 * <p>Kein {@code @PreAuthorize} auf Klassenebene, weil hier keine feste Rolle
 * die Grenze zieht: die Gruppenliste sieht jeder Angemeldete, ueber Anfragen
 * entscheidet dagegen nur, wer eine der Leitungsrollen genau dieser Gruppe
 * traegt, oder die Fuehrung. Welche Rollen das sind, steht erst in der geladenen Gruppe
 * und laesst sich in einem SpEL-Ausdruck nicht ausdruecken; diese Pruefungen
 * sitzen deshalb vollstaendig im {@link AuthGroupService}. Dass ueberhaupt jemand angemeldet ist, stellt
 * {@code SecurityConfig} mit {@code anyRequest().authenticated()} sicher.</p>
 *
 * <p>Eine Ausnahme: die Mitgliederliste. Ihr Kreis haengt an festen Rollen und
 * nicht an der geladenen Gruppe, laesst sich also sehr wohl als SpEL schreiben -
 * und steht deshalb zusaetzlich als Annotation an der Methode. Der Dienst prueft
 * dieselbe Regel noch einmal; das ist dasselbe Muster wie beim
 * {@code AuthGroupAdminController} und keine Doppelung aus Unsicherheit: die
 * Annotation gehoert zu diesem einen Einstiegspunkt, faellt bei einem Umbau
 * lautlos weg und schuetzt einen zweiten Aufrufer gar nicht.</p>
 *
 * <p>Die Pflege der Gruppen liegt bewusst woanders: {@code /api/groups} ist
 * bereits vom {@code GroupManagementController} belegt, das Admin-CRUD haengt
 * deshalb unter {@code /api/admin/groups} - dasselbe Muster wie bei der
 * Navigation.</p>
 */
@RestController
@RequestMapping("/api/groups")
public class AuthGroupController {

    private final AuthGroupService groupService;

    public AuthGroupController(AuthGroupService groupService) {
        this.groupService = groupService;
    }

    /** Alle Gruppen, je Gruppe angereichert um den Stand des Aufrufers. */
    @GetMapping
    public ResponseEntity<List<AuthGroupDtos.GroupDto>> groups() {
        return ResponseEntity.ok(groupService.groupsFor(CurrentUser.characterId()));
    }

    /** Stellt eine Beitrittsanfrage fuer den angemeldeten Charakter. */
    @PostMapping("/{id}/apply")
    public ResponseEntity<AuthGroupDtos.GroupRequestDto> apply(@PathVariable Long id) {
        return ResponseEntity.ok(groupService.apply(CurrentUser.characterId(), id));
    }

    /**
     * Verlaesst die Gruppe: nimmt dem angemeldeten Charakter ihre Rolle ab.
     *
     * <p>Gegenstueck zu {@code apply}, aber ohne Anfrage und ohne Entscheidung -
     * wer raus will, ist raus. Der Weg zurueck fuehrt ueber einen neuen Antrag.</p>
     *
     * <p>Der Charakter kommt wie ueberall hier aus dem Sicherheitskontext und
     * nicht aus dem Pfad. Ein Parameter dafuer waere eine Hintertuer, jeden
     * beliebigen Charakter aus jeder beliebigen Gruppe zu werfen.</p>
     */
    @PostMapping("/{id}/leave")
    public ResponseEntity<Void> leave(@PathVariable Long id) {
        groupService.leave(CurrentUser.characterId(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Die Mitglieder der Gruppe - Name und Portrait, nach Namen sortiert.
     *
     * <p>Der einzige Endpunkt dieses Controllers mit einem eigenen Rechtekreis:
     * Fuehrung, technische Administration und die Ausbilder (A38) - derselbe
     * Kreis, den {@link AccessRules#FLEET_STAFF_OR_LEADERSHIP} bereits benennt.
     * Wer nur beitreten und austreten will, braucht die Namen der anderen nicht,
     * und eine Corp-Mitgliederliste je SIG ist mehr Auskunft, als der Reiter
     * hergeben soll.</p>
     *
     * <p>Deshalb geht hier - anders als bisher - der Aufrufer mit an den
     * Service: dort steht dieselbe Pruefung noch einmal, und zwar an der Sache
     * statt am Einstiegspunkt. Ein Unberechtigter bekommt 403 und keine leere
     * Liste; eine leere Liste behauptete, die Gruppe sei leer.</p>
     */
    @GetMapping("/{id}/members")
    @PreAuthorize(AccessRules.FLEET_STAFF_OR_LEADERSHIP)
    public ResponseEntity<List<AuthGroupDtos.GroupMemberDto>> members(@PathVariable Long id) {
        return ResponseEntity.ok(groupService.membersOf(CurrentUser.characterId(), id));
    }

    /**
     * Entfernt ein Mitglied aus der Gruppe.
     *
     * <p>Der einzige Endpunkt dieses Controllers mit einer <b>fremden</b>
     * Charakter-Id im Pfad. Er ist damit das genaue Gegenteil von
     * {@code leave} - und der Grund, warum die Zustaendigkeitspruefung im
     * {@link AuthGroupService} sitzt und nicht hier: die Leitungsrollen stehen
     * erst in der geladenen Gruppe, und was hier stuende, faende bei einem
     * zweiten Aufrufer niemand wieder.</p>
     *
     * <p>Bewusst OHNE die Annotation, die ueber der Mitgliederliste steht: Sehen
     * und Hinauswerfen sind zwei verschiedene Kreise. Ein A38 sieht, wer in der
     * Gruppe ist, entfernt aber niemanden; eine Leitung entfernt, sieht die
     * Liste aber nicht. Wer die beiden Kreise zusammenzoege, gaebe jedem
     * Ausbilder Zugriff auf jede Mitgliedschaft der Corporation.</p>
     *
     * <p>{@code DELETE} und nicht {@code POST}, weil hier wirklich etwas
     * entfernt wird; die Antwort ist entsprechend leer.</p>
     */
    @DeleteMapping("/{id}/members/{characterId}")
    public ResponseEntity<Void> removeMember(@PathVariable Long id,
                                             @PathVariable Long characterId) {
        groupService.removeMember(CurrentUser.characterId(), id, characterId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Die offenen Anfragen, die der Aufrufer bearbeiten darf.
     *
     * <p>Ein Leiter bekommt nur seine Gruppen, die Fuehrung alle, jeder andere
     * eine leere Liste - der Reiter "Verwaltung" blendet sich dann selbst aus.</p>
     */
    @GetMapping("/requests")
    public ResponseEntity<List<AuthGroupDtos.GroupRequestDto>> openRequests() {
        return ResponseEntity.ok(groupService.openRequestsFor(CurrentUser.characterId()));
    }

    /**
     * Nimmt eine Anfrage an oder lehnt sie ab.
     *
     * <p>Wer entscheiden darf, klaert der Service an der geladenen Gruppe. Eine
     * unzustaendige Anfrage endet dort mit einer {@code AccessDeniedException},
     * die der {@code ApiExceptionHandler} zu einem 403 macht.</p>
     *
     * @param decision {@code approve} oder {@code reject}
     */
    @PostMapping("/requests/{requestId}/{decision}")
    public ResponseEntity<Void> decide(@PathVariable Long requestId,
                                       @PathVariable String decision) {
        groupService.decide(CurrentUser.characterId(), requestId, decision);
        return ResponseEntity.ok().build();
    }
}
