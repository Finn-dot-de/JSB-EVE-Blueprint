package com.eve.own.auth.backend.domain.assets.service;

import com.eve.own.auth.backend.domain.assets.dto.AssetDtos;
import com.eve.own.auth.backend.domain.assets.repository.AssetQueryRepository;
import com.eve.own.auth.backend.domain.fleet.entity.FleetDoctrine;
import com.eve.own.auth.backend.domain.fleet.repository.FleetDoctrineRepository;
import jakarta.persistence.Tuple;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static com.eve.own.auth.backend.domain.assets.repository.AssetQueryRepository.*;

/**
 * Fachlogik der Asset-Auswertung fuer Directors.
 */
@Slf4j
@Service
public class AssetAnalyticsService {

    private final AssetQueryRepository queryRepo;
    private final FleetDoctrineRepository doctrineRepo;

    public AssetAnalyticsService(AssetQueryRepository queryRepo, FleetDoctrineRepository doctrineRepo) {
        this.queryRepo = queryRepo;
        this.doctrineRepo = doctrineRepo;
    }

    private static String portrait(Long characterId, int size) {
        return "https://images.evetech.net/characters/" + characterId + "/portrait?size=" + size;
    }

    private static String typeIcon(Long typeId) {
        return "https://images.evetech.net/types/" + typeId + "/icon?size=64";
    }

    // ==================================================================
    // Suche
    // ==================================================================

    @Transactional(readOnly = true)
    public AssetDtos.PageDto<AssetDtos.AssetRowDto> search(AssetDtos.AssetSearchRequest req) {
        return queryRepo.search(req);
    }

    @Transactional(readOnly = true)
    public AssetDtos.PageDto<AssetDtos.AssetStackDto> searchGrouped(AssetDtos.AssetSearchRequest req) {
        return queryRepo.searchGrouped(req);
    }

    // ==================================================================
    // "Wer hat das?"
    // ==================================================================

    @Transactional(readOnly = true)
    public AssetDtos.TypeHoldersDto holdersOfType(Long typeId) {
        Tuple info = queryRepo.findTypeInfo(typeId);
        String typeName = info != null ? str(info, "typeName") : ("Typ " + typeId);
        String groupName = info != null ? str(info, "groupName") : null;
        double unitPrice = info != null ? dbl(info, "unitPrice") : 0d;

        List<Tuple> rows = queryRepo.findHoldersOfType(typeId);

        // main -> character -> locations
        Map<Long, MainAcc> mains = new LinkedHashMap<>();

        for (Tuple r : rows) {
            Long mainId = lng(r, "mainId");
            Long charId = lng(r, "characterId");
            long qty = lng(r, "quantity");
            double value = dbl(r, "value");

            MainAcc main = mains.computeIfAbsent(mainId, k -> new MainAcc(
                    mainId, str(r, "mainName"), str(r, "corporationName")));
            main.quantity += qty;
            main.value += value;

            CharAcc ch = main.characters.computeIfAbsent(charId, k -> new CharAcc(charId, str(r, "characterName")));
            ch.quantity += qty;
            ch.locations.add(new AssetDtos.HolderLocationDto(
                    lng(r, "locationId"),
                    fallbackLocation(str(r, "locationName"), lng(r, "locationId")),
                    str(r, "systemName"),
                    str(r, "regionName"),
                    str(r, "locationFlag"),
                    qty
            ));
        }

        List<AssetDtos.HolderDto> holders = mains.values().stream()
                .sorted(Comparator.comparingLong((MainAcc m) -> m.quantity).reversed())
                .map(m -> new AssetDtos.HolderDto(
                        m.mainId, m.mainName, portrait(m.mainId, 64), m.corporationName,
                        m.quantity, m.value,
                        m.characters.values().stream()
                                .sorted(Comparator.comparingLong((CharAcc c) -> c.quantity).reversed())
                                .map(c -> new AssetDtos.HolderCharacterDto(
                                        c.characterId, c.characterName, portrait(c.characterId, 64),
                                        c.quantity, c.locations))
                                .toList()
                ))
                .toList();

        long totalQty = holders.stream().mapToLong(AssetDtos.HolderDto::totalQuantity).sum();
        double totalValue = holders.stream().mapToDouble(AssetDtos.HolderDto::totalValue).sum();

        return new AssetDtos.TypeHoldersDto(typeId, typeName, groupName, typeIcon(typeId),
                unitPrice, totalQty, totalValue, holders.size(), holders);
    }

