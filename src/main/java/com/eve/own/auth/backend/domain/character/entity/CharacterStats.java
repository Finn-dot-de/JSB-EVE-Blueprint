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
    private Long characterId;

    private Double walletBalance;

    @Column(name = "skill_points")
    private Long skillPoints;

    @Column(name = "rat_kills")
    private Long ratKills;

    private String walletEtag;
    private String skillsEtag;

    private Instant lastUpdated;
}