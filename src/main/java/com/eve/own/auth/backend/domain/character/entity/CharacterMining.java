package com.eve.own.auth.backend.domain.character.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "character_mining", indexes = {
        @Index(name = "idx_mining_char_id", columnList = "character_id")
})
@Getter @Setter
public class CharacterMining {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "character_id", nullable = false)
    private Long characterId;
    @Column(name = "mining_date")
    private String date;
    private Long typeId;
    private Long quantity;
}