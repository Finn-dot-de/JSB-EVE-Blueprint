package com.eve.own.auth.backend.domain.academy.controller;

import com.eve.own.auth.backend.common.AccessRules;
import com.eve.own.auth.backend.common.CurrentUser;
import com.eve.own.auth.backend.domain.academy.dto.AcademyDtos;
import com.eve.own.auth.backend.domain.academy.service.AcademyService;
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
 * Die Pflege der Academy-Themen.
 *
 * <p>Klassenweites {@code @PreAuthorize} mit
 * {@link AccessRules#ACADEMY_AUTHORS}, weil hier eine feste Rolle die Grenze
 * zieht - anders als beim Sichtkreis auf die Namen, der zusaetzlich am
 * geladenen Thema haengt. Die Annotation wirkt, weil {@code SecurityConfig}
 * {@code @EnableMethodSecurity} traegt.</p>
 *
 * <p>Derselbe Kreis wird im {@link AcademyService} noch einmal geprueft. Das ist
 * keine Doppelung aus Unsicherheit: die Annotation haengt an diesem einen
 * Einstiegspunkt, faellt bei einem Umbau lautlos weg und schuetzt einen zweiten
 * Aufrufer gar nicht. Und was hier geschrieben wird, lesen alle - unter ihnen
 * die Konten mit den meisten Rechten. Ein Lehrplan ist genau der Ort, an dem
 * ein Director in Ruhe hineinschaut.</p>
 *
 * <p>Zur Einordnung, damit niemand sie ueberschaetzt: diese Rollenpruefung ist
 * <b>Zugangskontrolle, keine Sicherheitsgrenze</b>. {@code CharacterRoleService}
 * leitet die Rollen aus Ingame-Titeln ab - wer schreiben darf, wird also in EVE
 * Online vergeben, ausserhalb dieser Anwendung, und die Menge aendert sich ohne
 * jeden Vorgang in diesem Code. Die Sicherheitsgrenze ist die Bauweise des
 * Renderers, der aus dem Lehrplan nie einen HTML-String macht. Beides, nicht
 * eines statt des anderen.</p>
 *
 * <p>Der eigene Pfad {@code /api/admin/academy} haelt die Pflege von
 * {@code /api/academy} getrennt - dasselbe Muster wie bei Gruppen und
 * Navigation.</p>
 */
@RestController
@RequestMapping("/api/admin/academy")
@PreAuthorize(AccessRules.ACADEMY_AUTHORS)
public class AcademyAdminController {

    private final AcademyService academyService;

    public AcademyAdminController(AcademyService academyService) {
        this.academyService = academyService;
    }

    /**
     * Alle Themen, auch die abgeschalteten.
     *
     * <p>Der Unterschied zu {@code GET /api/academy/topics} ist genau dieser:
     * dort stehen nur die angebotenen. Ein abgeschaltetes Thema ist oft eines,
     * das gerade neu geschrieben wird.</p>
     */
    @GetMapping("/topics")
    public ResponseEntity<List<AcademyDtos.TopicDto>> topics() {
        return ResponseEntity.ok(academyService.allTopicsFor(CurrentUser.characterId()));
    }

    /**
     * Legt ein Thema an ({@code id == null}) oder aendert es.
     *
     * <p>Der Dienst weist dabei jede Bildquelle ab, deren Host nicht auf der
     * Allowlist steht, und nennt den Host in der Meldung - damit der Autor es
     * sofort erfaehrt und nicht erst der Leser ein fehlendes Bild sieht.</p>
     */
    @PostMapping("/topics")
    public ResponseEntity<AcademyDtos.TopicDto> save(
            @RequestBody AcademyDtos.SaveTopicDto dto) {
        return ResponseEntity.ok(academyService.saveTopic(CurrentUser.characterId(), dto));
    }

    /**
     * Loescht das Thema samt seiner Bekundungen.
     *
     * <p>Der Loeschende geht mit hinein, damit der Dienst denselben Riegel legen
     * kann wie beim Speichern - und nicht darauf angewiesen ist, dass ueber ihm
     * eine Annotation haengt.</p>
     */
    @DeleteMapping("/topics/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        academyService.deleteTopic(CurrentUser.characterId(), id);
        return ResponseEntity.noContent().build();
    }
}
