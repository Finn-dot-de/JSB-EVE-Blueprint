package com.eve.own.auth.backend.domain.character.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Eine Spieler-Ueberweisung zwischen zwei bestimmten Charakteren.
 *
 * <h2>Warum das der staerkste Kandidat unter den Signalen ist</h2>
 * <p>Eine Ueberweisung ist <b>selten</b> und sie ist <b>gerichtet</b>. Genau
 * daran scheitern die bisherigen Signale: gemeinsame Mining-Tage waren gemessen
 * sogar <em>invertiert</em>, weil in einer Corporation alle an denselben Tagen
 * minen - ein Gruppenereignis, das wie ein Fingerabdruck aussieht. Ein
 * Beitritts-Cluster hat dasselbe Problem, sobald eine Rekrutierungswelle
 * durchlaeuft. Eine Ueberweisung von A an B dagegen benennt <em>zwei</em>
 * Charaktere, nicht einen Tag, an dem alle etwas taten. Sie muss deshalb nicht
 * erst gegen die Haeufigkeit in der Gruppe verrechnet werden, um ueberhaupt
 * etwas auszusagen.</p>
 *
 * <h2>Warum nur die registrierte Seite gespeichert wird</h2>
 * <p>Das Journal gibt es nur mit dem Token seines Charakters, und ein Token hat
 * nur, wer sich angemeldet hat. Von einem unregistrierten Y sieht diese Tabelle
 * also nie sein eigenes Journal. Das ist <b>kein Mangel</b>, sondern der Zweck:
 * gesucht wird "Main X ueberweist regelmaessig an unregistrierten Y", und diese
 * Zeile entsteht auf der Seite von X.</p>
 *
 * <h2>Warum die Journal-ID mitgespeichert wird</h2>
 * <p>Sie ist der einzige stabile Schluessel, an dem ein zweiter Lauf erkennt,
 * dass er dieselbe Ueberweisung schon hat. ESI liefert rund dreissig Tage
 * Journal auf einmal; ohne diesen Schluessel wuerde jeder Lauf alle 30 Tage
 * erneut anlegen und die Haeufigkeit - das eigentliche Signal - ins Vielfache
 * treiben. Der eindeutige Schluessel ueber {@code (character_id, journal_ref_id)}
 * macht daraus eine Zusicherung der Datenbank statt einer Absicht im Code.</p>
 */
@Entity
@Table(name = "character_isk_transfer",
        uniqueConstraints = @UniqueConstraint(name = "uk_isk_transfer_journal",
                columnNames = {"character_id", "journal_ref_id"}),
        indexes = {
                @Index(name = "idx_isk_transfer_char", columnList = "character_id"),
                @Index(name = "idx_isk_transfer_counterparty", columnList = "counterparty_id"),
                // Der Loeschlauf sucht ausschliesslich nach dem Zeitpunkt. Ohne
                // diesen Index waere er ein voller Tabellendurchlauf - taeglich,
                // auf der groessten der vier neuen Tabellen.
                @Index(name = "idx_isk_transfer_time", columnList = "occurred_at")
        })
@Getter
@Setter
public class CharacterIskTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Der registrierte Charakter, aus dessen Journal die Zeile stammt. */
    @Column(name = "character_id", nullable = false)
    private Long characterId;

    /** Die Gegenpartei - der Wert, den der bisherige Code wegwarf. */
    @Column(name = "counterparty_id", nullable = false)
    private Long counterpartyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 16)
    private IskTransferDirection direction;

    /**
     * Der Betrag, immer positiv - das Vorzeichen steckt in {@link #direction}.
     *
     * <p>{@code numeric(20,2)} aus demselben Grund wie bei
     * {@code CharacterActivity}: ISK hat ingame genau zwei Nachkommastellen, und
     * ein {@code double} hat hier schon einmal Nachkommastellen erfunden, die
     * keine Zahlung je hatte.</p>
     */
    @Column(name = "amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal amount;

    /** Der Zeitpunkt der Ueberweisung laut ESI, nicht der des Abrufs. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** {@code id} der Journalzeile; siehe Klassendoku - dient allein der Wiedererkennung. */
    @Column(name = "journal_ref_id", nullable = false)
    private Long journalRefId;
}
