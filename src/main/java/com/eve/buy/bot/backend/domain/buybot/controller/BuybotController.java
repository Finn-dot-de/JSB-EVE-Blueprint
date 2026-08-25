package com.eve.buy.bot.backend.domain.buybot.controller;

import com.eve.buy.bot.backend.audit.AuditCategory;
import com.eve.buy.bot.backend.audit.AuditService;
import com.eve.buy.bot.backend.audit.AuditSeverity;
import com.eve.buy.bot.backend.domain.buybot.dto.ParsedItemDto;
import com.eve.buy.bot.backend.domain.buybot.dto.PublicConfigDto;
import com.eve.buy.bot.backend.domain.buybot.entity.BuybackConfig;
import com.eve.buy.bot.backend.domain.buybot.entity.BuybackLocation;
import com.eve.buy.bot.backend.domain.buybot.repository.BuybackConfigRepository;
import com.eve.buy.bot.backend.domain.buybot.repository.BuybackLocationRepository;
import com.eve.buy.bot.backend.domain.buybot.service.BuybackCalculationService;
import com.eve.buy.bot.backend.domain.buybot.service.EvePasteParserService;
import com.eve.buy.bot.backend.domain.buybot.service.InjectorInventoryService;
import com.eve.buy.bot.backend.domain.buybot.service.MarketService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Öffentliche Schnittstelle des Buybots.
 *
 * <p>Preisberechnung, Abgabeorte und Konfiguration sind bewusst ohne Anmeldung erreichbar:
 * Spieler sollen einen Preis ausrechnen können, ohne sich vorher mit EVE SSO anzumelden.
 * Einzige Ausnahme ist {@link #getMyInjectors()} - der eigene Besitz setzt eine Sitzung
 * voraus.
 */
@RestController
@RequestMapping("/api/buybot")
@RequiredArgsConstructor
public class BuybotController {

    private final EvePasteParserService parserService;
    private final BuybackCalculationService calculationService;
    private final BuybackLocationRepository locationRepo;
    private final BuybackConfigRepository configRepo;
    private final MarketService marketService;
    private final AuditService auditService;
    private final InjectorInventoryService injectorInventory;

    /**
     * Anfrage zur Preisberechnung.
     *
     * @param rawInput   die aus EVE kopierte Item-Liste
     * @param locationId der gewählte Abgabeort
     */
    public record CalculateRequest(String rawInput, Long locationId) {}

    /**
     * Aktueller Preis eines Skill Injectors.
     *
     * @param typeId EVE-Type-ID des Injectors
     * @param name   Anzeigename
     * @param price  Jita-Verkaufspreis in ISK
     */
    public record InjectorPriceDto(long typeId, String name, double price) {}

    /**
     * Rechnet eine eingefügte Item-Liste in einen Ankaufspreis um.
     *
     * @param request Liste und Abgabeort
     * @return die bewerteten Positionen, HTTP 503 im Wartungsmodus
     */
    @PostMapping("/calculate")
    public ResponseEntity<List<ParsedItemDto>> calculateBuyback(@RequestBody CalculateRequest request) {
        if (request.rawInput() == null || request.rawInput().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        BuybackConfig config = configRepo.findById(1L).orElse(null);
        if (config != null && !config.isBotActive()) {
            auditService.record(AuditCategory.QUOTE, AuditSeverity.INFO,
                    "Preisanfrage im Wartungsmodus abgewiesen", null);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        List<ParsedItemDto> parsedItems = parserService.parseAndResolveInput(request.rawInput());
        calculationService.calculatePrices(parsedItems, request.locationId());

        auditService.record(AuditCategory.QUOTE, AuditSeverity.INFO,
                "Preisanfrage: %d Positionen, %s ISK".formatted(parsedItems.size(), totalOf(parsedItems)),
                "Abgabeort " + request.locationId());

        return ResponseEntity.ok(parsedItems);
    }

    /**
     * Liefert die konfigurierten Abgabeorte für die Auswahl im Frontend.
     *
     * @return alle Abgabeorte
     */
    @GetMapping("/locations")
    public ResponseEntity<List<BuybackLocation>> getLocations() {
        return ResponseEntity.ok(locationRepo.findAll());
    }

    /**
     * Liefert den aktuellen Jita-Preis eines Large Skill Injectors.
     *
     * <p>Das Frontend rechnet den Ankaufspreis damit in "so viele Injektoren" um.
     *
     * @return Type-ID, Name und Preis
     */
    @GetMapping("/injector-price")
    public ResponseEntity<InjectorPriceDto> getInjectorPrice() {
        return ResponseEntity.ok(new InjectorPriceDto(
                MarketService.LARGE_SKILL_INJECTOR_TYPE_ID,
                "Large Skill Injector",
                marketService.getSkillInjectorPrice()));
    }

    /**
     * Bestand an Skill Injectors des angemeldeten Charakters.
     *
     * @param quantity Anzahl im Besitz, {@code null} wenn nicht ermittelbar
     * @param hint     Grund, falls keine Zahl geliefert werden konnte
     */
    public record MyInjectorsDto(Long quantity, String hint) {}

    /**
     * Liefert, wie viele Skill Injectors der angemeldete Charakter besitzt.
     *
     * <p>Nur mit Anmeldung erreichbar - ohne Sitzung greift die Sicherheitsregel und der
     * Aufruf endet mit HTTP 401. Das Frontend wertet das als "nicht angemeldet" und zeigt
     * stattdessen die Umrechnung des Ankaufspreises.
     *
     * @return Anzahl im Besitz oder ein Hinweis, warum sie fehlt
     */
    @GetMapping("/my-injectors")
    public ResponseEntity<MyInjectorsDto> getMyInjectors() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Long characterId)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        InjectorInventoryService.InjectorStock stock = injectorInventory.getStock(characterId);
        return ResponseEntity.ok(new MyInjectorsDto(stock.quantity(), stock.hint()));
    }

    /**
     * Liefert den öffentlichen Teil der Konfiguration.
     *
     * <p>Enthält Wartungszustand, Bot-Sprüche, Reaktionsschwellen und die Angaben für die
     * Vertragserstellung - bewusst ohne Preisbasis und Modifikatoren.
     *
     * @return die öffentliche Konfiguration
     */
    @GetMapping("/config")
    public ResponseEntity<PublicConfigDto> getPublicConfig() {
        BuybackConfig config = configRepo.findById(1L).orElseGet(BuybackConfig::new);
        return ResponseEntity.ok(new PublicConfigDto(
                config.isBotActive(),
                config.getMaintenanceTitle(),
                config.getMaintenanceMessage(),
                config.getVolumeThreshold(),
                config.getValueThreshold(),
                config.getItemValueThreshold(),
                config.getContractRecipient(),
                config.getContractExpireDays(),
                config.getContractDaysToComplete(),
                config.getContractNote(),
                config.getBotTexts()));
    }

    /**
     * Summiert den Ankaufspreis einer Berechnung für den Protokolleintrag.
     *
     * @param items die bewerteten Positionen
     * @return die Summe als ganze ISK
     */
    private long totalOf(List<ParsedItemDto> items) {
        double total = 0.0;
        for (ParsedItemDto item : items) {
            total += item.getTotalPrice() != null ? item.getTotalPrice() : 0.0;
        }
        return Math.round(total);
    }
}
