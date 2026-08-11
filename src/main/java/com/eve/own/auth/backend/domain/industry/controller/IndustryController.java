package com.eve.own.auth.backend.domain.industry.controller;

import com.eve.own.auth.backend.common.CurrentUser;
import com.eve.own.auth.backend.domain.industry.dto.IndustryDtos;
import com.eve.own.auth.backend.domain.industry.service.IndustryOrderService;
import com.eve.own.auth.backend.domain.industry.service.IndustryPlanningService;
import com.eve.own.auth.backend.domain.industry.service.IndustryStructureService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
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
 * Der Industrie-Assistent.
 *
 * <p>Keine Rollenpruefung, und das mit Absicht: die Sicht ist ohnehin auf das
 * eigene Konto begrenzt, und Produzieren soll jedes Mitglied koennen. Genau wie
 * bei der Selbstauskunft ueber die Assets kommt der Zugriffsrahmen <em>nicht</em>
 * aus der Anfrage, sondern aus dem angemeldeten Charakter - die Endpunkte
 * reichen nur dessen Nummer weiter, den Rest erzwingen die Dienste.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/industry")
@RequiredArgsConstructor
public class IndustryController {

    /** Wie viele Vorschlaege das Suchfeld hoechstens zeigt. */
    private static final int SEARCH_LIMIT = 25;

    private final IndustryPlanningService planning;
    private final IndustryOrderService orders;
    private final IndustryStructureService structures;
    private final com.eve.own.auth.backend.domain.industry.service.IndustrySyncService sync;
    private final com.eve.own.auth.backend.domain.character.repository.CharacterRepository characterRepo;

    // ===========================================================
    //  Planen
    // ===========================================================

    /** Vorschlagsliste fuer das Suchfeld - nur, was sich tatsaechlich bauen laesst. */
    @GetMapping("/search")
    public List<IndustryDtos.ProductHitDto> search(@RequestParam String q) {
        return planning.search(q, SEARCH_LIMIT);
    }

    /**
     * Rechnet einen Bauwunsch durch, ohne etwas anzulegen.
     *
     * <p>Man soll ausprobieren duerfen, ohne sich festzulegen.</p>
     *
     * <p>{@code buildSystemId} ist freiwillig und entscheidet, welches Material
     * als vorhanden gilt. Ohne Angabe zaehlt der Bestand aus ganz EVE - eine
     * Zahl, die beim Bauen an einem bestimmten Ort zu hoch ist und die die
     * Oberflaeche deshalb kenntlich machen muss.</p>
     */
    @GetMapping("/preview")
    public IndustryDtos.PlanPreviewDto preview(
            @RequestParam Long productTypeId,
            @RequestParam(defaultValue = "1") Long quantity,
            @RequestParam(defaultValue = "1") Integer depth,
            @RequestParam(required = false) Long buildSystemId) {
        return planning.preview(CurrentUser.characterId(), productTypeId, quantity, depth,
                buildSystemId);
    }

    /**
     * Liest die Blaupausen des Kontos sofort neu ein.
     *
     * <p>Der Zeitplan holt sie alle sechs Stunden. Wer gerade eine Blaupause
     * gekauft oder erforscht hat, will nicht sechs Stunden warten - und wer den
     * Verdacht hat, dass der Abruf gar nicht laeuft, braucht eine Antwort statt
     * eines Zeitplans. Die Rueckgabe sagt, wie viele Zeilen geschrieben wurden
     * und bei wie vielen Charakteren der Zugriff scheiterte.</p>
     */
    @PostMapping("/blueprints/sync")
    public Map<String, Object> syncBlueprints() {
        Long mainId = CurrentUser.characterId();
        int geschrieben = 0;
        int ohneZugriff = 0;
        List<String> betroffen = new ArrayList<>();

        for (var c : characterRepo.findAll()) {
            int n = sync.syncBlueprints(c);
            if (n < 0) {
                ohneZugriff++;
                betroffen.add(c.getName());
            } else {
                geschrieben += n;
            }
        }
        log.info("Blaupausen von Hand eingelesen ({}): {} Zeilen, {} ohne Zugriff.",
                mainId, geschrieben, ohneZugriff);
        return Map.of("written", geschrieben, "withoutAccess", ohneZugriff,
                "characters", betroffen);
    }

