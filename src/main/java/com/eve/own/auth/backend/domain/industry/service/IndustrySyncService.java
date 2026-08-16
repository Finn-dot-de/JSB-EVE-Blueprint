package com.eve.own.auth.backend.domain.industry.service;

import com.eve.own.auth.backend.domain.assets.entity.MarketPrice;
import com.eve.own.auth.backend.domain.assets.repository.MarketPriceRepository;
import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.industry.IndustryActivity;
import com.eve.own.auth.backend.domain.industry.entity.CharacterBlueprint;
import com.eve.own.auth.backend.domain.industry.entity.IndustryJob;
import com.eve.own.auth.backend.domain.industry.repository.CharacterBlueprintRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryJobRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository;
import com.eve.own.auth.backend.esi.EsiService;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService.EsiBlueprintResponse;
import com.eve.own.auth.backend.esi.EsiService.EsiCorpStructureResponse;
import com.eve.own.auth.backend.esi.EsiService.EsiIndustryJobResponse;
import com.eve.own.auth.backend.esi.EsiService.EsiMarketPriceResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holt die Industriedaten aus ESI: Jobs, Blaupausen und Referenzpreise.
 *
 * <p>Ohne diesen Dienst steht der Fortschritt jedes Auftrags bei null - er kommt
 * aus dem Jobbuch, und das fuellt sich nur hier. Und ohne die Blaupausen rechnet
 * jeder Auftrag mit ME 0, weil Material- und Zeiteffizienz ausschliesslich in
 * ESI stehen und in keiner Stammdatentabelle.</p>
 *
 * <p>Fehler eines einzelnen Charakters beenden den Durchlauf nicht. Ein
 * widerrufenes Token, eine fehlende Ingame-Rolle oder ein 403 sind normale
 * Zustaende in einer Corporation - sie duerfen nicht dazu fuehren, dass auch
 * alle anderen Mitglieder veraltete Zahlen sehen.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndustrySyncService {

    /** Status, bei denen ein Job als abgeschlossen und geliefert gilt. */
    private static final String STATUS_DELIVERED = "delivered";

    /**
     * Wie viele Typen je Preisabfrage.
     *
     * <p>Fuzzwork vertraegt grosse Listen, aber die Typen stehen in der URL -
     * und die ist irgendwann zu lang. Derselbe Wert wie im bestehenden
     * Preisabgleich.</p>
     */
    private static final int PRICE_BATCH_SIZE = 200;

    private final CharacterRepository characterRepo;
    private final AuthService authService;
    private final EsiService esiService;
    private final IndustryJobRepository jobRepo;
    private final CharacterBlueprintRepository blueprintRepo;
    private final MarketPriceRepository priceRepo;
    private final IndustryStructureService structureService;
    private final IndustryQueryRepository queryRepo;

    // ===========================================================
    //  Jobs
    // ===========================================================

    /**
     * Holt die Industriejobs eines Charakters.
     *
     * @return wie viele Jobs geschrieben wurden, oder -1 bei einem Fehlschlag
     */
    @Transactional
    public int syncJobs(Character character) {
        String token;
        try {
            token = authService.getValidAccessToken(character);
        } catch (RuntimeException e) {
            log.debug("Kein gültiges Token für Charakter {}: {}", character.getId(), e.getMessage());
            return -1;
        }

        EsiIndustryJobResponse[] jobs;
        try {
            jobs = esiService.getIndustryJobs(character.getId(), token).dataOr(null);
        } catch (RuntimeException e) {
            // 403 kommt vor, wenn der Scope fehlt - das ist kein Grund zum Abbruch.
            log.debug("Industriejobs für Charakter {} nicht abrufbar: {}",
                    character.getId(), e.getMessage());
            return -1;
        }
        if (jobs == null) {
            // Unveraendert seit dem letzten Abruf (HTTP 304) - nichts zu tun.
            return 0;
        }

        List<IndustryJob> zeilen = new ArrayList<>(jobs.length);
        Instant jetzt = Instant.now();
        for (EsiIndustryJobResponse j : jobs) {
            if (j.job_id() == null) {
                continue;
            }
            IndustryJob zeile = jobRepo.findById(j.job_id()).orElseGet(IndustryJob::new);
            zeile.setJobId(j.job_id());
            zeile.setSource("CHARACTER");
            zeile.setOwnerCharacterId(character.getId());
            zeile.setInstallerId(j.installer_id() == null ? character.getId() : j.installer_id());
            zeile.setFacilityId(j.facility_id());
            zeile.setActivityIdEsi(j.activity_id());
            // Die Uebersetzung ist der Grund, warum Reaktionsjobs ueberhaupt
            // ankommen: ESI zaehlt sie als 9, die Stammdaten als 11.
            zeile.setActivityIdSde(IndustryActivity.sdeFromEsi(j.activity_id()));
            zeile.setBlueprintTypeId(j.blueprint_type_id());
            zeile.setProductTypeId(j.product_type_id());
            zeile.setRuns(j.runs() == null ? 0 : j.runs());
            zeile.setSuccessfulRuns(j.successful_runs());
            zeile.setStatus(j.status());
            zeile.setStartDate(j.start_date());
            zeile.setEndDate(j.end_date());
            zeile.setCompletedDate(j.completed_date());
            zeile.setUpdatedAt(jetzt);
            zeilen.add(zeile);
        }
        jobRepo.saveAll(zeilen);
        return zeilen.size();
    }

    // ===========================================================
    //  Blaupausen
    // ===========================================================

    /**
     * Holt die Blaupausen eines Charakters.
     *
     * <p>Die Zeilen werden vorher geloescht statt zusammengefuehrt: eine verkaufte
     * oder verbrauchte Blaupause soll verschwinden und nicht als Karteileiche
     * weiter ein ME vortaeuschen, das es nicht mehr gibt.</p>
     *
     * @return wie viele Blaupausen geschrieben wurden, oder -1 bei einem Fehlschlag
     */
    @Transactional
    public int syncBlueprints(Character character) {
        // Fehlschlaege auf WARN und nicht auf DEBUG. In Produktion steht das Log
        // auf INFO; jeder dieser Wege war dort unsichtbar, und "die Blaupausen
        // werden nicht eingelesen" liess sich nicht von "es gibt keine"
        // unterscheiden. Ein Sync, der stillschweigend nichts tut, ist schlimmer
        // als einer, der laut scheitert.
        String token;
        try {
            token = authService.getValidAccessToken(character);
        } catch (RuntimeException e) {
            log.warn("Blaupausen {}: kein gültiges Token ({}). Meist ein abgelaufener "
                    + "Refresh-Token - der Charakter muss sich neu anmelden.",
                    character.getName(), e.getMessage());
            return -1;
        }

        List<EsiBlueprintResponse> bps;
        try {
            EsiResponse<List<EsiBlueprintResponse>> antwort =
                    esiService.getCharacterBlueprints(character.getId(), token);
            if (antwort.notModified() && !antwort.hasData()) {
                // ESI sagt "unveraendert", liefert aber keinen Inhalt: der Body
                // war zu gross zum Zwischenspeichern. Der Bestand bleibt stehen,
                // das ist richtig - aber es muss nachvollziehbar sein, warum
                // hier nichts geschrieben wurde.
                log.info("Blaupausen {}: unverändert (304 ohne Inhalt) - Bestand bleibt.",
                        character.getName());
                return 0;
            }
            bps = antwort.dataOr(null);
        } catch (RuntimeException e) {
            log.warn("Blaupausen {} nicht abrufbar: {}. Prüfe den Scope "
                    + "esi-characters.read_blueprints.v1 - er fehlt, wenn die "
                    + "Anmeldung mit einer anderen ESI-Anwendung erfolgte.",
                    character.getName(), e.getMessage());
            return -1;
        }
        if (bps == null) {
            log.warn("Blaupausen {}: ESI antwortete ohne Inhalt.", character.getName());
            return 0;
        }

        blueprintRepo.deleteByCharacterId(character.getId());

        List<CharacterBlueprint> zeilen = new ArrayList<>(bps.size());
        Instant jetzt = Instant.now();
        for (EsiBlueprintResponse b : bps) {
            if (b.item_id() == null || b.type_id() == null) {
                continue;
            }
            CharacterBlueprint zeile = new CharacterBlueprint();
            zeile.setItemId(b.item_id());
            zeile.setCharacterId(character.getId());
            zeile.setTypeId(b.type_id());
            zeile.setLocationId(b.location_id());
            zeile.setLocationFlag(b.location_flag());
            zeile.setQuantity(b.quantity() == null ? 1 : b.quantity());
            zeile.setRuns(b.runs() == null ? -1 : b.runs());
            zeile.setMaterialEfficiency(b.material_efficiency() == null ? 0 : b.material_efficiency());
            zeile.setTimeEfficiency(b.time_efficiency() == null ? 0 : b.time_efficiency());
            // An den Laeufen erkannt und nicht an der Stueckzahl: ein frisch
            // gekaufter Stapel Originale hat eine positive Stueckzahl und waere
            // sonst faelschlich eine Kopie.
            zeile.setCopy(zeile.getRuns() != -1);
            zeile.setUpdatedAt(jetzt);
            zeilen.add(zeile);
        }
        blueprintRepo.saveAll(zeilen);
        return zeilen.size();
    }

    // ===========================================================
    //  Referenzpreise
    // ===========================================================

    /**
     * Holt die Referenzpreise von CCP.
     *
     * <p>Oeffentlich, ein einziger Aufruf fuer alle Typen. Geschrieben wird nur
     * {@code adjusted_price} - die Jita-Preise bleiben dem bestehenden
     * Preis-Scheduler ueberlassen, der sie aus einer anderen Quelle zieht.</p>
     *
     * @return wie viele Preise geschrieben wurden, oder -1 bei einem Fehlschlag
     */
    @Transactional
    public int syncAdjustedPrices() {
        EsiMarketPriceResponse[] preise;
        try {
            preise = esiService.getMarketPrices().dataOr(null);
        } catch (RuntimeException e) {
            log.warn("Referenzpreise nicht abrufbar: {}", e.getMessage());
            return -1;
        }
        if (preise == null) {
            return 0;
        }

        List<MarketPrice> zeilen = new ArrayList<>();
        Instant jetzt = Instant.now();
        for (EsiMarketPriceResponse p : preise) {
            if (p.type_id() == null || p.adjusted_price() == null) {
                // Ohne Referenzpreis nichts schreiben - eine fehlende Bewertung
                // soll sichtbar bleiben und nicht als Null durchgehen.
                continue;
            }
            MarketPrice zeile = priceRepo.findById(p.type_id()).orElseGet(() -> {
                MarketPrice neu = new MarketPrice();
                neu.setTypeId(p.type_id());
                return neu;
            });
            zeile.setAdjustedPrice(p.adjusted_price());
            if (zeile.getUpdatedAt() == null) {
                zeile.setUpdatedAt(jetzt);
            }
            zeilen.add(zeile);
        }
        priceRepo.saveAll(zeilen);
        log.info("{} Referenzpreise aktualisiert.", zeilen.size());
        return zeilen.size();
    }

    /**
     * Holt Jita-Preise fuer alles, was der Assistent bewerten muss.
     *
     * <p>Der bestehende Preisabgleich holt nur, was in den Hangars liegt. Fuer
     * die Bestandsbewertung genuegt das, fuer eine Beschaffungsfrage nicht:
     * ausgerechnet die Dinge, die man kaufen soll, liegen ja gerade <em>nicht</em>
     * im Hangar. Nachgezaehlt: von 186 komprimierten Erzen hatten zehn einen
     * Preis - und ohne den laesst sich nicht sagen, ob Erz oder Mineral
     * guenstiger ist.</p>
     *
     * <p>Fehlgeschlagene Bloecke werden gezaehlt, aber nicht wiederholt. Ein
     * Preis, der eine Stunde alt wird, ist unangenehm; ein Abgleich, der sich in
     * Wiederholungen festfrisst, ist schlimmer.</p>
     *
     * @return wie viele Preise geschrieben wurden
     */
    @Transactional
    public int syncIndustryPrices() {
        List<Long> typen = queryRepo.priceRelevantTypeIds();
        if (typen.isEmpty()) {
            return 0;
        }

        List<MarketPrice> zuSpeichern = new ArrayList<>();
        int fehlgeschlagen = 0;
        Instant jetzt = Instant.now();

        for (int i = 0; i < typen.size(); i += PRICE_BATCH_SIZE) {
            List<Long> block = typen.subList(i, Math.min(i + PRICE_BATCH_SIZE, typen.size()));
            try {
                var preise = esiService.getFuzzworkPrices(block);
                if (preise == null || preise.isEmpty()) {
                    fehlgeschlagen++;
                    continue;
                }
                for (Long typeId : block) {
                    var daten = preise.get(String.valueOf(typeId));
                    if (daten == null) {
                        continue;
                    }
                    MarketPrice zeile = priceRepo.findById(typeId).orElseGet(MarketPrice::new);
                    zeile.setTypeId(typeId);
                    zeile.setJitaBuy(daten.buy() != null ? daten.buy().max() : zeile.getJitaBuy());
                    zeile.setJitaSell(daten.sell() != null ? daten.sell().min() : zeile.getJitaSell());
                    zeile.setUpdatedAt(jetzt);
                    zuSpeichern.add(zeile);
                }
            } catch (RuntimeException e) {
                fehlgeschlagen++;
                log.warn("Preisblock ab {} fehlgeschlagen: {}", i, e.getMessage());
            }
        }

        priceRepo.saveAll(zuSpeichern);
        log.info("Industriepreise: {} von {} Typen aktualisiert, {} Blöcke fehlgeschlagen.",
                zuSpeichern.size(), typen.size(), fehlgeschlagen);
        return zuSpeichern.size();
    }

    // ===========================================================
    //  Bauorte
    // ===========================================================

    /**
     * Holt die Strukturen der Corporation samt ihrer Dienste.
     *
     * <p>Der einzige Weg, verlaesslich zu erfahren, ob in einer Struktur
     * gefertigt werden kann. Braucht neben dem Scope die Ingame-Rolle
     * Station_Manager - hat der Charakter sie nicht, antwortet ESI mit 403, und
     * genau das ist der Normalfall bei den meisten Mitgliedern. Deshalb wird der
     * Fehlschlag hier auch nur auf Debug-Ebene vermerkt.</p>
     *
     * @return wie viele Strukturen geschrieben wurden, oder -1 bei einem Fehlschlag
     */
    @Transactional
    public int syncCorpStructures(Character character) {
        if (character.getCorporation() == null || character.getCorporation().getId() == null) {
            return -1;
        }
        Long corporationId = character.getCorporation().getId();
        String token;
        try {
            token = authService.getValidAccessToken(character);
        } catch (RuntimeException e) {
            return -1;
        }

        List<EsiCorpStructureResponse> strukturen;
        try {
            strukturen = esiService
                    .getCorporationStructures(corporationId, token)
                    .dataOr(null);
        } catch (RuntimeException e) {
            log.debug("Corp-Strukturen über Charakter {} nicht abrufbar (fehlt die Rolle "
                    + "Station_Manager?): {}", character.getId(), e.getMessage());
            return -1;
        }
        if (strukturen == null) {
            return 0;
        }

        int geschrieben = 0;
        for (EsiCorpStructureResponse s : strukturen) {
            if (s.structure_id() == null) {
                continue;
            }
            List<String> laufendeDienste = s.services() == null ? List.of()
                    : s.services().stream()
                            .filter(d -> d != null && "online".equalsIgnoreCase(d.state()))
                            .map(EsiService.EsiStructureService::name)
                            .toList();
            structureService.upsertCorpStructure(
                    s.structure_id(), s.type_id(), s.system_id(),
                    s.corporation_id(), laufendeDienste, s.fuel_expires());
            geschrieben++;
        }
        return geschrieben;
    }

    // ===========================================================
    //  Alle Charaktere
    // ===========================================================

    /**
     * Gleicht Jobs und Blaupausen aller registrierten Charaktere ab.
     *
     * <p>Bewusst charakterweise mit eigener Fehlerbehandlung: faellt einer aus,
     * laufen die uebrigen weiter.</p>
     */
    public void syncAll() {
        List<Character> alle = characterRepo.findAll();
        int jobs = 0;
        int blaupausen = 0;
        int fehler = 0;

        for (Character c : alle) {
            int j = syncJobs(c);
            int b = syncBlueprints(c);
            if (j < 0 || b < 0) {
                fehler++;
            }
            jobs += Math.max(0, j);
            blaupausen += Math.max(0, b);
        }
        log.info("Industrie-Abgleich: {} Jobs, {} Blaupausen, {} von {} Charakteren ohne Zugriff.",
                jobs, blaupausen, fehler, alle.size());
    }

    /** Ob ein Job als geliefert gilt - nur dann zaehlt er auf einen Auftrag ein. */
    public static boolean isDelivered(IndustryJob job) {
        return STATUS_DELIVERED.equalsIgnoreCase(job.getStatus());
    }
}
