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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loest die location_id der Assets in lesbare Ortsnamen auf.
 *
 * ESI liefert nur nackte IDs. Die Zuordnung laeuft ueber den Zahlenbereich:
 *  - 30.000.000 - 32.000.000  Sonnensystem      -> SDE
 *  - 60.000.000 - 64.000.000  NPC-Station       -> SDE (mapDenormalize)
 *  - >= 1.000.000.000.000     Upwell-Struktur   -> ESI (Token + Docking-Access noetig)
 *
 * Ergebnisse landen dauerhaft in asset_locations, damit die Suche nur noch joint.
 */
@Slf4j
@Service
public class AssetLocationService {

    private static final long SYSTEM_MIN = 30_000_000L;
    private static final long SYSTEM_MAX = 32_000_000L;
    private static final long STATION_MIN = 60_000_000L;
    private static final long STATION_MAX = 64_000_000L;
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

    /**
     * Berechnet fuer jedes Asset den "echten" Aufbewahrungsort, indem die
     * Container-Kette hochgelaufen wird (Item in Container in Schiff in Station).
     *
     * @param assetsByItemId alle Assets eines Charakters, indexiert nach item_id
     * @param locationId     die direkte location_id des Assets
     * @return die ID von Station / Struktur / System
     */
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

    /**
     * Holt Namen fuer alle noch unbekannten Standorte nach.
     * Wird vom Scheduler aufgerufen - bewusst nach dem Asset-Sync.
     */
    @Transactional
    public void resolvePendingLocations() {
        List<Long> pending = locationRepo.findUnresolvedLocationIds();
        if (pending.isEmpty()) {
            log.debug("Keine offenen Asset-Standorte zum Aufloesen.");
            return;
        }
        log.info("Loese {} unbekannte Asset-Standorte auf...", pending.size());

        List<Long> sdeIds = new ArrayList<>();
        List<Long> structureIds = new ArrayList<>();

        for (Long id : pending) {
            if (id == null) continue;
            if (id >= STRUCTURE_MIN) structureIds.add(id);
            else sdeIds.add(id);
        }

        int resolved = 0;
        resolved += resolveFromSde(sdeIds);
        resolved += resolveStructuresFromEsi(structureIds);

        log.info("Asset-Standorte aufgeloest: {} von {}.", resolved, pending.size());
    }

    // ------------------------------------------------------------------
    // SDE: Stationen, Systeme, Celestials
    // ------------------------------------------------------------------

