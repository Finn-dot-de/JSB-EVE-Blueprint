package com.eve.own.auth.backend.domain.character.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Wen ein registrierter Charakter in seiner Kontaktliste fuehrt, samt Standing.
 *
 * <p>Der eigene Alt steht dort auffallend oft mit hoher Standing - eine Angabe,
 * die der Spieler selbst gesetzt hat und die nichts mit dem Verhalten der Gruppe
 * zu tun hat. Damit ist sie, wie die ISK-Ueberweisung, ein <em>gerichtetes</em>
 * Merkmal zwischen zwei bestimmten Charakteren und nicht das Nebenprodukt eines
 * Gruppenereignisses.</p>
 *
 * <h2>Warum nur Charakter-Kontakte</h2>
 * <p>ESI liefert unter derselben Liste auch Corporations, Allianzen und
 * Fraktionen. Die stehen bei jedem zweiten Mitglied drin und sagen deshalb
 * genau nichts ueber eine Verbindung zwischen zwei Spielern aus - dieselbe
 * Falle wie beim gemeinsamen Mining-Tag. Sie werden gar nicht erst gespeichert.
 * Weil damit nur noch <em>ein</em> Typ uebrig ist, gibt es auch keine
 * Typ-Spalte: eine Spalte, in der immer derselbe Wert steht, verspricht eine
 * Unterscheidung, die es nicht gibt.</p>
 *
 * <p>Der Bestand ist eine <b>Momentaufnahme</b>: jeder Lauf ersetzt die Liste
 * eines Charakters vollstaendig, denn ein entfernter Kontakt ist eine Aussage
 * und darf nicht als "besteht weiter" liegenbleiben. Ersetzt wird nur, wenn ESI
 * wirklich geantwortet hat - siehe {@code ContactSyncService}.</p>
 */
@Entity
@Table(name = "character_contact",
        uniqueConstraints = @UniqueConstraint(name = "uk_contact_char_contact",
                columnNames = {"character_id", "contact_id"}),
        indexes = {
                @Index(name = "idx_contact_char", columnList = "character_id"),
                @Index(name = "idx_contact_contact", columnList = "contact_id")
        })
@Getter
@Setter
public class CharacterContact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Der registrierte Charakter, dem die Kontaktliste gehoert. */
    @Column(name = "character_id", nullable = false)
    private Long characterId;

    /** Der eingetragene Charakter. Er muss nicht registriert sein - das ist der Punkt. */
    @Column(name = "contact_id", nullable = false)
    private Long contactId;

    /**
     * Die vom Spieler gesetzte Standing, -10 bis +10.
     *
     * <p>Ausdruecklich {@code nullable}: ESI darf das Feld weglassen, und ein
     * fehlender Wert ist nicht dasselbe wie Standing 0 (neutral). Wer daraus 0
     * macht, verwandelt "unbekannt" in "bewusst neutral gesetzt" - genau die
     * Verwechslung, gegen die die tragende Regel der Alt-Erkennung steht.</p>
     */
    @Column(name = "standing")
    private Double standing;

    /** Ob der Spieler den Kontakt beobachtet. Ein weiterer bewusster Handgriff. */
    @Column(name = "watched")
    private Boolean watched;

    /** Wann diese Momentaufnahme entstand. */
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;
}
