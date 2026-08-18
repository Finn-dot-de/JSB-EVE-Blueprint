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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Die Pflege der Gruppen (SIGs).
 *
 * <p>Wer hier anlegt, aendert oder loescht, bestimmt ueber eine Rolle - und
 * ueber die Leitung auch darueber, wer sie kuenftig vergeben darf. Deshalb
 * durchgehend der Fuehrung und der technischen Administration vorbehalten,
 * mit klassenweitem {@code @PreAuthorize} wie beim
 * {@code NavigationAdminController}. Die Annotation wirkt, weil
 * {@code SecurityConfig} {@code @EnableMethodSecurity} traegt.</p>
 *
 * <p>Derselbe Kreis wird im {@code AuthGroupService} noch einmal geprueft. Das
 * ist keine Doppelung aus Unsicherheit: die Annotation haengt an diesem einen
 * Einstiegspunkt, faellt bei einem Umbau lautlos weg und schuetzt einen zweiten
 * Aufrufer gar nicht. Ein Loch hier waere das gefaehrlichste des ganzen
 * Features - wer eine Gruppe anlegen kann, setzt sich selbst als Leitung ein
 * und vergibt sich danach jede Rolle am Antragsweg vorbei.</p>
 *
 * <p>Der eigene Pfad {@code /api/admin/groups} ist noetig, weil unter
 * {@code /api/groups} bereits der {@code GroupManagementController} sitzt; ein
 * {@code POST /api/groups} kaeme dessen Rollen- und Titelpflege in die Quere.</p>
 *
 * <p>Fuer die Rollen-Auswahlfelder gibt es hier bewusst keinen Endpunkt:
 * {@code GET /api/groups/roles} liefert den Rollenkatalog bereits und ist an
 * denselben Personenkreis gebunden. Das gilt seit der Umstellung auch fuer die
 * Leitung - sie ist eine Rolle und keine Person mehr, weshalb das fruehere
 * {@code /leader-candidates} (die registrierten Main-Charaktere) ersatzlos
 * entfallen ist.</p>
 */
@RestController
@RequestMapping("/api/admin/groups")
@PreAuthorize(AccessRules.LEADERSHIP_OR_IT)
public class AuthGroupAdminController {

    private final AuthGroupService groupService;

    public AuthGroupAdminController(AuthGroupService groupService) {
        this.groupService = groupService;
    }

    /** Alle Gruppen - derselbe Datensatz wie im Reiter "Gruppen". */
    @GetMapping
    public ResponseEntity<List<AuthGroupDtos.GroupDto>> overview() {
        return ResponseEntity.ok(groupService.groupsFor(CurrentUser.characterId()));
    }

    /**
     * Legt eine Gruppe an ({@code id == null}) oder aendert sie.
     *
     * <p>Der Service sorgt dabei dafuer, dass die zugehoerige Rolle existiert und
     * als speziell markiert ist - andernfalls raeumte der naechste Rollen-Sync
     * jede Mitgliedschaft wieder ab. Ein leerer Rollenname ist deshalb kein
     * Fehler mehr: der Dienst leitet ihn aus dem Gruppennamen ab.</p>
     */
    @PostMapping
    public ResponseEntity<AuthGroupDtos.GroupDto> save(
            @RequestBody AuthGroupDtos.SaveGroupDto dto) {
        return ResponseEntity.ok(groupService.saveGroup(CurrentUser.characterId(), dto));
    }

    /**
     * Loescht die Gruppe samt ihrer Anfragen; die Rolle selbst bleibt bestehen.
     *
     * <p>Der Loeschende geht mit hinein, damit der Dienst denselben Riegel legen
     * kann wie beim Speichern - und nicht darauf angewiesen ist, dass ueber ihm
     * eine Annotation haengt.</p>
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        groupService.deleteGroup(CurrentUser.characterId(), id);
        return ResponseEntity.noContent().build();
    }
}
