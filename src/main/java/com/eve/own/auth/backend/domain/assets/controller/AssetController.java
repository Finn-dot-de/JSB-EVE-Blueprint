package com.eve.own.auth.backend.domain.assets.controller;

import com.eve.own.auth.backend.common.AccessRules;
import com.eve.own.auth.backend.common.ApiError;
import com.eve.own.auth.backend.domain.assets.dto.AssetDtos;
import com.eve.own.auth.backend.domain.assets.service.AssetAnalyticsService;
import com.eve.own.auth.backend.domain.assets.service.AssetLocationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/assets")
@PreAuthorize(AccessRules.LEADERSHIP_OR_IT)
public class AssetController {

    private final AssetAnalyticsService analyticsService;
    private final AssetLocationService locationService;

    public AssetController(AssetAnalyticsService analyticsService,
                           AssetLocationService locationService) {
        this.analyticsService = analyticsService;
        this.locationService = locationService;
    }

    // ------------------------------------------------------------------
    // Suche
    // ------------------------------------------------------------------

    /**
     * Freie Detailsuche ueber alle Assets aller registrierten Charaktere.
     * Beispiel: /api/assets/search?q=Nestor&sort=value&direction=desc
     */
    @GetMapping("/search")
    public ResponseEntity<AssetDtos.PageDto<?>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long typeId,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long characterId,
            @RequestParam(required = false) Long mainId,
            @RequestParam(required = false) Long corporationId,
            @RequestParam(required = false) Long locationId,
            @RequestParam(required = false) String regionName,
            @RequestParam(required = false) String locationFlag,
            @RequestParam(required = false) Long minQuantity,
            @RequestParam(required = false) Double minValue,
            @RequestParam(required = false) Boolean shipsOnly,
            @RequestParam(required = false) String ownerType,
            @RequestParam(defaultValue = "value") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "50") Integer size,
            @RequestParam(defaultValue = "false") Boolean grouped) {

        AssetDtos.AssetSearchRequest req = new AssetDtos.AssetSearchRequest(
                q, typeId, groupId, categoryId, characterId, mainId, corporationId,
                locationId, regionName, locationFlag, minQuantity, minValue, shipsOnly,
                ownerType, sort, direction, page, size, grouped);

        return ResponseEntity.ok(Boolean.TRUE.equals(grouped)
                ? analyticsService.searchGrouped(req)
                : analyticsService.search(req));
    }

    /** Typeahead ueber die Items, die tatsaechlich vorhanden sind. */
    @GetMapping("/types/suggest")
    public ResponseEntity<List<AssetDtos.TypeSuggestionDto>> suggestTypes(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "15") int limit) {
        return ResponseEntity.ok(analyticsService.suggestTypes(q, Math.min(limit, 50)));
    }

    // ------------------------------------------------------------------
    // "Wer hat das?"
    // ------------------------------------------------------------------

    @GetMapping("/holders/{typeId}")
    public ResponseEntity<AssetDtos.TypeHoldersDto> holders(@PathVariable Long typeId) {
        return ResponseEntity.ok(analyticsService.holdersOfType(typeId));
    }

    // ------------------------------------------------------------------
    // Uebersicht
    // ------------------------------------------------------------------

    @GetMapping("/summary")
    public ResponseEntity<AssetDtos.SummaryDto> summary() {
        return ResponseEntity.ok(analyticsService.summary());
    }

    @GetMapping("/filters")
    public ResponseEntity<AssetDtos.FilterOptionsDto> filters(
            @RequestParam(required = false) Long categoryId) {
        return ResponseEntity.ok(analyticsService.filterOptions(categoryId));
    }

    // ------------------------------------------------------------------
    // Member-Detail
    // ------------------------------------------------------------------

    @GetMapping("/member/{mainId}")
    public ResponseEntity<AssetDtos.MemberAssetDetailDto> memberDetail(@PathVariable Long mainId) {
        return ResponseEntity.ok(analyticsService.memberDetail(mainId));
    }

    // ------------------------------------------------------------------
    // Doktrinen
    // ------------------------------------------------------------------

    @GetMapping("/doctrines")
    public ResponseEntity<List<String>> doctrines() {
        return ResponseEntity.ok(analyticsService.doctrineNames());
    }

    @GetMapping("/doctrines/readiness")
    public ResponseEntity<AssetDtos.DoctrineReadinessDto> doctrineReadiness(
            @RequestParam(required = false) String doctrineName) {
        return ResponseEntity.ok(analyticsService.doctrineReadiness(doctrineName));
    }

    // ------------------------------------------------------------------
    // Export & Wartung
    // ------------------------------------------------------------------

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long typeId,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long characterId,
            @RequestParam(required = false) Long mainId,
            @RequestParam(required = false) Long corporationId,
            @RequestParam(required = false) Long locationId,
            @RequestParam(required = false) String regionName,
            @RequestParam(required = false) String locationFlag,
            @RequestParam(required = false) Long minQuantity,
            @RequestParam(required = false) Double minValue,
            @RequestParam(required = false) Boolean shipsOnly,
            @RequestParam(required = false) String ownerType,
            @RequestParam(defaultValue = "value") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(defaultValue = "false") Boolean grouped) {

        AssetDtos.AssetSearchRequest req = new AssetDtos.AssetSearchRequest(
                q, typeId, groupId, categoryId, characterId, mainId, corporationId,
                locationId, regionName, locationFlag, minQuantity, minValue, shipsOnly,
                ownerType, sort, direction, 0, 500, grouped);

        String csv = analyticsService.exportCsv(req);
        // BOM, damit Excel die Umlaute korrekt als UTF-8 liest
        byte[] body = ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"assets-" + LocalDate.now() + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }

    /** Manueller Anstoss der Standort-Aufloesung (z.B. nach neuem Docking-Access). */
    @PostMapping("/locations/resolve")
    @PreAuthorize(AccessRules.COMMAND)
    public ResponseEntity<ApiError> resolveLocations() {
        locationService.resolvePendingLocations();
        return ResponseEntity.ok(new ApiError("Standort-Aufloesung gestartet."));
    }
}
