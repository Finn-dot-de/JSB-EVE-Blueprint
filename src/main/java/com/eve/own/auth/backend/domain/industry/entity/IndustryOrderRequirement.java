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

    /**
     * Der Anteil am Bedarf, der unmittelbar vom Endprodukt kommt.
     *
     * <p>Notwendig, weil {@code quantityNeeded} beim Neurechnen die Beitraege
     * der Unterzweige aufsammelt. Ohne einen Punkt, auf den zurueckgesetzt
     * werden kann, addiert jedes Umschalten einer Kaufen/Bauen-Entscheidung
     * dieselben Beitraege erneut auf - und Tritanium, das sowohl unmittelbares
     * Material als auch Bestandteil mehrerer Bauteile ist, waechst bei jedem
     * Klick.</p>
     *
     * <p>Leer bei Auftraegen von vor der Einfuehrung; dort dient der aktuelle
     * Stand als Marke. Ein bereits aufgelaufener Fehler laesst sich damit nicht
     * mehr zurueckrechnen, aber er waechst nicht weiter.</p>
     */
    @Column(name = "base_quantity")
    private Long baseQuantity;

    /** MINERAL, PI, REACTION, BUILDABLE, GAS oder RAW. */
    @Column(name = "source_kind", nullable = false, length = 16)
    private String sourceKind;

    /** BUY oder BUILD - Vorgabe ist BUY. */
    @Column(nullable = false, length = 8)
    private String decision = "BUY";

    /** Ebene im Stuecklistenbaum, 1 ist unmittelbares Material. */
    @Column(nullable = false)
    private Integer depth;

    /**
     * Die Fertigungsstufe: 0 wird beschafft, darueber wird gebaut.
     *
     * <p>Nicht dasselbe wie {@link #depth} und deshalb ein eigenes Feld.
     * {@code depth} ist der <em>kuerzeste</em> Weg zur Wurzel und beantwortet
     * die Frage "ist das unmittelbares Material des Endprodukts". Fuer die
     * Reihenfolge taugt es nicht: Reinforced Carbon Fiber ist unmittelbares
     * Material des Endprodukts <em>und</em> Vorprodukt einer anderen Zeile
     * derselben Tiefe. Wer nach {@code depth} gruppiert, stellt beide
     * nebeneinander.</p>
     *
     * <p>Diese Stufe ist der <em>laengste</em> Weg ueber gebaute Knoten und
     * damit die Zusicherung, auf die sich die Oberflaeche verlassen darf:
     * <b>fuer jede Materialkante steht das Material auf einer kleineren Stufe
     * als das Produkt.</b> Wer Stufe 1 fertig hat, kann Stufe 2 anfangen.</p>
     *
     * <p>Bleibt leer bei Auftraegen, die vor der Einfuehrung angelegt wurden;
     * dort faellt die Anzeige auf {@code depth} zurueck, bis einmal neu
     * gerechnet wurde.</p>
     */
    @Column(name = "build_level")
    private Integer buildLevel;

    /**
     * Einer der Verbraucher dieses Materials - nicht der einzige.
     *
     * <p>Eine Stueckliste ist ein Netz, kein Baum: Reinforced Carbon Fiber
     * geht in einem Phoenix-Auftrag in siebzehn verschiedene Teile ein. Eine
     * Spalte kann davon nur eines festhalten, und welches es wird, entscheidet
     * die Reihenfolge der Suche.</p>
     *
     * <p>Fuer die Reihenfolge darf dieses Feld deshalb nicht benutzt werden -
     * dafuer gibt es {@link #buildLevel}. Es bleibt als Herkunftshinweis
     * erhalten: "wozu gehoert diese Zeile ueberhaupt".</p>
     */
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
