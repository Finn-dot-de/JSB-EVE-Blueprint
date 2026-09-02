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
 * Wieviele Nachrichten zwischen zwei Charakteren liefen - und sonst nichts.
 *
 * <h2>Die Zusage</h2>
 * <p><b>Betreff und Text werden nicht gespeichert, nicht protokolliert und
 * nicht durch den Dienst gereicht.</b> Das ist keine Absicht, die man beim
 * Aufraeumen versehentlich fallenlaesst - es ist in der Bauform durchgesetzt:</p>
 * <ul>
 *   <li>Diese Entitaet hat <b>kein Feld</b>, das einen Betreff oder einen Text
 *       aufnehmen koennte. Es gibt hier ueberhaupt keine Zeichenkette.</li>
 *   <li>Sie hat auch <b>keine Mail-ID</b>. Damit gibt es keinen Schluessel, mit
 *       dem sich der Inhalt aus ESI nachladen liesse - auch nicht von jemandem,
 *       der spaeter danach sucht.</li>
 *   <li>Schon die ESI-Antwort wird ohne Betreff und ohne Mail-ID eingelesen
 *       (siehe {@code EsiService.EsiMailHeaderResponse}). Der Betreff faellt
 *       also bereits beim Einlesen weg und existiert im Prozess nie als Wert.</li>
 * </ul>
 * <p>Wer hier ein Feld ergaenzt, bricht eine Zusage an die Mitglieder dieser
 * Corporation. {@code MailPrivacyTest} laesst den Versuch scheitern, damit das
 * nicht unbemerkt passiert.</p>
 *
 * <h2>Was die Zahlen bedeuten</h2>
 * <p>Weil es keine Mail-ID gibt, kann kein Lauf erkennen, welche Nachricht er
 * schon gezaehlt hat. Also wird nicht fortgeschrieben, sondern <b>jeder Lauf
 * zaehlt neu</b> - ueber die jeweils juengsten Kopfzeilen, die ESI in einem Zug
 * herausgibt. Der Wert ist damit "so viele der zuletzt sichtbaren Nachrichten
 * liefen zwischen diesen beiden", nicht "so viele insgesamt seit jeher". Diese
 * Einschraenkung ist der Preis der Zusage und ausdruecklich gewollt.</p>
 *
 * <p>Rundschreiben zaehlen nicht mit. Eine Mail an die halbe Corporation ist
 * dasselbe wie ein gemeinsamer Mining-Tag: ein Merkmal, das alle teilen, ist
 * kein Fingerabdruck. Die Grenze steht in {@code AltSourceProperties}.</p>
 */
@Entity
@Table(name = "character_mail_count",
        uniqueConstraints = @UniqueConstraint(name = "uk_mail_count_pair",
                columnNames = {"character_id", "counterparty_id"}),
        indexes = {
                @Index(name = "idx_mail_count_char", columnList = "character_id"),
                @Index(name = "idx_mail_count_counterparty", columnList = "counterparty_id")
        })
@Getter
@Setter
public class CharacterMailCount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Der registrierte Charakter, aus dessen Postfach gezaehlt wurde. */
    @Column(name = "character_id", nullable = false)
    private Long characterId;

    /** Der andere Charakter des Paares. */
    @Column(name = "counterparty_id", nullable = false)
    private Long counterpartyId;

    /** Nachrichten vom registrierten Charakter an die Gegenpartei. */
    @Column(name = "sent_count", nullable = false)
    private int sentCount;

    /** Nachrichten von der Gegenpartei an den registrierten Charakter. */
    @Column(name = "received_count", nullable = false)
    private int receivedCount;

    /**
     * Zeitpunkt der juengsten gezaehlten Nachricht dieses Paares.
     *
     * <p>Kein Inhalt, sondern Aktualitaet: ein Austausch von vor zwei Jahren
     * wiegt anders als einer von gestern. Ohne diesen Wert saehe die Bewertung
     * beide gleich.</p>
     */
    @Column(name = "last_mail_at")
    private Instant lastMailAt;

    /** Wann zuletzt gezaehlt wurde - die Zahlen oben gelten zu diesem Zeitpunkt. */
    @Column(name = "counted_at", nullable = false)
    private Instant countedAt;

    public void addSent() {
        sentCount++;
    }

    public void addReceived() {
        receivedCount++;
    }

    /** Haelt den juengsten Zeitpunkt fest; ein fehlender Zeitpunkt aendert nichts. */
    public void noteMailAt(Instant timestamp) {
        if (timestamp != null && (lastMailAt == null || timestamp.isAfter(lastMailAt))) {
            lastMailAt = timestamp;
        }
    }
}
