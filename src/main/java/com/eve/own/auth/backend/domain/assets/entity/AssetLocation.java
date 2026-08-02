package com.eve.own.auth.backend.domain.assets.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Namens-Cache fuer Asset-Standorte.
 *
 * ESI liefert bei Assets nur eine location_id. Je nach Zahlenbereich ist das:
 *  - 30.000.000 - 32.000.000  -> Sonnensystem (Item schwebt im All / Container im Space)
 *  - 60.000.000 - 64.000.000  -> NPC-Station  (aufloesbar ueber evesde."mapDenormalize")
 *  - >= 1.000.000.000.000     -> Upwell-Struktur (nur ueber ESI mit Token aufloesbar)
 *
 * Alles wird hier einmalig aufgeloest und danach nur noch gejoined.
 */
@Entity
@Table(name = "asset_locations")
@Getter
@Setter
public class AssetLocation {

    @Id
    @Column(name = "location_id")
    private Long locationId;

    @Column(name = "name", length = 255)
    private String name;

    @Column(name = "system_id")
    private Long systemId;

    @Column(name = "system_name", length = 128)
    private String systemName;

    @Column(name = "region_id")
    private Long regionId;

    @Column(name = "region_name", length = 128)
    private String regionName;

    /** STATION | STRUCTURE | SYSTEM | UNKNOWN */
    @Column(name = "location_kind", length = 16)
    private String locationKind;

    /** Corp-ID des Struktur-Besitzers (nur bei Upwell-Strukturen aus ESI) */
    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /**
     * true, wenn ESI den Namen nicht liefern konnte (kein Docking-Access).
     * Verhindert, dass wir bei jedem Lauf erneut gegen 403er rennen.
     */
    @Column(name = "resolve_failed")
    private Boolean resolveFailed = false;
}
