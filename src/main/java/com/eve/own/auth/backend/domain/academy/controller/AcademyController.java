package com.eve.own.auth.backend.domain.academy.controller;

import com.eve.own.auth.backend.common.CurrentUser;
import com.eve.own.auth.backend.domain.academy.dto.AcademyDtos;
import com.eve.own.auth.backend.domain.academy.service.AcademyService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Die Academy, wie jedes Mitglied sie sieht: Themen lesen, Interesse bekunden,
 * Interesse zuruecknehmen.
 *
 * <p>Kein {@code @PreAuthorize} auf Klassenebene, und auch keines an der
 * Namensliste - hier zieht keine feste Rolle die Grenze: wer die Namen der
 * Interessenten sehen darf, haengt zusaetzlich an den {@code teacherRoleNames}
 * des <em>geladenen</em> Themas und laesst sich in einem SpEL-Ausdruck gar nicht
 * ausdruecken. Diese Pruefung sitzt deshalb vollstaendig im
 * {@link AcademyService}. Dass ueberhaupt jemand angemeldet ist, stellt
 * {@code SecurityConfig} mit {@code anyRequest().authenticated()} sicher.</p>
 *
 * <p>Interesse bekunden traegt bewusst keinen Rechtekreis: {@code ROLE_USER} ist
 * die einzige bedingungslose Rolle, und alles Strengere sperrte genau die
 * Neulinge aus, fuer die eine Academy da ist.</p>
 *
 * <p>Die Pflege der Themen liegt unter {@code /api/admin/academy}, im
 * {@link AcademyAdminController} - dasselbe Muster wie bei den Gruppen und der
 * Navigation.</p>
 */
@RestController
@RequestMapping("/api/academy")
public class AcademyController {

    private final AcademyService academyService;

    public AcademyController(AcademyService academyService) {
        this.academyService = academyService;
    }

    /**
     * Die angebotenen Themen samt Nachfragebild - <b>ohne</b> die Lehrplaene.
     *
     * <p>Der Lehrplan kommt erst beim Aufklappen ueber
     * {@link #topic(Long)}. Ginge er hier mit, gingen bei zwoelf Themen zwoelf
     * Lehrplaene ueber die Leitung, bei jedem Laden.</p>
     */
    @GetMapping("/topics")
    public ResponseEntity<List<AcademyDtos.TopicDto>> topics() {
        return ResponseEntity.ok(academyService.topicsFor(CurrentUser.characterId()));
    }

    /** Ein Thema samt Lehrplan - die Antwort auf das Aufklappen einer Karte. */
    @GetMapping("/topics/{id}")
    public ResponseEntity<AcademyDtos.TopicDetailDto> topic(@PathVariable Long id) {
        return ResponseEntity.ok(academyService.topicDetail(CurrentUser.characterId(), id));
    }

    /**
     * Bekundet Interesse oder schreibt die bestehende Bekundung um.
     *
     * <p>{@code PUT} und nicht {@code POST}, weil der Aufruf idempotent ist: er
     * identifiziert sich vollstaendig aus Pfad und Sitzung, und ein zweiter
     * Aufruf erzeugt keine zweite Zeile.</p>
     *
     * <p>Der Account kommt aus dem Sicherheitskontext und nicht aus dem Pfad
     * oder dem Rumpf. Ein Parameter dafuer waere eine Hintertuer, jedem
     * beliebigen Mitglied eine beliebige Bekundung unterzuschieben - siehe
     * {@code AcademyDtos.SaveInterestDto}.</p>
     *
     * @return das Thema mit frisch gerechneten Zaehlern, damit die Oberflaeche
     *     die eine Karte umschreiben kann statt die ganze Liste neu zu laden
     */
    @PutMapping("/topics/{id}/interest")
    public ResponseEntity<AcademyDtos.TopicDto> saveInterest(
            @PathVariable Long id,
            @RequestBody AcademyDtos.SaveInterestDto dto) {
        return ResponseEntity.ok(
                academyService.saveInterest(CurrentUser.characterId(), id, dto));
    }

    /**
     * Zieht die eigene Bekundung zurueck.
     *
     * <p>{@code DELETE}, weil die Zeile wirklich verschwindet: es gibt keinen
     * Zustand "zurueckgezogen", weil es nie einen Antrag gab.</p>
     */
    @DeleteMapping("/topics/{id}/interest")
    public ResponseEntity<Void> withdrawInterest(@PathVariable Long id) {
        academyService.withdrawInterest(CurrentUser.characterId(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Wer Interesse bekundet hat, mit Namen.
     *
     * <p>Der einzige Endpunkt dieses Controllers mit einem eigenen Rechtekreis -
     * und der einzige ohne Annotation dafuer: der Kreis ist der Autorenkreis
     * <em>plus</em> die am Thema hinterlegten Ausbilderrollen, und der zweite
     * Teil steht erst im geladenen Thema. Ein {@code @PreAuthorize} koennte hier
     * also nur die Haelfte der Regel behaupten und muesste die andere trotzdem
     * dem Dienst ueberlassen - eine halbe Regel an einer Annotation ist
     * schlechter als gar keine, weil sie beim Lesen vollstaendig aussieht.</p>
     *
     * <p>Ein Unberechtigter bekommt 403 und keine leere Liste; eine leere Liste
     * behauptete, niemand habe Interesse.</p>
     */
    @GetMapping("/topics/{id}/interest")
    public ResponseEntity<List<AcademyDtos.InterestDto>> interested(@PathVariable Long id) {
        return ResponseEntity.ok(academyService.interestedIn(CurrentUser.characterId(), id));
    }
}