    private int resolveFromSde(List<Long> ids) {
        if (ids.isEmpty()) return 0;
        int count = 0;

        for (int i = 0; i < ids.size(); i += 500) {
            List<Long> batch = ids.subList(i, Math.min(i + 500, ids.size()));
            Map<Long, AssetLocation> found = new HashMap<>();

            // 1) mapDenormalize deckt Stationen und Celestials ab
            try {
                Query q = em.createNativeQuery("""
                        SELECT m."itemID"           AS "id",
                               m."itemName"         AS "name",
                               m."solarSystemID"    AS "systemId",
                               s."solarSystemName"  AS "systemName",
                               m."regionID"         AS "regionId",
                               r."regionName"       AS "regionName"
                        FROM evesde."mapDenormalize" m
                        LEFT JOIN evesde."mapSolarSystems" s ON s."solarSystemID" = m."solarSystemID"
                        LEFT JOIN evesde."mapRegions" r ON r."regionID" = m."regionID"
                        WHERE m."itemID" IN (:ids)
                        """, Tuple.class);
                q.setParameter("ids", batch);
                @SuppressWarnings("unchecked")
                List<Tuple> rows = q.getResultList();
                for (Tuple t : rows) {
                    Long id = num(t, "id");
                    if (id == null) continue;
                    AssetLocation loc = new AssetLocation();
                    loc.setLocationId(id);
                    loc.setName(text(t, "name"));
                    loc.setSystemId(num(t, "systemId"));
                    loc.setSystemName(text(t, "systemName"));
                    loc.setRegionId(num(t, "regionId"));
                    loc.setRegionName(text(t, "regionName"));
                    loc.setLocationKind(isStation(id) ? "STATION" : (isSystem(id) ? "SYSTEM" : "UNKNOWN"));
                    found.put(id, loc);
                }
            } catch (Exception e) {
                log.warn("mapDenormalize-Lookup fehlgeschlagen: {}", e.getMessage());
            }

            // 2) Sonnensysteme, die in mapDenormalize keinen brauchbaren Eintrag hatten
            List<Long> missingSystems = batch.stream()
                    .filter(AssetLocationService::isSystem)
                    .filter(id -> !found.containsKey(id) || found.get(id).getSystemName() == null)
                    .toList();

            if (!missingSystems.isEmpty()) {
                try {
                    Query q = em.createNativeQuery("""
                            SELECT s."solarSystemID"   AS "id",
                                   s."solarSystemName" AS "name",
                                   s."regionID"        AS "regionId",
                                   r."regionName"      AS "regionName"
                            FROM evesde."mapSolarSystems" s
                            LEFT JOIN evesde."mapRegions" r ON r."regionID" = s."regionID"
                            WHERE s."solarSystemID" IN (:ids)
                            """, Tuple.class);
                    q.setParameter("ids", missingSystems);
                    @SuppressWarnings("unchecked")
                    List<Tuple> rows = q.getResultList();
                    for (Tuple t : rows) {
                        Long id = num(t, "id");
                        if (id == null) continue;
                        AssetLocation loc = found.getOrDefault(id, new AssetLocation());
                        loc.setLocationId(id);
                        loc.setName(text(t, "name") + " (im All)");
                        loc.setSystemId(id);
                        loc.setSystemName(text(t, "name"));
                        loc.setRegionId(num(t, "regionId"));
                        loc.setRegionName(text(t, "regionName"));
                        loc.setLocationKind("SYSTEM");
                        found.put(id, loc);
                    }
                } catch (Exception e) {
                    log.warn("mapSolarSystems-Lookup fehlgeschlagen: {}", e.getMessage());
                }
            }

            // 3) Rest als "nicht aufloesbar" markieren, damit wir es nicht endlos wiederholen
            for (Long id : batch) {
                AssetLocation loc = found.get(id);
                if (loc == null) {
                    loc = new AssetLocation();
                    loc.setLocationId(id);
                    loc.setName("Unbekannter Ort (" + id + ")");
                    loc.setLocationKind("UNKNOWN");
                    loc.setResolveFailed(true);
                } else {
                    loc.setResolveFailed(false);
                    count++;
                }
                loc.setResolvedAt(Instant.now());
                locationRepo.save(loc);
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // ESI: Upwell-Strukturen
    // ------------------------------------------------------------------

    private int resolveStructuresFromEsi(List<Long> ids) {
        if (ids.isEmpty()) return 0;

        String token = findStructureToken();
        if (token == null) {
            log.warn("Kein Token mit Struktur-Zugriff gefunden - {} Strukturen bleiben unbenannt.", ids.size());
            for (Long id : ids) saveUnknownStructure(id);
            return 0;
        }

        int count = 0;
        for (Long id : ids) {
            try {
                var info = esiService.getStructureInfo(id, token);
                if (info == null || info.name() == null) {
                    saveUnknownStructure(id);
                    continue;
                }
                AssetLocation loc = new AssetLocation();
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
            } catch (Exception e) {
                // 403 = kein Docking-Access. Voellig normal bei fremden Strukturen.
                log.debug("Struktur {} nicht aufloesbar: {}", id, e.getMessage());
                saveUnknownStructure(id);
            }
        }
        return count;
    }

    private void enrichSystem(AssetLocation loc) {
        if (loc.getSystemId() == null) return;
        try {
            Query q = em.createNativeQuery("""
                    SELECT s."solarSystemName" AS "systemName",
                           s."regionID"        AS "regionId",
                           r."regionName"      AS "regionName"
                    FROM evesde."mapSolarSystems" s
                    LEFT JOIN evesde."mapRegions" r ON r."regionID" = s."regionID"
                    WHERE s."solarSystemID" = :id
                    """, Tuple.class);
            q.setParameter("id", loc.getSystemId());
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

    /**
     * Sucht einen Charakter, dessen Token vermutlich Strukturen aufloesen darf.
     * Directors / CEOs haben in aller Regel Docking-Access auf die eigenen Citadels.
     */
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
                // naechsten Kandidaten probieren
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

    // ------------------------------------------------------------------

    private static boolean isStation(Long id) {
        return id != null && id >= STATION_MIN && id < STATION_MAX;
    }

    private static boolean isSystem(Long id) {
        return id != null && id >= SYSTEM_MIN && id < SYSTEM_MAX;
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
