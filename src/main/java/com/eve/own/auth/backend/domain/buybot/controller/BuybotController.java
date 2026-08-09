package com.eve.own.auth.backend.domain.buybot.controller;

import com.eve.own.auth.backend.domain.buybot.dto.ParsedItemDto;
import com.eve.own.auth.backend.domain.buybot.entity.BuybackLocation;
import com.eve.own.auth.backend.domain.buybot.repository.BuybackLocationRepository;
import com.eve.own.auth.backend.domain.buybot.service.BuybackCalculationService;
import com.eve.own.auth.backend.domain.buybot.service.EvePasteParserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/buybot")
@RequiredArgsConstructor
public class BuybotController {

    private final EvePasteParserService parserService;
    private final BuybackCalculationService calculationService;
    private final BuybackLocationRepository locationRepo;

    public record CalculateRequest(String rawInput, Long locationId) {}

    @PostMapping("/calculate")
    public ResponseEntity<List<ParsedItemDto>> calculateBuyback(@RequestBody CalculateRequest request) {
        if (request.rawInput() == null || request.rawInput().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        // 1. EVE-Text parsen und mit lokaler SDE abgleichen
        List<ParsedItemDto> parsedItems = parserService.parseAndResolveInput(request.rawInput());
        // 2. Preise via Fuzzwork ziehen und Margen anwenden
        calculationService.calculatePrices(parsedItems, request.locationId());
        // 3. Berechnetes Ergebnis als JSON ausliefern
        return ResponseEntity.ok(parsedItems);
    }

    // NEU: Liefert alle Abgabeorte aus der Datenbank ans Frontend
    @GetMapping("/locations")
    public ResponseEntity<List<BuybackLocation>> getLocations() {
        return ResponseEntity.ok(locationRepo.findAll());
    }
}