package com.eve.own.auth.backend.domain.industry.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Eine Zeile der eingefrorenen Bedarfstabelle.
 *
 * <p>Eingefroren, weil eine jedes Mal neu gerechnete Tabelle den
 * Fortschrittsbalken beim blossen Neuladen springen laesst. Der Bedarf steht
 * fest, sobald der Auftrag angelegt ist; er aendert sich nur, wenn jemand
 * ausdruecklich eine Kaufen/Bauen-Entscheidung umstellt.</p>
 *
 * <p>Je Auftrag und Typ genau eine Zeile - Tritanium kommt aus vielen Zweigen
 * des Baums, steht hier aber einmal mit der Gesamtmenge.</p>
 */
@Entity
@Table(name = "industry_order_requirements")
@Getter
@Setter
public class IndustryOrderRequirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "type_id", nullable = false)
    private Long typeId;

    @Column(name = "type_name", nullable = false, length = 255)
    private String typeName;

    /** Wie viel gebraucht wird, Boni bereits eingerechnet. */
    @Column(name = "quantity_needed", nullable = false)
    private Long quantityNeeded;

    /** MINERAL, PI, REACTION, BUILDABLE, GAS oder RAW. */
    @Column(name = "source_kind", nullable = false, length = 16)
    private String sourceKind;

    /** BUY oder BUILD - Vorgabe ist BUY. */
    @Column(nullable = false, length = 8)
    private String decision = "BUY";

    /** Ebene im Stuecklistenbaum, 1 ist unmittelbares Material. */
    @Column(nullable = false)
    private Integer depth;

    @Column(name = "parent_type_id")
    private Long parentTypeId;

    /** Referenzpreis je Stueck, null wenn unbekannt. */
    @Column(name = "unit_price")
    private Double unitPrice;

    /**
     * Ob der Preis fehlt.
     *
     * <p>Ausdruecklich als eigenes Feld und nicht bloss als Preis {@code null}:
     * eine fehlende Bewertung muss auf dem Bildschirm sichtbar sein. Wer sie
     * stillschweigend als null ISK verrechnet, weist eine zu niedrige Summe aus
     * und niemand merkt es.</p>
     */
    @Column(name = "price_missing", nullable = false)
    private Boolean priceMissing = false;

    /**
     * Verpacktes Volumen je Stueck in Kubikmetern.
     *
     * <p>Als Kommazahl: Tritanium hat 0,01 m3. Auf ganze Kubikmeter gerundet
     * waere das hundertfach zu viel - und der Fehler ginge unbemerkt in jede
     * Frachtkostenrechnung ein.</p>
     */
    @Column(name = "packaged_volume")
    private Double packagedVolume;
}
