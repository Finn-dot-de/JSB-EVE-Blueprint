package com.eve.buy.bot.backend.domain.buybot.controller;

import com.eve.buy.bot.backend.audit.AuditCategory;
import com.eve.buy.bot.backend.audit.AuditService;
import com.eve.buy.bot.backend.audit.AuditSeverity;
import com.eve.buy.bot.backend.domain.auth.service.AuthService;
import com.eve.buy.bot.backend.domain.buybot.dto.ReprocessMaterialProjection;
import com.eve.buy.bot.backend.domain.buybot.dto.TypeDetailsProjection;
import com.eve.buy.bot.backend.domain.buybot.entity.BuybackCategoryRule;
import com.eve.buy.bot.backend.domain.buybot.entity.BuybackConfig;
import com.eve.buy.bot.backend.domain.buybot.entity.BuybackLocation;
import com.eve.buy.bot.backend.domain.buybot.entity.BuybackTypeRule;
import com.eve.buy.bot.backend.domain.buybot.entity.ContractCheck;
import com.eve.buy.bot.backend.domain.buybot.repository.BuybackCategoryRuleRepository;
import com.eve.buy.bot.backend.domain.buybot.repository.BuybackConfigRepository;
import com.eve.buy.bot.backend.domain.buybot.repository.BuybackLocationRepository;
import com.eve.buy.bot.backend.domain.buybot.repository.BuybackTypeRuleRepository;
import com.eve.buy.bot.backend.domain.buybot.service.ContractCheckService;
import com.eve.buy.bot.backend.domain.character.repository.CharacterRepository;
import com.eve.buy.bot.backend.domain.eve.entity.InvCategory;
import com.eve.buy.bot.backend.domain.eve.entity.InvType;
import com.eve.buy.bot.backend.domain.eve.repository.InvCategoryRepository;
import com.eve.buy.bot.backend.domain.eve.repository.InvTypeRepository;
import com.eve.buy.bot.backend.esi.EsiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verwaltung des Buybots: Preisbasis, Abgabeorte, Whitelist und Vertragspruefung.
 *
 * <p>Jede Aenderung an der Ankaufsmatrix wird zusaetzlich ins Protokoll geschrieben, weil
 * sie unmittelbar beeinflusst, wie viel ISK ausgezahlt wird.
 */
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
    private final ContractCheckService contractCheckService;
    private final AuditService auditService;

    /**
     * Hält eine Änderung an der Ankaufsmatrix im Protokoll fest.
     *
     * <p>Wer wann welchen Modifikator verschoben hat, ist bei einem Werkzeug, das ISK
     * bewegt, die wichtigste Rückfrage überhaupt.
     *
     * @param message Beschreibung der Änderung
     */
    private void auditAdmin(String message) {
        auditService.record(AuditCategory.ADMIN, AuditSeverity.INFO, message, null);
    }

    // ==========================================
    // 1. CONFIGURATION (Preise & Schwellenwerte)
    // ==========================================
    /**
     * Liefert die Konfiguration und legt sie beim ersten Aufruf an.
     *
     * @return die aktuelle Konfiguration
     */
    @GetMapping("/config")
    public ResponseEntity<BuybackConfig> getConfig() {
        BuybackConfig config = configRepo.findById(1L).orElseGet(() -> {
            BuybackConfig newConfig = new BuybackConfig();
            newConfig.setId(1L);
            return configRepo.save(newConfig);
        });
        return ResponseEntity.ok(config);
    }

    /**
     * Zusammenführen statt Überschreiben: ein Feld, das nicht mitgeschickt wird,
     * bleibt unverändert. Sonst würde ein Teil-Formular (z.B. nur Preisbasis)
     * alle übrigen Einstellungen auf NULL setzen.
     */
    @PutMapping("/config")
    public ResponseEntity<BuybackConfig> updateConfig(@RequestBody BuybackConfig incoming) {
        BuybackConfig current = configRepo.findById(1L).orElseGet(() -> {
            BuybackConfig fresh = new BuybackConfig();
            fresh.setId(1L);
            return fresh;
        });

        if (incoming.getPriceBasis() != null) current.setPriceBasis(incoming.getPriceBasis());
        if (incoming.getGlobalModifier() != null) current.setGlobalModifier(incoming.getGlobalModifier());
        if (incoming.getVolumeThreshold() != null) current.setVolumeThreshold(incoming.getVolumeThreshold());
        if (incoming.getValueThreshold() != null) current.setValueThreshold(incoming.getValueThreshold());
        if (incoming.getItemValueThreshold() != null) current.setItemValueThreshold(incoming.getItemValueThreshold());
        if (incoming.getReprocessingRate() != null) current.setReprocessingRate(incoming.getReprocessingRate());

        if (incoming.getBotEnabled() != null) current.setBotEnabled(incoming.getBotEnabled());
        if (incoming.getMaintenanceTitle() != null) current.setMaintenanceTitle(incoming.getMaintenanceTitle());
        if (incoming.getMaintenanceMessage() != null) current.setMaintenanceMessage(incoming.getMaintenanceMessage());

        if (incoming.getContractRecipient() != null) current.setContractRecipient(incoming.getContractRecipient());
        if (incoming.getContractExpireDays() != null) current.setContractExpireDays(incoming.getContractExpireDays());
        if (incoming.getContractDaysToComplete() != null) current.setContractDaysToComplete(incoming.getContractDaysToComplete());
        if (incoming.getContractNote() != null) current.setContractNote(incoming.getContractNote());

        if (incoming.getContractCheckEnabled() != null) current.setContractCheckEnabled(incoming.getContractCheckEnabled());
        // 0 aus dem Formular bedeutet "kein Charakter gewählt"
        if (incoming.getContractCheckCharacterId() != null) {
            current.setContractCheckCharacterId(incoming.getContractCheckCharacterId() == 0L ? null : incoming.getContractCheckCharacterId());
        }
        if (incoming.getPriceTolerancePercent() != null) current.setPriceTolerancePercent(incoming.getPriceTolerancePercent());
        if (incoming.getCheckIntervalMinutes() != null) current.setCheckIntervalMinutes(incoming.getCheckIntervalMinutes());
        if (incoming.getNotifyTarget() != null) current.setNotifyTarget(incoming.getNotifyTarget());
        if (incoming.getDiscordWebhookUrl() != null) current.setDiscordWebhookUrl(incoming.getDiscordWebhookUrl());
        if (incoming.getNotifyMailRecipientId() != null) {
            current.setNotifyMailRecipientId(incoming.getNotifyMailRecipientId() == 0L ? null : incoming.getNotifyMailRecipientId());
        }
        if (incoming.getNotifyOnOk() != null) current.setNotifyOnOk(incoming.getNotifyOnOk());

        if (incoming.getBotTexts() != null) current.setBotTexts(incoming.getBotTexts());

        current.setId(1L); // ID ist hart auf 1 gesetzt, da es nur eine Config gibt
        BuybackConfig saved = configRepo.save(current);
        auditAdmin("Konfiguration gespeichert: Basis %s, Modifikator %s %%, Ausbeute %s %%, Ankauf %s"
                .formatted(saved.getPriceBasis(), saved.getGlobalModifier(),
                        saved.getReprocessingRate(), saved.isBotActive() ? "aktiv" : "pausiert"));
        return ResponseEntity.ok(saved);
    }

    // ==========================================
    // 2. LOCATIONS (Abgabeorte)
    // ==========================================
    /**
     * Liefert alle Abgabeorte.
     *
     * @return die Abgabeorte
     */
    @GetMapping("/locations")
    public ResponseEntity<List<BuybackLocation>> getLocations() {
        return ResponseEntity.ok(locationRepo.findAll());
    }

    /**
     * Legt einen Abgabeort an.
     *
     * @param location Name, Gebuehren und optionale Station-ID
     * @return der gespeicherte Abgabeort
     */
    @PostMapping("/locations")
    public ResponseEntity<BuybackLocation> addLocation(@RequestBody BuybackLocation location) {
        BuybackLocation savedLocation = locationRepo.save(location);
        auditAdmin("Abgabeort angelegt: %s (Transport %s ISK/m3, Sicherheit %s %%, Station %s)"
                .formatted(savedLocation.getName(), savedLocation.getTransportFee(),
                        savedLocation.getSecurityFee(), savedLocation.getStationId()));
        return ResponseEntity.ok(savedLocation);
    }

    /**
     * Loescht einen Abgabeort.
     *
     * @param id ID des Abgabeorts
     * @return eine leere Antwort
     */
    @DeleteMapping("/locations/{id}")
    public ResponseEntity<Void> deleteLocation(@PathVariable Long id) {
        locationRepo.findById(id).ifPresent(loc -> auditAdmin("Abgabeort gelöscht: " + loc.getName()));
        locationRepo.deleteById(id);
        return ResponseEntity.ok().build();
    }

    // ==========================================
    // 3. CATEGORY RULES (Kategorien-Whitelist)
    // ==========================================

    public record CategoryRuleDto(Long categoryId, String categoryName, Double modifier, Boolean useReprocessedValue) {}
    public record AddCategoryRuleRequest(String categoryName, Double modifier, Boolean useReprocessedValue) {}

    /**
     * Liefert die freigegebenen Kategorien samt Namen aus der Statikdatenbank.
     *
     * @return die Kategorie-Whitelist
     */
    @GetMapping("/categories")
    public ResponseEntity<List<CategoryRuleDto>> getCategoryRules() {
        List<CategoryRuleDto> result = categoryRuleRepo.findAll().stream().map(rule -> {
            String name = invCategoryRepo.findById(rule.getCategoryId())
                    .map(InvCategory::getCategoryName)
                    .orElse("Unknown Category");
            return new CategoryRuleDto(rule.getCategoryId(), name, rule.getModifier(),
                    Boolean.TRUE.equals(rule.getUseReprocessedValue()));
        }).toList();
        return ResponseEntity.ok(result);
    }

    // ==========================================
    // 3. CATEGORY RULES (Kategorien-Whitelist)
    // ==========================================
    /**
     * Gibt eine Kategorie frei.
     *
     * @param request Kategoriename, Modifikator und Reprocessing-Schalter
     * @return die gespeicherte Regel
     */
    @PostMapping("/categories")
    public ResponseEntity<BuybackCategoryRule> addCategoryRule(@RequestBody AddCategoryRuleRequest request) {
        // .trim() hinzugefügt, um unsichtbare Leerzeichen abzufangen
        InvCategory category = invCategoryRepo.findByCategoryNameIgnoreCase(request.categoryName().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kategorie nicht in der EVE DB gefunden."));

        BuybackCategoryRule rule = new BuybackCategoryRule();
        rule.setCategoryId(category.getCategoryId());
        rule.setModifier(request.modifier());
        rule.setUseReprocessedValue(Boolean.TRUE.equals(request.useReprocessedValue()));
        auditAdmin("Kategorie erlaubt: %s mit %s %%%s".formatted(category.getCategoryName(),
                request.modifier(), Boolean.TRUE.equals(request.useReprocessedValue()) ? ", Reprocessing-Wert" : ""));
        return ResponseEntity.ok(categoryRuleRepo.save(rule));
    }

    // ==========================================
    // 5. ESI STATION SEARCH (UX Feature)
    // ==========================================
    /**
     * Sucht die ID einer Station oder Struktur ueber ESI.
     *
     * <p>Aus dem vollen EVE-Namen wird zuvor der Stationsteil geloest, damit auch
     * ein direkt aus dem Spiel kopierter Name gefunden wird.
     *
     * @param name der gesuchte Ortsname
     * @return die gefundene ID oder HTTP 404
     */
    @GetMapping("/search-station")
    public ResponseEntity<Long> searchStationId(@RequestParam String name) {
        // Eingeloggten Admin holen
        Long charId = (Long) Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication()).getPrincipal();
        assert charId != null;
        com.eve.buy.bot.backend.domain.character.entity.Character reqChar = characterRepo.findById(charId).orElseThrow();
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
    /**
     * Legt eine Regel fuer ein einzelnes Item an.
     *
     * @param request Itemname, Modifikator, Sperre und Reprocessing-Schalter
     * @return die gespeicherte Regel
     */
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
        rule.setUseReprocessedValue(Boolean.TRUE.equals(request.useReprocessedValue()));
        auditAdmin("Item-Regel gespeichert: %s -> %s".formatted(details.getTypeName(),
                Boolean.TRUE.equals(request.isBlacklisted()) ? "gesperrt" : request.modifier() + " %"));

        return ResponseEntity.ok(typeRuleRepo.save(rule));
    }

    /**
     * Nimmt eine Kategorie aus der Whitelist.
     *
     * @param categoryId ID der Kategorie
     * @return eine leere Antwort
     */
    @DeleteMapping("/categories/{categoryId}")
    public ResponseEntity<Void> deleteCategoryRule(@PathVariable Long categoryId) {
        auditAdmin("Kategorie aus der Whitelist entfernt: " + categoryId);
        categoryRuleRepo.deleteById(categoryId);
        return ResponseEntity.ok().build();
    }

    // ==========================================
    // 4. TYPE RULES (Einzelitem Whitelist/Blacklist)
    // ==========================================

    /**
     * Eine Einzelitem-Regel für die Anzeige.
     *
     * @param typeId              Type-ID des Items
     * @param typeName            Anzeigename aus der Statikdatenbank
     * @param modifier            Preis-Modifikator in Prozent
     * @param isBlacklisted       {@code true}, wenn das Item gesperrt ist
     * @param useReprocessedValue {@code true}, wenn über die Ausbeute bewertet werden soll
     * @param reprocessable       {@code false}, wenn das Item gar keine Ausbeute hat und das
     *                            Häkchen deshalb wirkungslos bleibt
     */
    public record TypeRuleDto(Long typeId, String typeName, Double modifier, Boolean isBlacklisted,
                              Boolean useReprocessedValue, boolean reprocessable) {}
    public record AddTypeRuleRequest(String typeName, Double modifier, Boolean isBlacklisted, Boolean useReprocessedValue) {}

    /**
     * Liefert die Einzelitem-Regeln samt Itemnamen.
     *
     * @return die Einzelitem-Regeln
     */
    @GetMapping("/types")
    public ResponseEntity<List<TypeRuleDto>> getTypeRules() {
        List<BuybackTypeRule> rules = typeRuleRepo.findAll();
        Set<Long> reprocessable = reprocessableAmong(
                rules.stream().map(BuybackTypeRule::getTypeId).collect(Collectors.toSet()));

        List<TypeRuleDto> result = rules.stream().map(rule -> {
            String name = invTypeRepo.findById(rule.getTypeId())
                    .map(InvType::getTypeName)
                    .orElse("Unknown Item");
            return new TypeRuleDto(rule.getTypeId(), name, rule.getModifier(), rule.getIsBlacklisted(),
                    Boolean.TRUE.equals(rule.getUseReprocessedValue()),
                    reprocessable.contains(rule.getTypeId()));
        }).toList();
        return ResponseEntity.ok(result);
    }

    /**
     * Loescht eine Einzelitem-Regel.
     *
     * @param typeId Type-ID des Items
     * @return eine leere Antwort
     */
    @DeleteMapping("/types/{typeId}")
    public ResponseEntity<Void> deleteTypeRule(@PathVariable Long typeId) {
        auditAdmin("Item-Regel gelöscht: Type " + typeId);
        typeRuleRepo.deleteById(typeId);
        return ResponseEntity.ok().build();
    }

    /**
     * Ermittelt, welche der angegebenen Items überhaupt eine Reprocessing-Ausbeute haben.
     *
     * <p>Mineralien, Mondgüter und andere Endprodukte haben keine - dort bleibt das Häkchen
     * "Reprocessed" ohne Wirkung, und genau das soll im Admin-Panel sichtbar sein, statt
     * dass sich jemand über einen unveränderten Preis wundert.
     *
     * @param typeIds die zu prüfenden Type-IDs
     * @return die Teilmenge, die sich verwerten lässt
     */
    private Set<Long> reprocessableAmong(Set<Long> typeIds) {
        if (typeIds.isEmpty()) {
            return Set.of();
        }
        return invTypeRepo.findReprocessMaterials(typeIds).stream()
                .map(ReprocessMaterialProjection::getTypeId)
                .collect(Collectors.toSet());
    }

    // ==========================================
    // 6. VERTRAGSPRÜFUNG (ESI-Abgleich)
    // ==========================================

    public record LinkedCharacterDto(Long id, String name) {}

    /** Auswahlliste für den Prüf-Charakter: alle verknüpften Charaktere mit gültigem Refresh-Token. */
    @GetMapping("/characters")
    public ResponseEntity<List<LinkedCharacterDto>> getLinkedCharacters() {
        List<LinkedCharacterDto> result = characterRepo.findAll().stream()
                .filter(c -> c.getRefreshToken() != null && !c.getRefreshToken().isBlank())
                .map(c -> new LinkedCharacterDto(c.getId(), c.getName()))
                .sorted(java.util.Comparator.comparing(LinkedCharacterDto::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return ResponseEntity.ok(result);
    }

    /** Manueller Prüflauf aus dem Admin-Panel - ignoriert den Enabled-Schalter. */
    @PostMapping("/contract-check/run")
    public ResponseEntity<ContractCheckService.RunResult> runContractCheck() {
        auditAdmin("Vertragsprüfung manuell gestartet");
        return ResponseEntity.ok(contractCheckService.run(true));
    }

    /** Lebenszeichen der automatischen Prüfung: letzter Lauf, nächster Lauf, offene Meldungen. */
    @GetMapping("/contract-check/status")
    public ResponseEntity<ContractCheckService.CheckStatus> getContractCheckStatus() {
        return ResponseEntity.ok(contractCheckService.status());
    }

    /** Testnachricht über den konfigurierten Meldeweg, ohne auf einen Vertrag zu warten. */
    @PostMapping("/contract-check/test")
    public ResponseEntity<ContractCheckService.RunResult> testNotification() {
        auditAdmin("Testnachricht angefordert");
        return ResponseEntity.ok(contractCheckService.sendTestNotification());
    }

    /**
     * Liefert die letzten Pruefberichte, neueste zuerst.
     *
     * @param limit gewuenschte Anzahl
     * @return die Pruefberichte
     */
    @GetMapping("/contract-check/results")
    public ResponseEntity<List<ContractCheck>> getContractCheckResults(
            @RequestParam(defaultValue = "25") int limit) {
        return ResponseEntity.ok(contractCheckService.recentChecks(Math.clamp(limit, 1, 200)));
    }

    /** Vertrag wieder "vergessen", damit er beim nächsten Lauf erneut geprüft und gemeldet wird. */
    @DeleteMapping("/contract-check/results/{contractId}")
    public ResponseEntity<Void> forgetContractCheck(@PathVariable Long contractId) {
        auditAdmin("Vertrag zur erneuten Prüfung freigegeben: " + contractId);
        contractCheckService.forget(contractId);
        return ResponseEntity.ok().build();
    }
}