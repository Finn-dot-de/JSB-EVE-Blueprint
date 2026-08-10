package com.eve.own.auth.backend.domain.assets.service;

import com.eve.own.auth.backend.domain.assets.entity.AssetLocation;
import com.eve.own.auth.backend.domain.assets.repository.AssetLocationRepository;
import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.esi.EsiService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Service
public class AssetLocationService {

    // Alles ab 1 Billion ist eine Upwell-Struktur und braucht ein ESI Token
    private static final long STRUCTURE_MIN = 1_000_000_000_000L;

    @PersistenceContext
    private EntityManager em;

    private final AssetLocationRepository locationRepo;
    private final CharacterRepository characterRepo;
    private final AuthService authService;
    private final EsiService esiService;

    public AssetLocationService(AssetLocationRepository locationRepo,
                                CharacterRepository characterRepo,
                                AuthService authService,
                                EsiService esiService) {
        this.locationRepo = locationRepo;
        this.characterRepo = characterRepo;
        this.authService = authService;
        this.esiService = esiService;
    }

    public Long resolveRootLocation(Map<Long, Long> assetsByItemId, Long locationId) {
        Long current = locationId;
        int guard = 0;
        // max. 12 Verschachtelungsebenen - schuetzt vor Zyklen in kaputten ESI-Daten
        while (current != null && assetsByItemId.containsKey(current) && guard++ < 12) {
            Long parent = assetsByItemId.get(current);
            if (parent == null || parent.equals(current)) break;
            current = parent;
        }
        return current;
    }

    // ACHTUNG: Kein @Transactional!
    // Dadurch rollt nicht der gesamte Batch zurück, falls ein einzelner Lookup platzt.
    public void resolvePendingLocations() {
        nachtragenOhneSystem();

        List<Long> pending = locationRepo.findUnresolvedLocationIds();
        if (pending.isEmpty()) {
            log.debug("Keine offenen Asset-Standorte zum Auflösen.");
            return;
        }

        log.info("Löse {} unbekannte Asset-Standorte auf...", pending.size());

        List<Long> publicIds = new ArrayList<>();
        List<Long> structureIds = new ArrayList<>();

        // Aufteilen in öffentliche IDs (Stationen/Systeme) und Spieler-Strukturen
        for (Long id : pending) {
            if (id == null) continue;
            if (id >= STRUCTURE_MIN) {
                structureIds.add(id);
            } else {
                publicIds.add(id);
            }
        }

        int resolved = 0;
        resolved += resolvePublicViaUniverseNames(publicIds);
        resolved += resolveStructuresFromEsi(structureIds);

        log.info("Asset-Standorte aufgelöst: {} von {}.", resolved, pending.size());
    }

    /**
     * Traegt das Sonnensystem bei Standorten nach, die schon einen Namen haben.
     *
     * <p>Diese Standorte gelten als aufgeloest und tauchen in
     * {@link AssetLocationRepository#findUnresolvedLocationIds()} nie wieder auf -
     * ein Name ist ja da. Ohne Sonnensystem laesst sich aber nicht sagen, ob das
     * Material dort am Bauort liegt. Sie blieben also fuer immer blind, ohne dass
     * es jemandem auffiele.</p>
     */
    private void nachtragenOhneSystem() {
        List<Long> ids = locationRepo.findLocationIdsWithoutSystem();
        if (ids.isEmpty()) {
            return;
        }
        int nachgetragen = 0;
        for (Long id : ids) {
            AssetLocation loc = locationRepo.findById(id).orElse(null);
            if (loc == null) {
                continue;
            }
            enrichStation(loc);
            enrichSystem(loc);
            if (loc.getSystemId() != null) {
                locationRepo.save(loc);
                nachgetragen++;
            }
        }
        log.info("Sonnensystem nachgetragen bei {} von {} Standorten.", nachgetragen, ids.size());
    }

