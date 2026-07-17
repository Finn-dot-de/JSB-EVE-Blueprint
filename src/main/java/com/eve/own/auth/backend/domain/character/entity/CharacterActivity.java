package com.eve.own.auth.backend.domain.character.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "character_activity", indexes = {
        @Index(name = "idx_activity_char_id", columnList = "character_id")
})
@Getter
@Setter
public class CharacterActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "character_id", nullable = false)
    private Long characterId;

    // "PVE_ISK", "MINING_VOLUME", "RAT_KILLS"
    @Column(name = "activity_type", nullable = false)
    private String activityType;

    private Double value;

    private Instant timestamp;
}