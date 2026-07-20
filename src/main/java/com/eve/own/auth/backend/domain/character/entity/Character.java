package com.eve.own.auth.backend.domain.character.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

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