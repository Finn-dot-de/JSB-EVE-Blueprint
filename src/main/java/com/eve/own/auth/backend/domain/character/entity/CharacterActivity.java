package com.eve.own.auth.backend.domain.character.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "character_activity", indexes = {
        @Index(name = "idx_activity_char_id", columnList = "character_id")
})
@Getter
@Setter
public class CharacterActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "character_id", nullable = false)
    private Long characterId;

    /** Einer der Werte aus {@link ActivityType}, siehe dortige Anmerkung zur Spalte. */
    @Column(name = "activity_type", nullable = false)
    private String activityType;

    private Double value;
    private Instant timestamp;

    /**
     * Von Hand gepflegter Eintrag.
     *
     * <p>Solche Zeilen ueberleben den ESI-Sync, der die automatisch erhobenen
     * Kennzahlen sonst komplett ersetzt.</p>
     */
    @Column(name = "is_manual", columnDefinition = "boolean default false")
    private Boolean isManual = false;

    public void setActivityType(ActivityType type) {
        this.activityType = type.dbValue();
    }

    public boolean isOfType(ActivityType type) {
        return type.matches(activityType);
    }

    /** Baut einen automatisch erhobenen Messwert. */
    public static CharacterActivity of(Long characterId, ActivityType type, double value, Instant timestamp) {
        CharacterActivity activity = new CharacterActivity();
        activity.setCharacterId(characterId);
        activity.setActivityType(type);
        activity.setValue(value);
        activity.setTimestamp(timestamp);
        return activity;
    }
}
