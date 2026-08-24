package com.eve.own.auth.backend.domain.mining.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Die eingefrorene Steuerrechnung eines Accounts fuer einen abgeschlossenen Monat.
 *
 * <h2>Der Betrag ist {@link BigDecimal}, wie bei {@code MiningTaxCredit}</h2>
 * <p>Das ist die Zahl, die ein Mitglied schuldet und gegen die es ueberweist.
 * Als {@code double} sammelte sie den Drift jeder einzelnen Multiplikation ein -
 * im Bestand steht in einem eingefrorenen Beleg bereits ein
 * {@code "volume": 261.59999999999997} fuer 872 mal 0,3. In einem Dokument, das
 * ausdruecklich unveraenderlich sein soll, ist das keine Schoenheitsfrage.</p>
 *
 * <p><b>Achtung:</b> die Spalte wird von {@code ddl-auto=update} NICHT
 * umgestellt - Hibernate legt fehlende Spalten an und vergleicht bestehende
 * Typen nie. Ohne {@code MiningMoneyColumnMigration} bliebe {@code total_tax}
 * eine {@code double precision}-Spalte, Postgres castete den gebundenen
 * {@link BigDecimal} stillschweigend nach {@code float8}, und der Code saehe nur
 * genau aus.</p>
 *
 * <h2>Warum {@link #frozenAt}</h2>
 * <p>Ein Beleg ohne Datum ist kein Beleg. Als eine Rechnung aus dem Bestand
 * nachweislich unvollstaendig war, liess sich nicht mehr belegen, wann sie
 * geschrieben wurde - nur erschliessen. Der Zeitstempel beantwortet die Frage,
 * die bei einem Streit zuerst gestellt wird: stand die Zeile schon da, als
 * eingefroren wurde?</p>
 */
@Entity
@Table(name = "mining_tax_invoices", uniqueConstraints = {
        // Genau diese Bedingung traegt das ON CONFLICT in
        // MiningTaxInvoiceRepository.insertIfAbsent - siehe dort.
        @UniqueConstraint(columnNames = {"main_character_id", "month"})
})
@Getter
@Setter
public class MiningTaxInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "main_character_id", nullable = false)
    private Long mainCharacterId;

    @Column(nullable = false)
    private String month;

    /** Die geschuldete Steuer des Monats - kein {@code double}, siehe Klassenkommentar. */
    @Column(name = "total_tax", nullable = false, precision = 20, scale = 2)
    private BigDecimal totalTax;

    @Column(columnDefinition = "TEXT")
    private String detailsJson;

    /**
     * Wann eingefroren wurde.
     *
     * <p>Darf bei den Rechnungen aus der Zeit davor {@code null} sein - eine
     * erfundene Zeitangabe waere schlechter als eine fehlende.</p>
     */
    @Column(name = "frozen_at")
    private Instant frozenAt;
}
