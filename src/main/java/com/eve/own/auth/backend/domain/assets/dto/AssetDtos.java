package com.eve.own.auth.backend.domain.assets.dto;

import java.util.List;

/**
 * Alle Transport-Objekte fuer das Asset-Audit (Director-Auswertung).
 */
public class AssetDtos {

    // ------------------------------------------------------------------
    // Suche
    // ------------------------------------------------------------------

    /** Ein einzelner Treffer der Detailsuche (ein Asset-Stack). */
    public record AssetRowDto(
            Long itemId,
            Long characterId,
            String characterName,
            Long mainId,
            String mainName,
            Long corporationId,
            String corporationName,
            Long typeId,
            String typeName,
            Long groupId,
            String groupName,
            Long categoryId,
            String categoryName,
            Long quantity,
            String locationName,
            String systemName,
            String regionName,
            String locationFlag,
            /** true = zusammengebaut / gefittet, false = verpackt im Stapel. */
            Boolean singleton,
            /** Ingame vergebener Name, nur bei zusammengebauten Items. Sonst null. */
            String customName,
            Boolean isBlueprintCopy,
            Double unitPrice,
            Double totalValue
    ) {}

    /** Aggregierte Sicht: gleicher Typ beim gleichen Account zusammengefasst. */
    public record AssetStackDto(
            Long typeId,
            String typeName,
            String groupName,
            String categoryName,
            Long mainId,
            String mainName,
            String corporationName,
            Boolean isBlueprintCopy,
            Long quantity,
            Integer locationCount,
            Double unitPrice,
            Double totalValue
    ) {}

    public record PageDto<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            Double pageValue,
            Double grandTotalValue
    ) {}

    /** Filter-Parameter der Suche (kommt als Query-String rein). */
    public record AssetSearchRequest(
            String q,
            Long typeId,
            Long groupId,
            Long categoryId,
            Long characterId,
            Long mainId,
            Long corporationId,
            Long locationId,
            String regionName,
            String locationFlag,
            Long minQuantity,
            Double minValue,
            Boolean shipsOnly,
            String sort,
            String direction,
            Integer page,
            Integer size,
            Boolean grouped
    ) {}

    // ------------------------------------------------------------------
    // "Wer hat das?"
    // ------------------------------------------------------------------

    public record HolderDto(
            Long mainId,
            String mainName,
            String portraitUrl,
            String corporationName,
            Long totalQuantity,
            Double totalValue,
            List<HolderCharacterDto> characters
    ) {}

    public record HolderCharacterDto(
            Long characterId,
            String characterName,
            String portraitUrl,
            Long quantity,
            List<HolderLocationDto> locations
    ) {}

    /**
     * Ein Posten eines Charakters an einem Standort.
     *
     * <p>Verpackte Huellen eines Typs werden zu einer Zeile mit entsprechender
     * Menge zusammengefasst; zusammengebaute Schiffe bleiben nach Name getrennt,
     * damit "Oskar" und "PVP Scimitar" einzeln sichtbar sind.</p>
     */
    public record HolderLocationDto(
            Long locationId,
            String locationName,
            String systemName,
            String regionName,
            String locationFlag,
            /** true = zusammengebaut / gefittet, false = verpackt im Stapel. */
            Boolean singleton,
            /** Ingame vergebener Name, nur bei zusammengebauten Items. Sonst null. */
            String customName,
            Long quantity
    ) {}

    public record TypeHoldersDto(
            Long typeId,
            String typeName,
            String groupName,
            String iconUrl,
            Double unitPrice,
            Long totalQuantity,
            Double totalValue,
            int holderCount,
            List<HolderDto> holders
    ) {}

    // ------------------------------------------------------------------
    // Uebersicht / Statistik
    // ------------------------------------------------------------------

    public record SummaryDto(
            Long totalStacks,
            Long totalItems,
            Long distinctTypes,
            Long trackedCharacters,
            Double totalValue,
            List<NamedValueDto> valueByCorporation,
            List<NamedValueDto> valueByCategory,
            List<TopTypeDto> topTypes,
            List<TopHolderDto> topHolders,
            List<NamedValueDto> topRegions
    ) {}

    public record NamedValueDto(String name, Long quantity, Double value) {}

    public record TopTypeDto(Long typeId, String typeName, String groupName,
                            String iconUrl, Long quantity, Double value, Long holders) {}

    public record TopHolderDto(Long mainId, String mainName, String portraitUrl,
                               String corporationName, Long stacks, Double value) {}

    // ------------------------------------------------------------------
    // Member-Detail
    // ------------------------------------------------------------------

    public record MemberAssetDetailDto(
            Long mainId,
            String mainName,
            String portraitUrl,
            String corporationName,
            Double totalValue,
            Long totalStacks,
            List<NamedValueDto> byCategory,
            List<LocationBucketDto> byLocation,
            List<AssetStackDto> topItems
    ) {}

    public record LocationBucketDto(
            Long locationId,
            String locationName,
            String systemName,
            String regionName,
            Long stacks,
            Double value
    ) {}

    // ------------------------------------------------------------------
    // Doktrin-Verfuegbarkeit
    // ------------------------------------------------------------------

    public record DoctrineShipDto(Long typeId, String typeName, String iconUrl, Long owned) {}

    public record DoctrineReadinessRowDto(
            Long mainId,
            String mainName,
            String portraitUrl,
            String corporationName,
            int shipsOwned,
            int shipsTotal,
            double coverage,
            List<DoctrineShipDto> ships
    ) {}

    public record DoctrineReadinessDto(
            String doctrineName,
            List<DoctrineShipDto> requiredShips,
            int membersReady,
            int membersTotal,
            List<DoctrineReadinessRowDto> rows
    ) {}

    // ------------------------------------------------------------------
    // Filter-Optionen fuer die Dropdowns im Frontend
    // ------------------------------------------------------------------

    public record FilterOptionsDto(
            List<IdNameDto> categories,
            List<IdNameDto> groups,
            List<IdNameDto> locations,
            List<String> regions,
            List<String> locationFlags,
            List<IdNameDto> corporations,
            List<IdNameDto> mains
    ) {}

    public record IdNameDto(Long id, String name) {}

    public record TypeSuggestionDto(Long typeId, String typeName, String groupName,
                                    String iconUrl, Long totalQuantity) {}
}