    private static class MainAcc {
        final Long mainId;
        final String mainName;
        final String corporationName;
        long quantity;
        double value;
        final Map<Long, CharAcc> characters = new LinkedHashMap<>();

        MainAcc(Long mainId, String mainName, String corporationName) {
            this.mainId = mainId;
            this.mainName = mainName;
            this.corporationName = corporationName;
        }
    }

    private static class CharAcc {
        final Long characterId;
        final String characterName;
        long quantity;
        final List<AssetDtos.HolderLocationDto> locations = new ArrayList<>();

        CharAcc(Long characterId, String characterName) {
            this.characterId = characterId;
            this.characterName = characterName;
        }
    }

    // ==================================================================
    // Uebersicht
    // ==================================================================

    @Transactional(readOnly = true)
    public AssetDtos.SummaryDto summary() {
        Tuple totals = queryRepo.totals();

        return new AssetDtos.SummaryDto(
                totals != null ? lng(totals, "stacks") : 0L,
                totals != null ? lng(totals, "items") : 0L,
                totals != null ? lng(totals, "types") : 0L,
                totals != null ? lng(totals, "chars") : 0L,
                totals != null ? dbl(totals, "value") : 0d,
                mapNamedValues(queryRepo.valueByCorporation()),
                mapNamedValues(queryRepo.valueByCategory()),
                queryRepo.topTypes(15).stream()
                        .map(t -> new AssetDtos.TopTypeDto(
                                lng(t, "typeId"), str(t, "typeName"), str(t, "groupName"),
                                typeIcon(lng(t, "typeId")), lng(t, "quantity"),
                                dbl(t, "value"), lng(t, "holders")))
                        .toList(),
                queryRepo.topHolders(15).stream()
                        .map(t -> new AssetDtos.TopHolderDto(
                                lng(t, "mainId"), str(t, "mainName"), portrait(lng(t, "mainId"), 64),
                                str(t, "corporationName"), lng(t, "stacks"), dbl(t, "value")))
                        .toList(),
                mapNamedValues(queryRepo.valueByRegion())
        );
    }

    private List<AssetDtos.NamedValueDto> mapNamedValues(List<Tuple> rows) {
        return rows.stream()
                .map(t -> new AssetDtos.NamedValueDto(str(t, "name"), lng(t, "quantity"), dbl(t, "value")))
                .toList();
    }

    // ==================================================================
    // Member-Detail
    // ==================================================================

    @Transactional(readOnly = true)
    public AssetDtos.MemberAssetDetailDto memberDetail(Long mainId) {
        List<AssetDtos.NamedValueDto> byCategory = mapNamedValues(queryRepo.memberByCategory(mainId));

        List<AssetDtos.LocationBucketDto> byLocation = queryRepo.memberByLocation(mainId).stream()
                .map(t -> new AssetDtos.LocationBucketDto(
                        lng(t, "locationId"),
                        fallbackLocation(str(t, "locationName"), lng(t, "locationId")),
                        str(t, "systemName"),
                        str(t, "regionName"),
                        lng(t, "stacks"),
                        dbl(t, "value")))
                .toList();

        AssetDtos.AssetSearchRequest topReq = new AssetDtos.AssetSearchRequest(
                null, null, null, null, null, mainId, null, null, null, null,
                null, null, null, "value", "desc", 0, 25, true);
        var topItems = queryRepo.searchGrouped(topReq);

        double totalValue = byCategory.stream().mapToDouble(AssetDtos.NamedValueDto::value).sum();
        long totalStacks = byLocation.stream().mapToLong(AssetDtos.LocationBucketDto::stacks).sum();

        String mainName = topItems.content().isEmpty() ? "Unbekannt" : topItems.content().get(0).mainName();
        String corpName = topItems.content().isEmpty() ? null : topItems.content().get(0).corporationName();

        return new AssetDtos.MemberAssetDetailDto(
                mainId, mainName, portrait(mainId, 128), corpName,
                totalValue, totalStacks, byCategory, byLocation, topItems.content());
    }

