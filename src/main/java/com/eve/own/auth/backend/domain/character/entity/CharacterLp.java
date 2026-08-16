package com.eve.own.auth.backend.domain.character.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "character_lp", indexes = {
        @Index(name = "idx_lp_char_id", columnList = "character_id")
})
@Getter
@Setter
public class CharacterLp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "character_id", nullable = false)
    private Long characterId;

    @Column(name = "corporation_id", nullable = false)
    private Long corporationId; // z.B. 1000035 (Federal Navy Academy)

    @Column(nullable = false)
    private Integer loyaltyPoints;
}