    /**
     * Bauorte nach Name, Strukturtyp oder System.
     *
     * <p>Antwortet mit dem, was bekannt ist - und sagt ausdruecklich, wenn es
     * nichts weiss: fuer fremde Strukturen liefert ESI keine Dienste, dann steht
     * dort "Dienste unbekannt" statt einer geratenen Zusage.</p>
     */
    @GetMapping("/locations")
    public List<IndustryDtos.LocationDto> locations(@RequestParam String q) {
        return structures.search(CurrentUser.characterId(), q, SEARCH_LIMIT);
    }

    // ===========================================================
    //  Auftraege
    // ===========================================================

    @GetMapping("/orders")
    public List<IndustryDtos.OrderSummaryDto> list() {
        return orders.list(CurrentUser.characterId());
    }

    @GetMapping("/orders/{orderId}")
    public IndustryDtos.OrderDetailDto detail(@PathVariable Long orderId) {
        return orders.detail(CurrentUser.characterId(), orderId);
    }

    /**
     * Die Einkaufsliste: was kaufen, wo, und was kostet der Weg.
     */
    @GetMapping("/orders/{orderId}/procurement")
    public IndustryDtos.ProcurementDto procurement(@PathVariable Long orderId) {
        return orders.procurement(CurrentUser.characterId(), orderId);
    }

    /** Reichen die vorhandenen Blaupausen samt ihrer Läufe? */
    @GetMapping("/orders/{orderId}/blueprints")
    public List<IndustryDtos.BlueprintCheckDto> blueprints(@PathVariable Long orderId) {
        return orders.blueprints(CurrentUser.characterId(), orderId);
    }

    @PostMapping("/orders")
    public IndustryDtos.OrderDetailDto create(
            @RequestBody IndustryDtos.CreateOrderRequest request) {
        return orders.create(CurrentUser.characterId(), request);
    }

    /** Stellt eine Zeile von Kaufen auf Bauen um - und loest dabei eine Ebene tiefer auf. */
    @PutMapping("/orders/{orderId}/decision")
    public IndustryDtos.OrderDetailDto decide(
            @PathVariable Long orderId,
            @RequestBody IndustryDtos.DecisionRequest request) {
        return orders.setDecision(CurrentUser.characterId(), orderId, request);
    }

    /**
     * Setzt alle Entscheidungen nach einer Voreinstellung.
     *
     * <p>BUY_ALL, COST_EFFICIENT oder BUILD_ALL. Danach lässt sich weiterhin
     * jede Zeile einzeln umstellen - die Voreinstellung ist ein Startpunkt.</p>
     */
    @PutMapping("/orders/{orderId}/strategy")
    public IndustryDtos.OrderDetailDto strategy(
            @PathVariable Long orderId,
            @RequestParam String strategy) {
        return orders.applyStrategy(CurrentUser.characterId(), orderId, strategy);
    }

    /**
     * Setzt oder ändert das Bausystem eines bestehenden Auftrags.
     *
     * <p>Ohne Bauort muss der Assistent beim Transport den teuersten Fall
     * annehmen und beim Bestand ganz EVE zusammenzählen. Beim Anlegen war der
     * Ort freiwillig - hier lässt er sich nachtragen. Danach wird neu gerechnet.</p>
     */
    @PutMapping("/orders/{orderId}/location")
    public IndustryDtos.OrderDetailDto location(
            @PathVariable Long orderId,
            @RequestBody IndustryDtos.BuildLocationRequest request) {
        return orders.setBuildLocation(CurrentUser.characterId(), orderId, request);
    }

    /**
     * Rechnet einen bestehenden Auftrag von Grund auf neu.
     *
     * <p>Die Bedarfstabelle ist eingefroren, damit der Fortschrittsbalken nicht
     * bei jedem Neuladen springt. Der Preis dafür: erforschte Blaupausen,
     * geänderte Marktpreise und behobene Rechenfehler erreichen einen einmal
     * angelegten Auftrag nie. Das hier ist der Weg zurück. Die
     * Kaufen/Bauen-Entscheidungen bleiben erhalten.</p>
     */
    @PutMapping("/orders/{orderId}/recalculate")
    public IndustryDtos.OrderDetailDto recalculate(@PathVariable Long orderId) {
        return orders.recalculate(CurrentUser.characterId(), orderId);
    }

    @PutMapping("/orders/{orderId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable Long orderId) {
        orders.cancel(CurrentUser.characterId(), orderId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/orders/{orderId}")
    public ResponseEntity<Void> delete(@PathVariable Long orderId) {
        orders.delete(CurrentUser.characterId(), orderId);
        return ResponseEntity.noContent().build();
    }
}
