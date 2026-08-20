package com.eve.own.auth.backend.domain.auth.controller;

import com.eve.own.auth.backend.common.AccessRules;
import com.eve.own.auth.backend.common.CurrentUser;
import com.eve.own.auth.backend.domain.auth.dto.RoleAssignmentDtos;
import com.eve.own.auth.backend.domain.auth.service.RoleAssignmentService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rollen einzelner Charaktere: zuweisen, entziehen, nachlesen.
 *
 * <p>Bis hierher liess sich eine Rolle nur mittelbar vergeben - ueber einen
 * Ingame-Titel oder ueber die Aufnahme in eine Gruppe. Diese Endpunkte sind der
 * unmittelbare Weg und damit der schaerfste des Rechtemodells: wer sie erreicht,
 * verteilt Rechte an beliebige Charaktere.</p>
 *
 * <p>Deshalb klassenweites {@code @PreAuthorize} wie beim
 * {@code AuthGroupAdminController}. Es wirkt, weil {@code SecurityConfig}
 * {@code @EnableMethodSecurity} traegt. Derselbe Kreis wird im
 * {@link RoleAssignmentService} noch einmal geprueft - keine Doppelung aus
 * Unsicherheit, sondern weil die Annotation zu diesem einen Einstiegspunkt
 * gehoert und bei einem Umbau lautlos wegfaellt.</p>
 *
 * <p>Der eigene Pfad {@code /api/roles} und nicht {@code /api/groups/roles}:
 * dort sitzt der {@code GroupManagementController} mit dem Rollen<em>katalog</em>
 * - welche Rollen es gibt. Hier geht es darum, wer sie hat. Zwei verschiedene
 * Fragen unter demselben Pfad waeren nur Anlass zur Verwechslung.</p>
 *
 * <p>Entziehen laeuft ueber {@code POST .../revoke} statt ueber {@code DELETE}:
 * der Vorgang traegt einen freiwilligen Grund im Rumpf, und ein
 * {@code DELETE} mit Rumpf ist bestenfalls geduldet. Der Grund gehoert
 * ausserdem nicht in die Adresszeile, wo er in jedem Zugriffsprotokoll
 * landet.</p>
 */
@RestController
@RequestMapping("/api/roles")
@PreAuthorize(AccessRules.LEADERSHIP_OR_IT)
public class RoleAssignmentController {

    private final RoleAssignmentService roleAssignmentService;

    public RoleAssignmentController(RoleAssignmentService roleAssignmentService) {
        this.roleAssignmentService = roleAssignmentService;
    }

    /**
     * Alle Rollen dieses Charakters samt der Frage, was ein Klick bewirken wuerde.
     *
     * <p>Das ist die Auskunft, mit der die Oberflaeche warnt, BEVOR jemand
     * klickt: welche Rolle sich entziehen laesst, welche der naechste Sync
     * zurueckbringt und welche ihn ueberhaupt ueberlebt.</p>
     */
    @GetMapping("/characters/{characterId}")
    public ResponseEntity<RoleAssignmentDtos.CharacterRolesDto> rolesOf(
            @PathVariable Long characterId) {
        return ResponseEntity.ok(
                roleAssignmentService.rolesOf(CurrentUser.characterId(), characterId));
    }

    /**
     * Gibt dem Charakter eine Rolle.
     *
     * <p>Der Handelnde kommt aus dem Sicherheitskontext und nicht aus dem Rumpf -
     * sonst schriebe der Aufrufer den Nachweis ueber sich selbst.</p>
     *
     * @return der geschriebene Nachweiseintrag
     */
    @PostMapping("/characters/{characterId}/grant")
    public ResponseEntity<RoleAssignmentDtos.RoleAuditDto> grant(
            @PathVariable Long characterId,
            @RequestBody RoleAssignmentDtos.ChangeRoleDto dto) {
        return ResponseEntity.ok(roleAssignmentService.grant(
                CurrentUser.characterId(), characterId, dto.roleName(), dto.reason()));
    }

    /**
     * Nimmt dem Charakter eine Rolle wieder ab.
     *
     * <p>Scheitert mit einer Meldung, wenn ein Ingame-Titel die Rolle vergibt -
     * der Entzug haette sonst zehn Minuten gehalten. Die Auskunft oben nennt
     * denselben Fall vorab.</p>
     *
     * @return der geschriebene Nachweiseintrag
     */
    @PostMapping("/characters/{characterId}/revoke")
    public ResponseEntity<RoleAssignmentDtos.RoleAuditDto> revoke(
            @PathVariable Long characterId,
            @RequestBody RoleAssignmentDtos.ChangeRoleDto dto) {
        return ResponseEntity.ok(roleAssignmentService.revoke(
                CurrentUser.characterId(), characterId, dto.roleName(), dto.reason()));
    }

    /** Wer diesem Charakter wann welche Rolle gab oder nahm. */
    @GetMapping("/characters/{characterId}/audit")
    public ResponseEntity<List<RoleAssignmentDtos.RoleAuditDto>> auditFor(
            @PathVariable Long characterId) {
        return ResponseEntity.ok(
                roleAssignmentService.auditFor(CurrentUser.characterId(), characterId));
    }

    /** Die juengsten Rollenaenderungen ueber alle Charaktere hinweg. */
    @GetMapping("/audit")
    public ResponseEntity<List<RoleAssignmentDtos.RoleAuditDto>> recentAudit() {
        return ResponseEntity.ok(roleAssignmentService.recentAudit(CurrentUser.characterId()));
    }
}
