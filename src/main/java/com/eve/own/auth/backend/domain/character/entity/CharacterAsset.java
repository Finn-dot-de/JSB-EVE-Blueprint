package com.eve.own.auth.backend.domain.character.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "character_assets", indexes = {
        @Index(name = "idx_asset_char_id", columnList = "character_id"),
        @Index(name = "idx_asset_type_id", columnList = "type_id"),
        @Index(name = "idx_asset_root_location", columnList = "root_location_id"),
        @Index(name = "idx_asset_type_char", columnList = "type_id, character_id")
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

    /**
     * Direkter Parent laut ESI. Kann eine Station, eine Struktur, ein Sonnensystem
     * ODER die item_id eines Containers / Schiffes sein.
     */
    @Column(name = "location_id")
    private Long locationId;

    /**
     * Aufgeloester "echter" Aufbewahrungsort (Station / Struktur / System).
     * Wird beim Sync durch Hochlaufen der Container-Kette ermittelt,
     * damit die Suche spaeter nicht rekursiv joinen muss.
     */
    @Column(name = "root_location_id")
    private Long rootLocationId;

    /**
     * ESI location_flag, z.B. Hangar, ShipHangar, AssetSafety, Deliveries ...
     */
    @Column(name = "location_flag", length = 64)
    private String locationFlag;

    /**
     * ESI location_type: station | solar_system | item | other
     */
    @Column(name = "location_type", length = 32)
    private String locationType;

    /**
     * true = einzelnes, "zusammengebautes" Item (z.B. ein gefittetes Schiff)
     */
    @Column(name = "is_singleton")
    private Boolean singleton;

    /**
     * true = Blueprint Copy (begrenzte Runs), false/null = Original.
     * ESI liefert das Feld nur bei Blueprints, sonst null.
     */
    @Column(name = "is_blueprint_copy")
    private Boolean blueprintCopy;

    /**
     * Ingame vergebener Name eines zusammengebauten Items, z.B. "Oskar" oder
     * "PVP Scimitar". Kommt nicht aus dem Asset-Endpunkt, sondern aus dem
     * separaten POST /characters/{id}/assets/names/.
     *
     * <p>Drei Zustaende, die bewusst unterschieden werden:</p>
     * <ul>
     *   <li>{@code null} - noch nie abgefragt (z.B. Bestand von vor diesem Feature)</li>
     *   <li>{@code ""} - abgefragt, aber der Spieler hat keinen Namen vergeben</li>
     *   <li>Text - der tatsaechliche Name</li>
     * </ul>
     *
     * <p>Ohne die Unterscheidung null/"" liesse sich nicht erkennen, ob die Namen
     * eines Charakters schon einmal geholt wurden - siehe
     * {@code CharacterAssetRepository.hasPendingCustomNames}.</p>
     */
    @Column(name = "custom_name", length = 255)
    private String customName;

    private Integer quantity;
}
