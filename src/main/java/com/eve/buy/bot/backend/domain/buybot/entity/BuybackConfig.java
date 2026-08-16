package com.eve.buy.bot.backend.domain.buybot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

/**
 * Die zentrale Konfiguration des Buybots; es existiert genau ein Datensatz mit der ID 1.
 *
 * <p>Spaeter ergaenzte Spalten sind bewusst nullable, damit sie sich an eine bereits
 * gefuellte Tabelle anhaengen lassen. Fuer diese Felder gibt es null-sichere Zugriffe am
 * Ende der Klasse.
 */
@Entity
@Table(name = "buyback_config")
@Getter
@Setter
public class BuybackConfig {
    @Id
    private Long id = 1L;

    @Column(name = "price_basis", nullable = false)
    private String priceBasis = "buy";

    @Column(name = "global_modifier", nullable = false)
    private Double globalModifier = 90.0;

    @Column(name = "volume_threshold")
    private Double volumeThreshold = 350000.0;

    @Column(name = "value_threshold")
    private Double valueThreshold = 1000000000.0;

    @Column(name = "item_value_threshold")
    private Double itemValueThreshold = 500000000.0;

    /**
     * Reprocessing-Ausbeute in Prozent. Die SDE listet die perfekte Ausbeute (100 %);
     * real hängt sie von Anlage, Skills und Implantaten ab - NPC-Station ist 50 %.
     */
    @Column(name = "reprocessing_rate")
    private Double reprocessingRate = 50.0;

    // ==========================================
    // WARTUNGSMODUS (Bot pausieren / aktivieren)
    // Alle neuen Spalten sind absichtlich nullable, damit ddl-auto=update
    // sie auch an eine bereits gefüllte Tabelle anhängen kann.
    // ==========================================
    @Column(name = "bot_enabled")
    private Boolean botEnabled = true;

    @Column(name = "maintenance_title")
    private String maintenanceTitle;

    @Column(name = "maintenance_message", columnDefinition = "TEXT")
    private String maintenanceMessage;

    // ==========================================
    // VERTRAGSERSTELLUNG (Anleitung im Frontend)
    // ==========================================
    /** Charakter oder Corp, auf den die Verträge laufen sollen. */
    @Column(name = "contract_recipient")
    private String contractRecipient;

    /** Empfohlene Laufzeit des Vertrags in Tagen (Protokoll: max. 72h). */
    @Column(name = "contract_expire_days")
    private Integer contractExpireDays = 3;

    /** "Days to complete" - bei Item-Exchange immer 0. */
    @Column(name = "contract_days_to_complete")
    private Integer contractDaysToComplete = 0;

    @Column(name = "contract_note", columnDefinition = "TEXT")
    private String contractNote;

    // ==========================================
    // VERTRAGSPRÜFUNG (ESI-Abgleich)
    // ==========================================
    @Column(name = "contract_check_enabled")
    private Boolean contractCheckEnabled = false;

    /** Charakter, auf dessen offene Verträge geprüft wird (muss eingeloggt/verknüpft sein). */
    @Column(name = "contract_check_character_id")
    private Long contractCheckCharacterId;

    /** Erlaubte Preisabweichung in Prozent (Protokoll: 1 %). */
    @Column(name = "price_tolerance_percent")
    private Double priceTolerancePercent = 1.0;

    /** Prüfintervall in Minuten. */
    @Column(name = "check_interval_minutes")
    private Integer checkIntervalMinutes = 15;

    /** NONE | DISCORD | EVEMAIL | BOTH */
    @Column(name = "notify_target")
    private String notifyTarget = "NONE";

    @Column(name = "discord_webhook_url", columnDefinition = "TEXT")
    private String discordWebhookUrl;

    /** Empfänger der EVE-Mail (Character-ID); leer = der Prüf-Charakter selbst. */
    @Column(name = "notify_mail_recipient_id")
    private Long notifyMailRecipientId;

    /** Auch fehlerfreie Verträge melden, nicht nur Fehlerfälle. */
    @Column(name = "notify_on_ok")
    private Boolean notifyOnOk = true;

    @Embedded
    private BotTexts botTexts;

    // ==========================================
    // Null-sichere Helfer (Altbestand hat in den neuen Spalten NULL)
    // ==========================================
    /**
     * Ob der Ankauf laeuft.
     *
     * @return {@code false} nur, wenn der Wartungsmodus ausdruecklich gesetzt ist
     */
    @Transient
    public boolean isBotActive() {
        return !Boolean.FALSE.equals(botEnabled);
    }

    /**
     * Die erlaubte Preisabweichung.
     *
     * @return der eingestellte Wert oder 1 Prozent
     */
    @Transient
    public double tolerancePercentOrDefault() {
        return (priceTolerancePercent == null || priceTolerancePercent < 0) ? 1.0 : priceTolerancePercent;
    }

    /**
     * Das Pruefintervall.
     *
     * @return der eingestellte Wert oder 15 Minuten
     */
    @Transient
    public int checkIntervalOrDefault() {
        return (checkIntervalMinutes == null || checkIntervalMinutes < 1) ? 15 : checkIntervalMinutes;
    }

    /**
     * Die Reprocessing-Ausbeute.
     *
     * @return der eingestellte Wert oder 50 Prozent, hoechstens 100
     */
    @Transient
    public double reprocessingRateOrDefault() {
        if (reprocessingRate == null || reprocessingRate <= 0) return 50.0;
        return Math.min(reprocessingRate, 100.0);
    }

    /**
     * Der Meldeweg in Grossbuchstaben.
     *
     * @return der eingestellte Weg oder {@code NONE}
     */
    @Transient
    public String notifyTargetOrNone() {
        return (notifyTarget == null || notifyTarget.isBlank()) ? "NONE" : notifyTarget.trim().toUpperCase();
    }
}
