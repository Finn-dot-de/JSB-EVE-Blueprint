package com.eve.buy.bot.backend.domain.buybot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Ergebnis der ESI-Vertragsprüfung. Dient gleichzeitig als Gedächtnis,
 * damit ein Vertrag nicht bei jedem Lauf erneut gemeldet wird.
 */
@Entity
@Table(name = "buyback_contract_checks")
@Getter
@Setter
public class ContractCheck {

    @Id
    @Column(name = "contract_id")
    private Long contractId;

    @Column(name = "issuer_id")
    private Long issuerId;

    @Column(name = "issuer_name")
    private String issuerName;

    @Column(name = "title")
    private String title;

    @Column(name = "contract_type")
    private String contractType;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "checked_at")
    private Instant checkedAt;

    @Column(name = "start_location_id")
    private Long startLocationId;

    @Column(name = "location_name")
    private String locationName;

    /** Vom Verkäufer geforderter Preis (Item-Exchange: "price"). */
    @Column(name = "contract_price")
    private Double contractPrice;

    /** Was der Bot laut Matrix zahlen würde. */
    @Column(name = "expected_price")
    private Double expectedPrice;

    @Column(name = "deviation_percent")
    private Double deviationPercent;

    @Column(name = "total_volume")
    private Double totalVolume;

    /** OK | WARN | REJECT */
    @Column(name = "verdict")
    private String verdict;

    /** Kommaseparierte Findings-Codes, z.B. WRONG_LOCATION,PRICE_DEVIATION */
    @Column(name = "finding_codes", columnDefinition = "TEXT")
    private String findingCodes;

    /** Ausformulierte Findings, eine pro Zeile. */
    @Column(name = "findings", columnDefinition = "TEXT")
    private String findings;

    /** Item-Aufstellung als Text, damit der Bericht ohne Zusatzabfrage lesbar ist. */
    @Column(name = "item_summary", columnDefinition = "TEXT")
    private String itemSummary;

    @Column(name = "notified")
    private Boolean notified = false;

    /** Warum die Meldung nicht rausging - damit der Grund im Admin-Panel steht und nicht nur im Log. */
    @Column(name = "notify_error", columnDefinition = "TEXT")
    private String notifyError;

    @Column(name = "notify_attempts")
    private Integer notifyAttempts = 0;
}
