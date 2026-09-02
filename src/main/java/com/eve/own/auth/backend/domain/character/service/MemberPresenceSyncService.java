package com.eve.own.auth.backend.domain.character.service;

import com.eve.own.auth.backend.domain.character.entity.CorporationMemberPresence;
import com.eve.own.auth.backend.domain.character.repository.CorporationMemberPresenceRepository;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Schreibt Standort und Ein-/Ausloggzeiten der Corp-Mitglieder mit.
 *
 * <h2>Warum die Mitgliederverfolgung und nicht {@code /characters/{id}/location/}</h2>
 * <p>Der Standort-Endpunkt braucht ein eigenes Token. Fuer die Zielgruppe -
 * unregistrierte Charaktere - ist er damit <b>strukturell leer</b>: wer nie ein
 * Token hinterlegt hat, ueber den erfaehrt man auf diesem Weg nie etwas. Die
 * Mitgliederverfolgung kostet dagegen <em>einen</em> Aufruf je Corporation und
 * deckt jedes Mitglied ab. Genau deshalb kommen auch schon die Beitrittsdaten
 * der Alt-Erkennung von dort.</p>
 *
 * <h2>Warum ueberhaupt mitgeschrieben wird</h2>
 * <p>ESI nennt nur den <em>letzten</em> Logon und Logoff. Aus einem Momentanwert
 * laesst sich keine Korrelation bilden - und gemeinsames Ein- und Ausloggen im
 * Sekundenbereich ist die eigentliche Signatur des Multiboxings. Erst die Reihe
 * macht daraus ein Signal.</p>
 *
 * <h2>Warum nicht jeder Lauf schreibt</h2>
 * <p>Geschrieben wird nur, was sich gegenueber der letzten Zeile desselben
 * Charakters geaendert hat. Ein Mitglied im Urlaub erzeugt keine einzige Zeile.
 * Das ist der Unterschied zwischen einer Tabelle, die man aufbewahren kann, und
 * einer, die man loeschen muss - die Mengenrechnung steht im
 * {@code AltSourceScheduler}.</p>
 *
 * <p><b>ESI-Last:</b> ein Aufruf je Corporation und Lauf. Traegt das Token des
 * ersten Director-Kandidaten nicht, kommen die Fehlversuche des
 * {@link DirectorTokenProvider} hinzu - hoechstens einer je Kandidat.</p>
 */
@Slf4j
@Service
public class MemberPresenceSyncService {

    private final EsiService esiService;
    private final DirectorTokenProvider directorTokenProvider;
    private final AltSourceProperties properties;
    private final AltSourceStore store;
    private final CorporationMemberPresenceRepository presenceRepo;

    public MemberPresenceSyncService(EsiService esiService,
                                     DirectorTokenProvider directorTokenProvider,
                                     AltSourceProperties properties,
                                     AltSourceStore store,
                                     CorporationMemberPresenceRepository presenceRepo) {
        this.esiService = esiService;
        this.directorTokenProvider = directorTokenProvider;
        this.properties = properties;
        this.store = store;
        this.presenceRepo = presenceRepo;
    }

    /**
     * Ein Messpunkt fuer eine Corporation.
     *
     * <p><b>Ohne Antwort wird nichts geschrieben.</b> Kein Director-Token, ein
     * 403, ein 304 ohne zwischengespeicherten Rumpf - in all diesen Faellen
     * entsteht keine Zeile. Die Alternative waere eine Messung, die aussieht,
     * als sei niemand online gewesen; eine spaetere Auswertung koennte sie nicht
     * von einem echten leeren Zeitraum unterscheiden. Das ist derselbe
     * Fehlertyp, der bei Fuzzwork Nullpreise und beim Marktabzug halbe Summen
     * hinterlassen hat.</p>
     *
     * @return wieviele Zeilen geschrieben wurden - fuer das Protokoll des Zeitgebers
     */
    public int sync(Long corporationId) {
        if (!properties.isPresenceEnabled()) {
            return 0;
        }

        var attempt = directorTokenProvider.attempt(corporationId,
                AltDetectionService.TRACK_MEMBERS_SCOPE,
                token -> esiService.getCorporationMemberTracking(corporationId, token));

        EsiResponse<EsiService.EsiMemberTrackingResponse[]> response = attempt.value();
        if (!attempt.succeeded() || response == null || response.data() == null) {
            log.info("Mitgliederverfolgung fuer Corp {} nicht abrufbar - es wird KEINE "
                    + "Anwesenheitszeile geschrieben. Eine leere Messung waere von einem "
                    + "leeren Zeitraum nicht zu unterscheiden.", corporationId);
            return 0;
        }

        Instant measuredAt = Instant.now();
        Map<Long, CorporationMemberPresence> latest = latestPerCharacter(corporationId);
        Set<Long> seen = new HashSet<>();
        List<CorporationMemberPresence> changed = new ArrayList<>();

        for (EsiService.EsiMemberTrackingResponse entry : response.data()) {
            if (entry == null || entry.character_id() == null) {
                continue;
            }
            // Doppelte Charaktere in einer Antwort wuerden sonst zwei Zeilen mit
            // demselben Messzeitpunkt erzeugen und die Reihe verfaelschen.
            if (!seen.add(entry.character_id())) {
                continue;
            }
            CorporationMemberPresence row = toPresence(corporationId, entry, measuredAt);
            if (row.sameStateAs(latest.get(entry.character_id()))) {
                continue;
            }
            changed.add(row);
        }

        store.appendPresence(changed);
        return changed.size();
    }

    private Map<Long, CorporationMemberPresence> latestPerCharacter(Long corporationId) {
        Map<Long, CorporationMemberPresence> latest = new HashMap<>();
        for (CorporationMemberPresence row : presenceRepo.findLatestPerCharacter(corporationId)) {
            latest.put(row.getCharacterId(), row);
        }
        return latest;
    }

    private static CorporationMemberPresence toPresence(Long corporationId,
                                                        EsiService.EsiMemberTrackingResponse entry,
                                                        Instant measuredAt) {
        CorporationMemberPresence presence = new CorporationMemberPresence();
        presence.setCorporationId(corporationId);
        presence.setCharacterId(entry.character_id());
        // Fehlende Felder bleiben null. ESI sichert ausser der Charakter-ID
        // nichts zu; wer daraus 0 macht, behauptet einen Standort, den niemand
        // gemeldet hat.
        presence.setLocationId(entry.location_id());
        presence.setLogonDate(entry.logon_date());
        presence.setLogoffDate(entry.logoff_date());
        presence.setMeasuredAt(measuredAt);
        return presence;
    }
}
