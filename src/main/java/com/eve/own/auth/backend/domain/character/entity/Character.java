package com.eve.own.auth.backend.domain.character.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "characters")
@Getter
@Setter
public class Character {

    @Id
    @Column(name = "character_id")
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "corporation_id", nullable = false)
    private Corporation corporation;

    @Column(name = "access_token", length = 4096, columnDefinition = "TEXT")
    private String accessToken;

    @Column(name = "refresh_token")
    private String refreshToken;

    @Column(name = "token_expiry")
    private Instant tokenExpiry;

    /**
     * Seit wann sich der Token dieses Charakters nicht mehr erneuern laesst.
     *
     * <p>{@code null} heisst gesund. Steht hier ein Zeitpunkt, hat EVE die
     * Erneuerung abgelehnt - meist mit {@code invalid_grant}, weil der
     * Refresh-Token abgelaufen oder zurueckgezogen wurde. Der Charakter muss
     * sich dann neu anmelden.</p>
     *
     * <p>Ohne dieses Feld existierte die Information nur als Logzeile und war
     * danach weg: niemand konnte sagen, <em>welche</em> Charaktere betroffen
     * sind, und deshalb liess sich weder etwas anzeigen noch jemand
     * benachrichtigen. Es ist die Grundlage fuer beides.</p>
     */
    @Column(name = "token_invalid_since")
    private Instant tokenInvalidSince;

    /** Warum die Erneuerung scheiterte - fuer die Anzeige, gekuerzt. */
    @Column(name = "token_invalid_reason", length = 255)
    private String tokenInvalidReason;

    /**
     * Wann wegen dieses Vorfalls benachrichtigt wurde.
     *
     * <p>Getrennt vom Zeitpunkt des Fehlschlags, damit eine Benachrichtigung
     * genau einmal je Vorfall hinausgeht. Ohne dieses Feld bekaeme der Spieler
     * alle zehn Minuten dieselbe Nachricht - und haette den Bot nach einer
     * Stunde stummgeschaltet.</p>
     */
    @Column(name = "token_invalid_notified_at")
    private Instant tokenInvalidNotifiedAt;

    @Column(name = "main_character_id")
    private Long mainCharacterId;

    @Column(name = "faction_id")
    private Long factionId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "character_roles", joinColumns = @JoinColumn(name = "character_id"))
    private Set<String> roles = new HashSet<>();

    /**
     * Die ID, unter der dieser Charakter zusammen mit seinen Geschwistern gefuehrt
     * wird: der Main-Charakter, bei einem Main also er selbst.
     *
     * <p>Der Datenbestand kennt beide Schreibweisen - ein Main traegt seine eigene
     * ID in {@code main_character_id} oder gar keine. Statt diese Fallunterscheidung
     * an einem Dutzend Aufrufstellen zu wiederholen, beantwortet sie der Charakter
     * selbst.</p>
     */
    public Long getAccountId() {
        return mainCharacterId != null ? mainCharacterId : id;
    }

    /** Ob dieser Charakter der Main seines Accounts ist. */
    public boolean isMain() {
        return getAccountId().equals(id);
    }

    public boolean hasRole(String roleName) {
        return roles != null && roles.contains(roleName);
    }
}
