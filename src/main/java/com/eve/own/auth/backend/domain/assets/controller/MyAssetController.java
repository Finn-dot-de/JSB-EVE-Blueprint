package com.eve.own.auth.backend.domain.assets.controller;

import com.eve.own.auth.backend.domain.assets.dto.AssetDtos;
import com.eve.own.auth.backend.domain.assets.service.MyAssetService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Selbstauskunft ueber die eigenen Assets - fuer jedes eingeloggte Mitglied.
 *
 * <p>Anders als der {@code AssetController} braucht es hier keine Rollenpruefung:
 * die Sicht ist ohnehin auf den eigenen Account begrenzt. Entscheidend ist, dass
 * der Scope <em>nicht</em> aus dem Request stammt, sondern aus dem authentifizierten
 * Charakter im SecurityContext. Alle Endpunkte reichen deshalb nur die
 * characterId aus dem Token an den Service durch, der daraus den Account
 * aufloest und die Filter erzwingt.</p>
 *
 * <p>Bewusst <em>nicht</em> angeboten: "Wer hat das?" und die Corp-Uebersicht -
 * das bleibt der Fuehrungsebene vorbehalten.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/my/assets")
public class MyAssetController {

    /** UTF-8 BOM, damit Excel die Umlaute im CSV korrekt interpretiert. */
    private static final String BOM = "﻿";


    private final MyAssetService myAssetService;

    public MyAssetController(MyAssetService myAssetService) {
        this.myAssetService = myAssetService;
    }

    /**
     * Die characterId steckt als Principal im JWT (siehe JwtAuthenticationFilter).
     * Sie ist die einzige vertrauenswuerdige Quelle fuer den Scope.
     */
    private Long currentCharacterId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long characterId)) {
            throw new IllegalStateException("Kein authentifizierter Charakter im Kontext.");
        }
        return characterId;
    }

    // ------------------------------------------------------------------
    // Suche
    // ------------------------------------------------------------------

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long typeId,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long characterId,
            @RequestParam(required = false) Long locationId,
            @RequestParam(required = false) String regionName,
            @RequestParam(required = false) String locationFlag,
            @RequestParam(required = false) Long minQuantity,
            @RequestParam(required = false) Double minValue,
            @RequestParam(required = false) Boolean shipsOnly,
            @RequestParam(defaultValue = "value") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "50") Integer size,
            @RequestParam(defaultValue = "false") Boolean grouped) {

        // mainId und corporationId werden bewusst nicht entgegengenommen -
        // der Service setzt sie selbst.
        AssetDtos.AssetSearchRequest req = new AssetDtos.AssetSearchRequest(
                q, typeId, groupId, categoryId, characterId, null, null,
                locationId, regionName, locationFlag, minQuantity, minValue, shipsOnly,
                null, sort, direction, page, size, grouped);

        try {
            Long me = currentCharacterId();
            return ResponseEntity.ok(Boolean.TRUE.equals(grouped)
                    ? myAssetService.searchGrouped(me, req)
                    : myAssetService.search(me, req));
        } catch (Exception e) {
            log.error("Eigene Asset-Suche fehlgeschlagen", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Suche fehlgeschlagen: " + e.getMessage()));
        }
    }

    /** Typeahead ueber die Items, die im eigenen Bestand tatsaechlich liegen. */
    @GetMapping("/types/suggest")
    public ResponseEntity<List<AssetDtos.TypeSuggestionDto>> suggestTypes(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "15") int limit) {
        return ResponseEntity.ok(
                myAssetService.suggestTypes(currentCharacterId(), q, Math.min(limit, 50)));
    }

    // ------------------------------------------------------------------
    // Uebersicht & Filter
    // ------------------------------------------------------------------

    @GetMapping("/summary")
    public ResponseEntity<?> summary() {
        try {
            return ResponseEntity.ok(myAssetService.summary(currentCharacterId()));
        } catch (Exception e) {
            log.error("Eigene Asset-Uebersicht fehlgeschlagen", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Auswertung fehlgeschlagen: " + e.getMessage()));
        }
    }

    @GetMapping("/filters")
    public ResponseEntity<AssetDtos.MyFilterOptionsDto> filters(
            @RequestParam(required = false) Long categoryId) {
        return ResponseEntity.ok(myAssetService.filterOptions(currentCharacterId(), categoryId));
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long typeId,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long characterId,
            @RequestParam(required = false) Long locationId,
            @RequestParam(required = false) String regionName,
            @RequestParam(required = false) String locationFlag,
            @RequestParam(required = false) Long minQuantity,
            @RequestParam(required = false) Double minValue,
            @RequestParam(required = false) Boolean shipsOnly,
            @RequestParam(defaultValue = "value") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(defaultValue = "false") Boolean grouped) {

        AssetDtos.AssetSearchRequest req = new AssetDtos.AssetSearchRequest(
                q, typeId, groupId, categoryId, characterId, null, null,
                locationId, regionName, locationFlag, minQuantity, minValue, shipsOnly,
                null, sort, direction, 0, 500, grouped);

        String csv = myAssetService.exportCsv(currentCharacterId(), req);
        // BOM, damit Excel die Umlaute korrekt als UTF-8 liest
        byte[] body = (BOM + csv).getBytes(StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"meine-assets-" + LocalDate.now() + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }
}