    // ==================================================================
    // NEU: Bulk API /universe/names/ für alles unter 1 Trillion
    // ==================================================================
    private int resolvePublicViaUniverseNames(List<Long> ids) {
        if (ids.isEmpty()) return 0;
        int count = 0;

        for (int i = 0; i < ids.size(); i += 500) {
            List<Long> batch = ids.subList(i, Math.min(i + 500, ids.size()));

            try {
                EsiService.EsiIdName[] names = esiService.getUniverseNames(batch);
                if (names != null && names.length > 0) {
                    for (EsiService.EsiIdName idName : names) {
                        AssetLocation loc = locationRepo.findById(idName.id()).orElseGet(AssetLocation::new);
                        loc.setLocationId(idName.id());
                        loc.setName(idName.name());
                        loc.setLocationKind(idName.category().toUpperCase());
                        loc.setResolveFailed(false);
                        loc.setResolvedAt(Instant.now());

                        // Das Sonnensystem fehlt hier noch: /universe/names/ gibt
                        // nur Kennung, Name und Kategorie zurueck.
                        enrichStation(loc);
                        // Optional: System/Region per SDE anreichern, falls ESI nur die Stations-ID liefert
                        enrichSystem(loc);

                        locationRepo.save(loc);
                        count++;
                    }
                }
            } catch (Exception e) {
                log.error("Fehler beim Bulk-Resolve der Public IDs über Universe Names: {}", e.getMessage());
            }
        }
        return count;
    }

    // ==================================================================
    // ESI: Upwell-Strukturen (Bleibt gleich, ESI Token nötig)
    // ==================================================================
    /** Ab so vielen 403ern in Folge gilt der Token als grundsaetzlich ohne Struktur-Zugriff (Scope/Docking). */
    private static final int FORBIDDEN_CIRCUIT_BREAKER = 5;

    private int resolveStructuresFromEsi(List<Long> ids) {
        if (ids.isEmpty()) return 0;
        String token = findStructureToken();
        if (token == null) {
            log.warn("Kein Token mit Struktur-Zugriff gefunden - {} Strukturen bleiben unbenannt.", ids.size());
            for (Long id : ids) saveUnknownStructure(id);
            return 0;
        }

        int count = 0;
        int consecutiveForbidden = 0;

        for (Long id : ids) {
            try {
                var info = esiService.getStructureInfo(id, token);
                if (info == null || info.name() == null) {
                    saveUnknownStructure(id);
                    consecutiveForbidden = 0;
                    continue;
                }
                AssetLocation loc = locationRepo.findById(id).orElseGet(AssetLocation::new);
                loc.setLocationId(id);
                loc.setName(info.name());
                loc.setOwnerId(info.owner_id());
                loc.setSystemId(info.solar_system_id());
                loc.setLocationKind("STRUCTURE");
                loc.setResolveFailed(false);
                loc.setResolvedAt(Instant.now());
                enrichSystem(loc);
                locationRepo.save(loc);
                count++;
                consecutiveForbidden = 0;
            } catch (RestClientResponseException e) {
                int statusCode = e.getStatusCode().value();
                log.debug("Struktur {} nicht auflösbar: {}", id, e.getMessage());

                if (statusCode == 420) {
                    log.warn("ESI Error-Rate-Limit (420) beim Aufloesen von Strukturen erreicht - breche ab.");
                    break;
                }
                if (statusCode == 403) {
                    consecutiveForbidden++;
                    if (consecutiveForbidden >= FORBIDDEN_CIRCUIT_BREAKER) {
                        log.warn("{} Strukturen in Folge mit 403 Forbidden - Token hat vermutlich keinen " +
                                "Struktur-Zugriff (Scope oder Docking fehlt). Breche fuer diesen Lauf ab.",
                                consecutiveForbidden);
                        break;
                    }
                    continue;
                }
                saveUnknownStructure(id);
                consecutiveForbidden = 0;
            } catch (Exception e) {
                log.debug("Struktur {} nicht auflösbar: {}", id, e.getMessage());
                saveUnknownStructure(id);
                consecutiveForbidden = 0;
            }
        }
        return count;
    }

