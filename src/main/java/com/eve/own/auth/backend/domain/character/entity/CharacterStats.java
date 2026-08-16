package com.eve.own.auth.backend.domain.character.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

    // ETags liegen jetzt zentral in esi_etags, nicht mehr an der Fachentitaet.

    private Instant lastUpdated;
}