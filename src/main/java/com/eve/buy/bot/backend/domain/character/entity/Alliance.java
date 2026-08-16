package com.eve.buy.bot.backend.domain.character.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Eine EVE-Allianz, soweit fuer die Anzeige noetig. */
@Entity
@Table(name = "alliances")
@Getter
@Setter
public class Alliance {

    @Id
    @Column(name = "alliance_id")
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 10)
    private String ticker;
}