    /**
     * Holt das Sonnensystem einer NPC-Station.
     *
     * <p>Der Umweg ueber einen zweiten ESI-Aufruf ist noetig, weil
     * {@code /universe/names/} zu einer Station nur Kennung, Name und Kategorie
     * kennt - und dieser SDE-Abzug die Stationen ueberhaupt nicht enthaelt:
     * {@code mapDenormalize} fuehrt keine Stations-Kennungen, eine Tabelle
     * {@code staStations} gibt es nicht. Ohne den Aufruf bliebe jede Station
     * ohne System, und zwar dauerhaft: sie gilt als aufgeloest und wird nie
     * wieder nachgeschlagen.</p>
     *
     * <p>Ein Fehlschlag ist kein Grund abzubrechen - der Name ist schon da, und
     * ein Standort ohne System ist besser als gar keiner.</p>
     */
    private void enrichStation(AssetLocation loc) {
        if (loc.getSystemId() != null || !"STATION".equals(loc.getLocationKind())) {
            return;
        }
        try {
            var info = esiService.getStationInfo(loc.getLocationId());
            if (info != null && info.system_id() != null) {
                loc.setSystemId(info.system_id());
            }
        } catch (Exception e) {
            log.debug("Station {} ohne System: {}", loc.getLocationId(), e.getMessage());
        }
    }

    private void enrichSystem(AssetLocation loc) {
        // Falls wir eine Station sind, haben wir evt. systemId von ESI.
        // Falls wir ein Solar System sind, ist die locationId selbst die System-ID.
        if (loc.getSystemId() == null && !loc.getLocationKind().equals("SOLAR_SYSTEM")) return;

        Long searchId = loc.getSystemId() != null ? loc.getSystemId() : loc.getLocationId();

        // Und diese Erkenntnis auch festhalten. Sie wurde hier lange nur fuer die
        // Namensabfrage benutzt und danach weggeworfen: alle Standorte der Art
        // SOLAR_SYSTEM trugen einen Systemnamen, aber keine system_id. Wer danach
        // filtert - etwa nach dem Bausystem eines Industrieauftrags - findet dort
        // nichts, obwohl das System bekannt ist.
        loc.setSystemId(searchId);

        try {
            Query q = em.createNativeQuery("""
                    SELECT s."solarSystemName" AS "systemName",
                           s."regionID"        AS "regionId",
                           r."regionName"      AS "regionName"
                    FROM evesde."mapSolarSystems" s
                    LEFT JOIN evesde."mapRegions" r ON r."regionID" = s."regionID"
                    WHERE s."solarSystemID" = :id
                    """, Tuple.class);
            q.setParameter("id", searchId);

            @SuppressWarnings("unchecked")
            List<Tuple> rows = q.getResultList();
            if (!rows.isEmpty()) {
                Tuple t = rows.get(0);
                loc.setSystemName(text(t, "systemName"));
                loc.setRegionId(num(t, "regionId"));
                loc.setRegionName(text(t, "regionName"));
            }
        } catch (Exception e) {
            log.debug("System-Anreicherung fehlgeschlagen: {}", e.getMessage());
        }
    }

    private void saveUnknownStructure(Long id) {
        AssetLocation loc = locationRepo.findById(id).orElseGet(AssetLocation::new);
        loc.setLocationId(id);
        if (loc.getName() == null) loc.setName("Struktur ohne Docking-Access (" + id + ")");
        loc.setLocationKind("STRUCTURE");
        loc.setResolveFailed(true);
        loc.setResolvedAt(Instant.now());
        locationRepo.save(loc);
    }

    private String findStructureToken() {
        List<Character> candidates = characterRepo.findAllWithCorporation().stream()
                .filter(c -> c.getRefreshToken() != null)
                .sorted((a, b) -> Integer.compare(rank(b), rank(a)))
                .toList();
        for (Character c : candidates) {
            try {
                String token = authService.getValidAccessToken(c);
                if (token != null) return token;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private int rank(Character c) {
        if (c.getRoles() == null) return 0;
        if (c.getRoles().contains("ROLE_CEO")) return 3;
        if (c.getRoles().contains("ROLE_DIRECTOR")) return 2;
        if (c.getRoles().contains("ROLE_IT_ADMIN")) return 1;
        return 0;
    }

    private static Long num(Tuple t, String alias) {
        try {
            Object v = t.get(alias);
            return v == null ? null : ((Number) v).longValue();
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(Tuple t, String alias) {
        try {
            Object v = t.get(alias);
            return v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            return null;
        }
    }
}