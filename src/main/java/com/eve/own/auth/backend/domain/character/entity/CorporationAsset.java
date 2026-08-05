package com.eve.own.auth.backend.domain.character.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "corporation_assets", indexes = {
        @Index(name = "idx_corp_asset_corp_id", columnList = "corporation_id"),
        @Index(name = "idx_corp_asset_type_id", columnList = "type_id"),
        @Index(name = "idx_corp_asset_root_location", columnList = "root_location_id"),
        @Index(name = "idx_corp_asset_type_corp", columnList = "type_id, corporation_id")
})
@Getter
@Setter
public class CorporationAsset {

    @Id
    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "corporation_id", nullable = false)
    private Long corporationId;

    @Column(name = "type_id", nullable = false)
    private Long typeId;

    /** Direkter Parent laut ESI - Station, Struktur, System oder ein Container. */
    @Column(name = "location_id")
    private Long locationId;

    /** Aufgeloester "echter" Aufbewahrungsort, analog zu {@link CharacterAsset}. */
    @Column(name = "root_location_id")
    private Long rootLocationId;

    /**
     * ESI location_flag. Bei Corp-Beständen typischerweise CorpSAG1 bis CorpSAG7
     * (die sieben Hangar-Divisionen), CorpDeliveries oder AssetSafety.
     */
    @Column(name = "location_flag", length = 64)
    private String locationFlag;

    @Column(name = "location_type", length = 32)
    private String locationType;

    @Column(name = "is_singleton")
    private Boolean singleton;

    @Column(name = "is_blueprint_copy")
    private Boolean blueprintCopy;

    /** Ingame vergebener Name; Semantik wie bei {@link CharacterAsset#getCustomName()}. */
    @Column(name = "custom_name", length = 255)
    private String customName;

    private Integer quantity;
}
