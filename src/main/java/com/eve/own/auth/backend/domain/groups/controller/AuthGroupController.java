package com.eve.own.auth.backend.domain.groups.controller;

import com.eve.own.auth.backend.common.CurrentUser;
import com.eve.own.auth.backend.domain.groups.dto.AuthGroupDtos;
import com.eve.own.auth.backend.domain.groups.service.AuthGroupService;
import java.util.List;
import org.springframework.http.ResponseEntity;
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
