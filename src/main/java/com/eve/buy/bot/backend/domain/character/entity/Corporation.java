package com.eve.buy.bot.backend.domain.character.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Eine EVE-Corporation, soweit fuer Anzeige und Zugriffspruefung noetig. */
@Entity
@Table(name = "corporations")
@Getter
@Setter
public class Corporation {

    @Id
    @Column(name = "corporation_id")
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 10)
    private String ticker;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "alliance_id")
    private Alliance alliance;

    @Column(name = "faction_id")
    private Long factionId;
}