    // ==================================================================
    // Doktrin-Verfuegbarkeit
    // ==================================================================

    @Transactional(readOnly = true)
    public List<String> doctrineNames() {
        return doctrineRepo.findAll().stream()
                .map(FleetDoctrine::getDoctrineName)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Zeigt pro Account, welche Schiffe der Doktrin bereits im Hangar liegen.
     * Basis sind die in fleet_doctrines hinterlegten shipTypeIds.
     */
    @Transactional(readOnly = true)
    public AssetDtos.DoctrineReadinessDto doctrineReadiness(String doctrineName) {
        List<FleetDoctrine> fits = doctrineRepo.findAll().stream()
                .filter(d -> doctrineName == null || doctrineName.equalsIgnoreCase(d.getDoctrineName()))
                .filter(d -> d.getShipTypeId() != null)
                .toList();

        // typeId -> Anzeigename (aus dem Doktrin-Eintrag)
        Map<Long, String> shipNames = new LinkedHashMap<>();
        for (FleetDoctrine d : fits) {
            shipNames.putIfAbsent(d.getShipTypeId(),
                    d.getShipType() != null ? d.getShipType() : ("Typ " + d.getShipTypeId()));
        }

        List<AssetDtos.DoctrineShipDto> required = shipNames.entrySet().stream()
                .map(e -> new AssetDtos.DoctrineShipDto(e.getKey(), e.getValue(), typeIcon(e.getKey()), 0L))
                .toList();

        if (shipNames.isEmpty()) {
            return new AssetDtos.DoctrineReadinessDto(doctrineName, required, 0, 0, List.of());
        }

        List<Tuple> rows = queryRepo.doctrineOwnership(new ArrayList<>(shipNames.keySet()));

        Map<Long, DoctrineAcc> byMain = new LinkedHashMap<>();
        for (Tuple r : rows) {
            Long mainId = lng(r, "mainId");
            DoctrineAcc acc = byMain.computeIfAbsent(mainId, k ->
                    new DoctrineAcc(mainId, str(r, "mainName"), str(r, "corporationName")));
            acc.owned.merge(lng(r, "typeId"), lng(r, "quantity"), Long::sum);
        }

        int shipsTotal = shipNames.size();
        List<AssetDtos.DoctrineReadinessRowDto> resultRows = byMain.values().stream()
                .map(acc -> {
                    List<AssetDtos.DoctrineShipDto> ships = shipNames.entrySet().stream()
                            .map(e -> new AssetDtos.DoctrineShipDto(
                                    e.getKey(), e.getValue(), typeIcon(e.getKey()),
                                    acc.owned.getOrDefault(e.getKey(), 0L)))
                            .toList();
                    int ownedCount = (int) ships.stream().filter(s -> s.owned() > 0).count();
                    return new AssetDtos.DoctrineReadinessRowDto(
                            acc.mainId, acc.mainName, portrait(acc.mainId, 64), acc.corporationName,
                            ownedCount, shipsTotal,
                            shipsTotal == 0 ? 0d : (double) ownedCount / shipsTotal,
                            ships);
                })
                .sorted(Comparator.comparingDouble(AssetDtos.DoctrineReadinessRowDto::coverage).reversed())
                .toList();

        int ready = (int) resultRows.stream().filter(r -> r.shipsOwned() == r.shipsTotal()).count();

        return new AssetDtos.DoctrineReadinessDto(doctrineName, required, ready, resultRows.size(), resultRows);
    }

    private static class DoctrineAcc {
        final Long mainId;
        final String mainName;
        final String corporationName;
        final Map<Long, Long> owned = new HashMap<>();

        DoctrineAcc(Long mainId, String mainName, String corporationName) {
            this.mainId = mainId;
            this.mainName = mainName;
            this.corporationName = corporationName;
        }
    }

    // ==================================================================
    // Filter-Optionen
    // ==================================================================

    @Transactional(readOnly = true)
    public AssetDtos.FilterOptionsDto filterOptions(Long categoryId) {
        return new AssetDtos.FilterOptionsDto(
                mapIdNames(queryRepo.distinctCategories()),
                mapIdNames(queryRepo.distinctGroups(categoryId)),
                mapIdNames(queryRepo.distinctLocations()),
                queryRepo.distinctRegions(),
                queryRepo.distinctLocationFlags(),
                mapIdNames(queryRepo.distinctCorporations()),
                mapIdNames(queryRepo.distinctMains())
        );
    }

    private List<AssetDtos.IdNameDto> mapIdNames(List<Tuple> rows) {
        return rows.stream()
                .map(t -> new AssetDtos.IdNameDto(lng(t, "id"), str(t, "name")))
                .filter(d -> d.name() != null)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AssetDtos.TypeSuggestionDto> suggestTypes(String term, int limit) {
        return queryRepo.suggestTypes(term, limit).stream()
                .map(t -> new AssetDtos.TypeSuggestionDto(
                        lng(t, "typeId"), str(t, "typeName"), str(t, "groupName"),
                        typeIcon(lng(t, "typeId")), lng(t, "quantity")))
                .toList();
    }

    // ==================================================================
    // CSV-Export
    // ==================================================================

    @Transactional(readOnly = true)
    public String exportCsv(AssetDtos.AssetSearchRequest req) {
        AssetDtos.AssetSearchRequest exportReq = new AssetDtos.AssetSearchRequest(
                req.q(), req.typeId(), req.groupId(), req.categoryId(), req.characterId(),
                req.mainId(), req.corporationId(), req.locationId(), req.regionName(),
                req.locationFlag(), req.minQuantity(), req.minValue(), req.shipsOnly(),
                req.sort(), req.direction(), 0, 500, req.grouped());

        StringBuilder sb = new StringBuilder();

        if (Boolean.TRUE.equals(req.grouped())) {
            sb.append("Typ;Gruppe;Kategorie;Account;Corporation;Menge;Standorte;Stueckpreis;Gesamtwert\n");
            for (var row : queryRepo.searchGrouped(exportReq).content()) {
                sb.append(csv(row.typeName())).append(';')
                        .append(csv(row.groupName())).append(';')
                        .append(csv(row.categoryName())).append(';')
                        .append(csv(row.mainName())).append(';')
                        .append(csv(row.corporationName())).append(';')
                        .append(row.quantity()).append(';')
                        .append(row.locationCount()).append(';')
                        .append(fmt(row.unitPrice())).append(';')
                        .append(fmt(row.totalValue())).append('\n');
            }
        } else {
            sb.append("Charakter;Account;Corporation;Typ;Gruppe;Kategorie;Menge;Standort;System;Region;Hangar;Stueckpreis;Gesamtwert\n");
            for (var row : queryRepo.search(exportReq).content()) {
                sb.append(csv(row.characterName())).append(';')
                        .append(csv(row.mainName())).append(';')
                        .append(csv(row.corporationName())).append(';')
                        .append(csv(row.typeName())).append(';')
                        .append(csv(row.groupName())).append(';')
                        .append(csv(row.categoryName())).append(';')
                        .append(row.quantity()).append(';')
                        .append(csv(row.locationName())).append(';')
                        .append(csv(row.systemName())).append(';')
                        .append(csv(row.regionName())).append(';')
                        .append(csv(row.locationFlag())).append(';')
                        .append(fmt(row.unitPrice())).append(';')
                        .append(fmt(row.totalValue())).append('\n');
            }
        }
        return sb.toString();
    }

    private static String csv(String value) {
        if (value == null) return "";
        String clean = value.replace(";", ",").replace("\n", " ").replace("\r", " ");
        return clean.contains("\"") ? clean.replace("\"", "'") : clean;
    }

    private static String fmt(Double value) {
        if (value == null) return "0";
        return String.format(Locale.GERMANY, "%.2f", value);
    }

    private static String fallbackLocation(String name, Long id) {
        if (name != null && !name.isBlank()) return name;
        return "Unbekannter Ort (" + id + ")";
    }
}
