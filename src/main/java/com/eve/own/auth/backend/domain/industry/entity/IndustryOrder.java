package com.eve.own.auth.backend.domain.industry.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Ein Bauauftrag: "fuenfzig Raven".
 *
 * <p>Ein Auftrag gehoert dem <b>Konto</b>, nicht der Corporation. Das ist die
 * tragende Entscheidung dieses Pakets: sie haelt die Zusage aus den Assets
 * unangetastet - niemand sieht fremde Hangars - und trifft zugleich genau das,
 * was gewuenscht war, naemlich dass ein Charakter samt seiner Alts verfolgt
 * wird.</p>
 *
 * <p>ME und TE werden bei der Anlage festgehalten und nicht spaeter neu
 * gelesen. Sonst aendert sich der ausgewiesene Materialbedarf, sobald jemand
 * die Blaupause weiterforscht, und der Auftrag widerspricht dem, was beim
 * Anlegen auf dem Bildschirm stand.</p>
 */
@Entity
@Table(name = "industry_orders")
@Getter
@Setter
public class IndustryOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Das Konto - der Hauptcharakter, unter dem die Alts haengen. */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** Wer den Auftrag angelegt hat - fuer die Anzeige, nicht fuer den Zugriff. */
    @Column(name = "created_by_character_id", nullable = false)
    private Long createdByCharacterId;

    @Column(name = "product_type_id", nullable = false)
    private Long productTypeId;

    @Column(name = "product_name", length = 255)
    private String productName;

    @Column(name = "blueprint_type_id", nullable = false)
    private Long blueprintTypeId;

    /** Wie viele Stueck gewuenscht sind. */
    @Column(name = "target_quantity", nullable = false)
    private Long targetQuantity;

    /** DRAFT, ACTIVE, DONE oder CANCELLED. */
    @Column(nullable = false, length = 16)
    private String status;

    /**
     * Die gewaehlte Voreinstellung: BUY_ALL, COST_EFFICIENT oder BUILD_ALL.
     *
     * <p>Gemerkt, damit sie nach dem Neuladen noch dasteht - und damit man
     * sieht, worauf die Entscheidungen zurueckgehen, falls jemand einzelne
     * Zeilen danach von Hand umgestellt hat.</p>
     */
    @Column(length = 20)
    private String strategy;

    /** Der gewaehlte Bauort, solange noch keiner feststeht null. */
    @Column(name = "build_location_id")
    private Long buildLocationId;

    @Column(name = "build_location_name", length = 255)
    private String buildLocationName;

    @Column(name = "build_system_id")
    private Long buildSystemId;

    /** Zum Zeitpunkt der Anlage gefundene Blaupausenforschung. */
    @Column(name = "material_efficiency", nullable = false)
    private Integer materialEfficiency = 0;

    @Column(name = "time_efficiency", nullable = false)
    private Integer timeEfficiency = 0;

    /**
     * Ob beim Anlegen eine passende Blaupause im Kontoverbund lag.
     *
     * <p>Nicht aus ME und TE erschliessbar: eine unerforschte Blaupause hat
     * ebenfalls ME 0. "Nicht erforscht" heisst nur teurer, "gar nicht vorhanden"
     * heisst, dass sich der Job nicht einmal starten laesst.</p>
     */
    @Column(name = "blueprint_owned")
    private Boolean blueprintOwned = false;

    /** Wie der Auftrag in Jobs zerfaellt - beim Anlegen gerechnet. */
    @Column(name = "runs_per_job")
    private Long runsPerJob;

    @Column(name = "job_count")
    private Long jobCount;

    /** Gerechnete Gesamtdauer aller Jobs in Sekunden, als Prognose. */
    @Column(name = "estimated_seconds")
    private Long estimatedSeconds;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
