package com.eve.own.auth.backend.domain.buybot.controller;

import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.buybot.dto.TypeDetailsProjection;
import com.eve.own.auth.backend.domain.buybot.entity.BuybackCategoryRule;
import com.eve.own.auth.backend.domain.buybot.entity.BuybackConfig;
import com.eve.own.auth.backend.domain.buybot.entity.BuybackLocation;
import com.eve.own.auth.backend.domain.buybot.entity.BuybackTypeRule;
import com.eve.own.auth.backend.domain.buybot.repository.BuybackCategoryRuleRepository;
import com.eve.own.auth.backend.domain.buybot.repository.BuybackConfigRepository;
import com.eve.own.auth.backend.domain.buybot.repository.BuybackLocationRepository;
import com.eve.own.auth.backend.domain.buybot.repository.BuybackTypeRuleRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.eve.entity.InvCategory;
import com.eve.own.auth.backend.domain.eve.entity.InvType;
import com.eve.own.auth.backend.domain.eve.repository.InvCategoryRepository;
import com.eve.own.auth.backend.domain.eve.repository.InvTypeRepository;
import com.eve.own.auth.backend.esi.EsiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/admin/buybot")
@PreAuthorize("hasAnyRole('ROLE_DIRECTOR', 'ROLE_CEO', 'ROLE_IT_ADMIN')")
@RequiredArgsConstructor
public class BuybotAdminController {

    private final BuybackConfigRepository configRepo;
    private final BuybackLocationRepository locationRepo;
    private final BuybackCategoryRuleRepository categoryRuleRepo;
    private final BuybackTypeRuleRepository typeRuleRepo;
    private final InvTypeRepository invTypeRepo;
    private final InvCategoryRepository invCategoryRepo;
    private final AuthService  authService;
    private final CharacterRepository characterRepo;
    private final EsiService esiService;

    // ==========================================
    // 1. CONFIGURATION (Preise & Schwellenwerte)
    // ==========================================

    @GetMapping("/config")
    public ResponseEntity<BuybackConfig> getConfig() {
        BuybackConfig config = configRepo.findById(1L).orElseGet(() -> {
            BuybackConfig newConfig = new BuybackConfig();
            newConfig.setId(1L);
            return configRepo.save(newConfig);
        });
        return ResponseEntity.ok(config);
    }

    @PutMapping("/config")
    public ResponseEntity<BuybackConfig> updateConfig(@RequestBody BuybackConfig updatedConfig) {
        updatedConfig.setId(1L); // ID ist hart auf 1 gesetzt, da es nur eine Config gibt
        return ResponseEntity.ok(configRepo.save(updatedConfig));
    }

    // ==========================================
    // 2. LOCATIONS (Abgabeorte)
    // ==========================================

    @GetMapping("/locations")
    public ResponseEntity<List<BuybackLocation>> getLocations() {
        return ResponseEntity.ok(locationRepo.findAll());
    }

    @PostMapping("/locations")
    public ResponseEntity<BuybackLocation> addLocation(@RequestBody BuybackLocation location) {
        return ResponseEntity.ok(locationRepo.save(location));
    }

    @DeleteMapping("/locations/{id}")
    public ResponseEntity<Void> deleteLocation(@PathVariable Long id) {
        locationRepo.deleteById(id);
        return ResponseEntity.ok().build();
    }

    // ==========================================
    // 3. CATEGORY RULES (Kategorien-Whitelist)
    // ==========================================

    public record CategoryRuleDto(Long categoryId, String categoryName, Double modifier) {}
    public record AddCategoryRuleRequest(String categoryName, Double modifier) {}

    @GetMapping("/categories")
    public ResponseEntity<List<CategoryRuleDto>> getCategoryRules() {
        List<CategoryRuleDto> result = categoryRuleRepo.findAll().stream().map(rule -> {
            String name = invCategoryRepo.findById(rule.getCategoryId())
                    .map(InvCategory::getCategoryName)
                    .orElse("Unknown Category");
            return new CategoryRuleDto(rule.getCategoryId(), name, rule.getModifier());
        }).toList();
        return ResponseEntity.ok(result);
    }

