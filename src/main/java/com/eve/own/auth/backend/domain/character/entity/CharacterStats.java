package com.eve.own.auth.backend.domain.character.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "character_stats")
@Getter
@Setter
public class CharacterStats {
    @Id
    private Long characterId; // Identisch mit Character ID

    private Double walletBalance;
    private Long skillPoints;

    // Das ETag von CCP, um zu sehen, ob sich Daten geändert haben
    private String walletEtag;

    private Instant lastUpdated;
}