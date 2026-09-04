package com.eve.own.auth.backend.domain.fleet.controller;

import com.eve.own.auth.backend.common.AccessRules;
import com.eve.own.auth.backend.common.CurrentUser;
import com.eve.own.auth.backend.domain.fleet.PingErwaehnung;
import com.eve.own.auth.backend.domain.fleet.dto.FleetPingDtos.CancelRequest;
import com.eve.own.auth.backend.domain.fleet.dto.FleetPingDtos.PingRequest;
import com.eve.own.auth.backend.domain.fleet.dto.FleetPingDtos.PingResponse;
import com.eve.own.auth.backend.domain.fleet.dto.FleetPingDtos.PingRolleResponse;
import com.eve.own.auth.backend.domain.fleet.dto.FleetPingDtos.PingStatusResponse;
import com.eve.own.auth.backend.domain.fleet.service.FleetPingService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Die Endpunkte der Flotten-Pings.
 *
 * <p>{@link PreAuthorize} steht hier <em>zusaetzlich</em> zur Pruefung im
 * {@link FleetPingService} und nicht statt ihrer. Der Grund steht dort
 * ausgeschrieben; kurz: die Annotation gehoert zu einem Einstiegspunkt, die
 * Regel gehoert zur Sache.</p>
 */
@RestController
@RequestMapping("/api/fleet/pings")
public class FleetPingController {

    private final FleetPingService pingService;

    public FleetPingController(FleetPingService pingService) {
        this.pingService = pingService;
    }

    /**
     * Die Rechenschaftsliste: wer hat wann was gepingt.
     *
     * <p>Sichtbar fuer {@link AccessRules#FLEET_VIEWERS} und nicht nur fuer die
     * FCs selbst. Eine Rechenschaft, die nur der Kreis einsehen kann, ueber den
     * sie Rechenschaft ablegt, ist keine. Der Kreis ist derselbe, der auch die
     * uebrigen Flottendaten sieht.</p>
     */
    @PreAuthorize(AccessRules.FLEET_VIEWERS)
    @GetMapping
    public ResponseEntity<List<PingResponse>> letzte() {
        return ResponseEntity.ok(pingService.letzte().stream().map(PingResponse::von).toList());
    }

    /**
     * Ob die Funktion eingerichtet ist.
     *
     * <p>Fuer denselben Kreis wie die Liste und ohne Schreibrecht: Das Frontend
     * fragt hier, bevor es den Reiter ueberhaupt anbietet.</p>
     */
    @PreAuthorize(AccessRules.FLEET_VIEWERS)
    @GetMapping("/status")
    public ResponseEntity<PingStatusResponse> status() {
        boolean verfuegbar = pingService.istVerfuegbar();
        return ResponseEntity.ok(new PingStatusResponse(
                verfuegbar,
                pingService.istRolleKonfiguriert(),
                verfuegbar
                        ? null
                        : "Es ist kein Discord-Kanal fuer Flotten-Pings hinterlegt "
                                + "(DISCORD_FLEET_PING_CHANNEL_ID)."));
    }

    /**
     * Die Rollen, die sich anpingen lassen.
     *
     * <p>{@link AccessRules#FLEET_STAFF} und nicht der weitere Kreis der Liste:
     * Das hier ist keine Rechenschaft, sondern die Auswahl vor der Handlung -
     * sie gehoert denen, die handeln duerfen. Die Pruefung steht zusaetzlich im
     * Dienst, aus demselben Grund wie beim Absetzen.</p>
     *
     * <p>Ein eigener Endpunkt und kein Feld im Status: Der Status wird bei jedem
     * Oeffnen des Reiters geladen, diese Liste erst, wenn jemand wirklich eine
     * Rolle waehlen will - und sie kostet einen Aufruf zu Discord.</p>
     */
    @PreAuthorize(AccessRules.FLEET_STAFF)
    @GetMapping("/rollen")
    public ResponseEntity<List<PingRolleResponse>> rollen() {
        return ResponseEntity.ok(pingService.pingbareRollen(CurrentUser.characterId()).stream()
                .map(PingRolleResponse::von).toList());
    }

    @PreAuthorize(AccessRules.FLEET_STAFF)
    @PostMapping
    public ResponseEntity<PingResponse> senden(@RequestBody PingRequest request) {
        return ResponseEntity.ok(PingResponse.von(
                pingService.senden(CurrentUser.characterId(), toBefehl(request))));
    }

    @PreAuthorize(AccessRules.FLEET_STAFF)
    @PutMapping("/{id}")
    public ResponseEntity<PingResponse> bearbeiten(@PathVariable Long id,
                                                   @RequestBody PingRequest request) {
        return ResponseEntity.ok(PingResponse.von(
                pingService.bearbeiten(CurrentUser.characterId(), id, toBefehl(request))));
    }

    /**
     * Die Absage.
     *
     * <p>{@code POST .../absage} und nicht {@code DELETE}: Geloescht wird nichts.
     * Der Ping bleibt in der Liste stehen, und in Discord bleibt die Nachricht
     * stehen - sie sagt nur ab jetzt etwas anderes. Ein {@code DELETE} verspraeche
     * ein Verschwinden, das es nicht gibt.</p>
     */
    @PreAuthorize(AccessRules.FLEET_STAFF)
    @PostMapping("/{id}/absage")
    public ResponseEntity<PingResponse> absagen(@PathVariable Long id,
                                                @RequestBody(required = false) CancelRequest request) {
        String grund = request == null ? null : request.grund();
        return ResponseEntity.ok(PingResponse.von(
                pingService.absagen(CurrentUser.characterId(), id, grund)));
    }

    private static FleetPingService.PingBefehl toBefehl(PingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Ein Ping ohne Angaben ist kein Ping.");
        }
        return new FleetPingService.PingBefehl(
                request.fleetType(), request.doctrine(), request.formupLocation(),
                request.formupTime(), request.comms(), request.srpCovered(), request.notes(),
                // Die einzige Stelle, an der aus der Zeichenkette des Aufrufers
                // eine Lautstaerke wird - und sie faellt bei Unbekanntem auf
                // STILL zurueck.
                PingErwaehnung.of(request.erwaehnung()),
                // Die Rollenkennung geht ROH weiter. Sie hier schon zu pruefen
                // waere die zweite Stelle mit derselben Regel, und die zweite
                // ist die, die beim naechsten Umbau vergessen wird - geprueft
                // wird im Dienst, wo auch die Zuordnungen liegen.
                request.rolleId());
    }
}
