package com.eve.buy.bot.backend.domain.buybot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.Setter;

/**
 * Die im Admin-Bereich gepflegten Sprueche des Bots.
 *
 * <p>Je Feld eine Zeile pro Spruch; angezeigt wird eine zufaellig gewaehlte davon.
 */
@Embeddable
@Getter
@Setter
public class BotTexts {

    @Column(columnDefinition = "TEXT")
    private String idle;

    @Column(columnDefinition = "TEXT")
    private String thinking;

    @Column(columnDefinition = "TEXT")
    private String success;

    @Column(name = "warn_missing", columnDefinition = "TEXT")
    private String warnMissing;

    @Column(name = "warn_rejected", columnDefinition = "TEXT")
    private String warnRejected;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "high_volume", columnDefinition = "TEXT")
    private String highVolume;

    @Column(name = "high_value", columnDefinition = "TEXT")
    private String highValue;

    @Column(name = "expensive_item", columnDefinition = "TEXT")
    private String expensiveItem;
}