    // ==========================================
    // 3. CATEGORY RULES (Kategorien-Whitelist)
    // ==========================================
    @PostMapping("/categories")
    public ResponseEntity<BuybackCategoryRule> addCategoryRule(@RequestBody AddCategoryRuleRequest request) {
        // .trim() hinzugefügt, um unsichtbare Leerzeichen abzufangen
        InvCategory category = invCategoryRepo.findByCategoryNameIgnoreCase(request.categoryName().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kategorie nicht in der EVE DB gefunden."));

        BuybackCategoryRule rule = new BuybackCategoryRule();
        rule.setCategoryId(category.getCategoryId());
        rule.setModifier(request.modifier());
        return ResponseEntity.ok(categoryRuleRepo.save(rule));
    }

    // ==========================================
    // 5. ESI STATION SEARCH (UX Feature)
    // ==========================================
    @GetMapping("/search-station")
    public ResponseEntity<Long> searchStationId(@RequestParam String name) {
        // Eingeloggten Admin holen
        Long charId = (Long) Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication()).getPrincipal();
        assert charId != null;
        com.eve.own.auth.backend.domain.character.entity.Character reqChar = characterRepo.findById(charId).orElseThrow();
        String token = authService.getValidAccessToken(reqChar);

        String searchTerm = name.trim();

        Pattern pattern = Pattern.compile("^(.*?)\\s+-\\s+(.*?)\\s*\\((.*?)\\)$");
        Matcher matcher = pattern.matcher(searchTerm);

        if (matcher.matches()) {
            searchTerm = matcher.group(2).trim();
        }

        try {
            // 2. Den bereinigten searchTerm an ESI übergeben
            var searchResult = esiService.searchStructureOrStation(charId, token, searchTerm).data();

            if (searchResult != null) {
                if (searchResult.station() != null && !searchResult.station().isEmpty()) {
                    return ResponseEntity.ok(searchResult.station().getFirst());
                }
                if (searchResult.structure() != null && !searchResult.structure().isEmpty()) {
                    return ResponseEntity.ok(searchResult.structure().getFirst());
                }
            }
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            System.err.println("ESI Search Error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }


    // ==========================================
    // 4. TYPE RULES (Einzelitem Whitelist/Blacklist)
    // ==========================================
    @PostMapping("/types")
    public ResponseEntity<BuybackTypeRule> addTypeRule(@RequestBody AddTypeRuleRequest request) {
        // Wir nutzen die bewährte native Query aus dem Repository statt dem fehleranfälligen JPA IgnoreCase
        TypeDetailsProjection details = invTypeRepo.findTypeDetailsByName(request.typeName().trim());

        if (details == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Item nicht in der EVE DB gefunden.");
        }

        BuybackTypeRule rule = new BuybackTypeRule();
        rule.setTypeId(details.getTypeId());
        rule.setModifier(request.modifier());
        rule.setIsBlacklisted(request.isBlacklisted() != null ? request.isBlacklisted() : false);

        return ResponseEntity.ok(typeRuleRepo.save(rule));
    }

    @DeleteMapping("/categories/{categoryId}")
    public ResponseEntity<Void> deleteCategoryRule(@PathVariable Long categoryId) {
        categoryRuleRepo.deleteById(categoryId);
        return ResponseEntity.ok().build();
    }

    // ==========================================
    // 4. TYPE RULES (Einzelitem Whitelist/Blacklist)
    // ==========================================

    public record TypeRuleDto(Long typeId, String typeName, Double modifier, Boolean isBlacklisted) {}
    public record AddTypeRuleRequest(String typeName, Double modifier, Boolean isBlacklisted) {}

    @GetMapping("/types")
    public ResponseEntity<List<TypeRuleDto>> getTypeRules() {
        List<TypeRuleDto> result = typeRuleRepo.findAll().stream().map(rule -> {
            String name = invTypeRepo.findById(rule.getTypeId())
                    .map(InvType::getTypeName)
                    .orElse("Unknown Item");
            return new TypeRuleDto(rule.getTypeId(), name, rule.getModifier(), rule.getIsBlacklisted());
        }).toList();
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/types/{typeId}")
    public ResponseEntity<Void> deleteTypeRule(@PathVariable Long typeId) {
        typeRuleRepo.deleteById(typeId);
        return ResponseEntity.ok().build();
    }
}