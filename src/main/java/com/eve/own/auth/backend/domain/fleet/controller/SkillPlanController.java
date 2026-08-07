package com.eve.own.auth.backend.domain.fleet.controller;

import com.eve.own.auth.backend.common.AccessRules;
import com.eve.own.auth.backend.common.CurrentUser;
import com.eve.own.auth.backend.domain.fleet.dto.SkillPlanDtos;
import com.eve.own.auth.backend.domain.fleet.service.SkillPlanService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Die Endpunkte fuer die Skillplaene.
 *
 * <p>Lesen darf jedes angemeldete Mitglied - wer wissen will, worauf er
 * hintrainieren soll, muss den Plan sehen koennen. Geaendert wird er nur von
 * der Flottenfuehrung.</p>
 */
@RestController
@RequestMapping("/api/skill-plans")
public class SkillPlanController {

    /** Genug Treffer zur Auswahl, ohne die Liste unbrauchbar lang zu machen. */
    private static final int SEARCH_LIMIT = 25;

    private final SkillPlanService skillPlanService;

    public SkillPlanController(SkillPlanService skillPlanService) {
        this.skillPlanService = skillPlanService;
    }

    @GetMapping
    public ResponseEntity<List<SkillPlanDtos.SkillPlanDto>> list() {
        return ResponseEntity.ok(skillPlanService.list());
    }

    /** Skill-Vorschlaege fuer den Plus-Knopf. */
    @PreAuthorize(AccessRules.FLEET_STAFF_OR_IT)
    @GetMapping("/skills")
    public ResponseEntity<List<SkillPlanDtos.SkillOptionDto>> searchSkills(@RequestParam String q) {
        return ResponseEntity.ok(skillPlanService.searchSkills(q, SEARCH_LIMIT));
    }

    @PreAuthorize(AccessRules.FLEET_STAFF_OR_IT)
    @PostMapping
    public ResponseEntity<SkillPlanDtos.SkillPlanDto> save(
            @RequestBody SkillPlanDtos.SaveSkillPlanDto dto) {
        return ResponseEntity.ok(skillPlanService.save(CurrentUser.characterId(), dto));
    }

    @PreAuthorize(AccessRules.FLEET_STAFF_OR_IT)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        skillPlanService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Wandelt einen eingefuegten Plantext in Eintraege um, ohne etwas zu speichern. */
    @PreAuthorize(AccessRules.FLEET_STAFF_OR_IT)
    @PostMapping("/import")
    public ResponseEntity<SkillPlanDtos.ImportResultDto> importPlanText(
            @RequestBody SkillPlanDtos.ImportRequestDto request) {
        return ResponseEntity.ok(skillPlanService.importPlanText(request.planText()));
    }

    /** Legt fest, welche Plaene an einem Fitting haengen. */
    @PreAuthorize(AccessRules.FLEET_STAFF_OR_IT)
    @PutMapping("/assign/{doctrineId}")
    public ResponseEntity<Void> assign(@PathVariable Long doctrineId,
                                       @RequestBody SkillPlanDtos.AssignPlansDto dto) {
        skillPlanService.assignToDoctrine(doctrineId, dto.planIds());
        return ResponseEntity.ok().build();
    }
}
