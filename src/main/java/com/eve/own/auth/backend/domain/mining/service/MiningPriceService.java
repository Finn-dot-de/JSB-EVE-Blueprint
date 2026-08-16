package com.eve.own.auth.backend.domain.mining.service;

import com.eve.own.auth.backend.domain.eve.entity.InvType;
import com.eve.own.auth.backend.domain.eve.repository.InvTypeRepository;
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxRate;
import com.eve.own.auth.backend.domain.mining.repository.MiningTaxRateRepository;
import com.eve.own.auth.backend.esi.EsiService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Haelt die Jita-Referenzpreise der Steuersaetze aktuell.
 *
 * <p>Die Steuer bemisst sich am Jita-Preis des abgebauten Materials. Fuer viele
 * Rohstoffe existiert dort aber schlicht kein Markt - gehandelt wird die
 * komprimierte Variante. Fuer diese Faelle greift die Rueckfallebene: der Preis
 * der komprimierten Form wird 1:1 uebernommen.</p>
 */
@Slf4j
@Service
public class MiningPriceService {

    /** Kein Preis ermittelbar - dient zugleich als Untergrenze fuer "brauchbar". */
    private static final double NO_PRICE = 0.0;

    /** Praefixe, unter denen die SDE die komprimierten Varianten fuehrt. */
    private static final List<String> COMPRESSED_PREFIXES = List.of("Compressed ", "Batch Compressed ");

    private final EsiService esiService;
    private final MiningTaxRateRepository taxRateRepo;
    private final InvTypeRepository invTypeRepo;

    public MiningPriceService(EsiService esiService,
                              MiningTaxRateRepository taxRateRepo,
                              InvTypeRepository invTypeRepo) {
        this.esiService = esiService;
        this.taxRateRepo = taxRateRepo;
        this.invTypeRepo = invTypeRepo;
    }

    @Transactional
    public void refreshJitaPrices() {
        List<MiningTaxRate> rates = taxRateRepo.findAll();
        if (rates.isEmpty()) {
            log.debug("Keine Steuersaetze hinterlegt, Preisabgleich entfaellt.");
            return;
        }

        List<MiningTaxRate> withoutPrice = applyDirectPrices(rates);
        int recovered = applyCompressedFallback(withoutPrice);

        taxRateRepo.saveAll(rates);
        log.info("Jita-Preise abgeglichen: {} Steuersaetze, davon {} ueber die komprimierte Variante.",
                rates.size(), recovered);
    }

    /**
     * Setzt die direkt am Markt ermittelbaren Preise.
     *
     * @return die Saetze, fuer die kein Preis zu bekommen war
     */
    private List<MiningTaxRate> applyDirectPrices(List<MiningTaxRate> rates) {
        Map<String, EsiService.FuzzworkPrice> prices =
                esiService.getFuzzworkPrices(rates.stream().map(MiningTaxRate::getTypeId).toList());

        if (prices == null || prices.isEmpty()) {
            return new ArrayList<>(rates);
        }

        List<MiningTaxRate> withoutPrice = new ArrayList<>();
        for (MiningTaxRate rate : rates) {
            double price = referencePrice(prices.get(String.valueOf(rate.getTypeId())));
            if (price > NO_PRICE) {
                rate.setCurrentJitaBuy(price);
            } else {
                withoutPrice.add(rate);
            }
        }
        return withoutPrice;
    }

    /**
     * Zweiter Versuch ueber die komprimierte Variante.
     *
     * @return wie viele Saetze dadurch doch noch einen Preis bekommen haben
     */
    private int applyCompressedFallback(List<MiningTaxRate> withoutPrice) {
        if (withoutPrice.isEmpty()) {
            return 0;
        }

        Map<Long, MiningTaxRate> rateByCompressedTypeId = new HashMap<>();
        for (MiningTaxRate rate : withoutPrice) {
            findCompressedVariant(rate.getTypeName())
                    .ifPresent(compressed -> rateByCompressedTypeId.put(compressed.getTypeId(), rate));
        }
        if (rateByCompressedTypeId.isEmpty()) {
            return 0;
        }

        Map<String, EsiService.FuzzworkPrice> prices =
                esiService.getFuzzworkPrices(List.copyOf(rateByCompressedTypeId.keySet()));
        if (prices == null || prices.isEmpty()) {
            return 0;
        }

        int recovered = 0;
        for (Map.Entry<Long, MiningTaxRate> entry : rateByCompressedTypeId.entrySet()) {
            double price = referencePrice(prices.get(String.valueOf(entry.getKey())));
            if (price > NO_PRICE) {
                entry.getValue().setCurrentJitaBuy(price);
                recovered++;
            }
        }
        return recovered;
    }

    private Optional<InvType> findCompressedVariant(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return Optional.empty();
        }
        return COMPRESSED_PREFIXES.stream()
                .map(prefix -> invTypeRepo.findByTypeNameIgnoreCase(prefix + typeName))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /**
     * Der Preis, mit dem gerechnet wird: bevorzugt das hoechste Kaufgebot, sonst
     * das guenstigste Verkaufsangebot.
     *
     * <p>Das Kaufgebot ist der Betrag, den ein Spieler sofort erloesen kann - die
     * ehrlichere Grundlage fuer eine Abgabe. Erst wenn niemand kauft, dient das
     * Verkaufsangebot als Naeherung.</p>
     */
    private static double referencePrice(EsiService.FuzzworkPrice price) {
        if (price == null) {
            return NO_PRICE;
        }
        if (price.buy() != null && price.buy().max() != null && price.buy().max() > NO_PRICE) {
            return price.buy().max();
        }
        if (price.sell() != null && price.sell().min() != null && price.sell().min() > NO_PRICE) {
            return price.sell().min();
        }
        return NO_PRICE;
    }
}
