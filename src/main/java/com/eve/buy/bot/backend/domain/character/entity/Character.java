package com.eve.buy.bot.backend.domain.character.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Ein mit dem Buybot verknuepfter EVE-Charakter.
 *
 * <p>Die ESI-Tokens liegen verschluesselt in dieser Tabelle. Die Haupt-Charakter-ID
 * verweist auf den Hauptcharakter, wenn es sich um einen Alt handelt.
 */
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

    @Column(name = "main_character_id")
    private Long mainCharacterId;

    @Column(name = "faction_id")
    private Long factionId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "character_roles", joinColumns = @JoinColumn(name = "character_id"))
    private java.util.Set<String> roles = new java.util.HashSet<>();

}