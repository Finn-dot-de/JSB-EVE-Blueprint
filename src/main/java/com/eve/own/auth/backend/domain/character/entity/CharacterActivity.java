package com.eve.own.auth.backend.domain.character.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /**
     * Der Messwert - je nach {@link #activityType} ISK, ein Volumen oder eine
     * Anzahl.
     *
     * <p>Kein {@code Double} mehr, und der Grund ist ein einziger Kennwert:
     * {@code TAX_PAYMENT}. Das ist die Gegenseite der Mining-Steuerschuld -
     * darueber entscheidet sich, ob jemand als bezahlt gilt. Eine Spalte hat
     * genau einen Typ, also wandern die uebrigen Kennzahlen mit; {@code numeric}
     * traegt eine Anzahl von 185 ebenso wie 1.472.369,60 m³. Nebenbei
     * verschwindet damit der Bestandsschaden, den der alte Typ hinterlassen hat:
     * die Summe der PVE-ISK stand mit {@code 1319981075.6900005} in der
     * Datenbank - Nachkommastellen, die keine Zahlung je hatte.</p>
     */
    @Column(precision = 20, scale = 2)
    private BigDecimal value;

    private Instant timestamp;

    /**
     * Von Hand gepflegter Eintrag.
     *
     * <p>Solche Zeilen ueberleben den ESI-Sync, der die automatisch erhobenen
     * Kennzahlen sonst komplett ersetzt.</p>
     */
    @Column(name = "is_manual", columnDefinition = "boolean default false")
    private Boolean isManual = false;

    /**
     * Setzt die Kennzahl typsicher.
     *
     * <p>Bewusst nicht {@code setActivityType} genannt: Lombok erzeugt einen
     * Setter nur, wenn noch keine Methode dieses Namens existiert. Eine
     * Ueberladung haette den erzeugten {@code setActivityType(String)}
     * verdraengt - und damit jede Zuweisung eines roh gelesenen Werts.</p>
     */
    public void setType(ActivityType type) {
        this.activityType = type.dbValue();
    }

    public boolean isOfType(ActivityType type) {
        return type.matches(activityType);
    }

    /**
     * ISK hat ingame genau zwei Nachkommastellen - dieselbe Festlegung wie bei
     * {@code MiningTaxCredit}.
     */
    private static final int ISK_SCALE = 2;

    /**
     * Baut einen automatisch erhobenen Messwert aus einer Gleitkommazahl.
     *
     * <p>Die Quelle ist hier tatsaechlich ein {@code double} - ESI liefert
     * JSON-Zahlen, und JSON kennt nichts Genaueres. Der Weg ueber
     * {@link BigDecimal#valueOf(double)} nimmt die kuerzeste Darstellung, die
     * denselben {@code double} ergibt: aus {@code 1319981075.6900005} wird damit
     * wieder {@code 1319981075.69}. Was hier ankommt, ist also so genau wie die
     * Leitung es hergab; ab hier geht keine Stelle mehr verloren.</p>
     */
    public static CharacterActivity of(Long characterId, ActivityType type, double value, Instant timestamp) {
        return of(characterId, type, BigDecimal.valueOf(value), timestamp);
    }

    /** Baut einen Messwert, der schon exakt vorliegt. */
    public static CharacterActivity of(Long characterId, ActivityType type, BigDecimal value,
                                       Instant timestamp) {
        CharacterActivity activity = new CharacterActivity();
        activity.setCharacterId(characterId);
        activity.setType(type);
        activity.setValue(value == null ? null : value.setScale(ISK_SCALE, RoundingMode.HALF_UP));
        activity.setTimestamp(timestamp);
        return activity;
    }
}
