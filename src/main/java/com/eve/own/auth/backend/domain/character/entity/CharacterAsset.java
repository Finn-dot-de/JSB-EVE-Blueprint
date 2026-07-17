package com.eve.own.auth.backend.domain.character.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "character_assets", indexes = {
        @Index(name = "idx_asset_char_id", columnList = "character_id")
})
@Getter
@Setter
public class CharacterAsset {

    @Id
    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "character_id", nullable = false)
    private Long characterId;

    @Column(name = "type_id", nullable = false)
    private Long typeId;

    @Column(name = "location_id")
    private Long locationId;

    private Integer quantity;
}