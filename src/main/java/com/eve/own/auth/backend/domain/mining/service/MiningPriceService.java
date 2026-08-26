package com.eve.own.auth.backend.domain.mining.service;

import com.eve.own.auth.backend.common.MarketPriceRules;
import com.eve.own.auth.backend.domain.eve.entity.InvType;
import com.eve.own.auth.backend.domain.eve.repository.InvTypeRepository;
import com.eve.own.auth.backend.domain.market.MarketSnapshot;
import com.eve.own.auth.backend.domain.market.StationPrice;
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxRate;
import com.eve.own.auth.backend.domain.mining.repository.MiningTaxRateRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 *
 * <p>Die Preise kommen als fertiger Marktabzug herein und werden nicht selbst
 * geholt. Frueher waren es zwei eigene Netzaufrufe je Lauf - beim Regionsabzug
 * waeren daraus 822 Seiten geworden, fuer 266 Steuersaetze, die im Abzug
 * ohnehin schon drinstehen.</p>
 */
@Slf4j
@Service
public class MiningPriceService {

    /** ISK hat ingame genau zwei Nachkommastellen - wie ueberall sonst im Steuerwesen. */
    private static final int ISK_SCALE = 2;

    /** Praefixe, unter denen die SDE die komprimierten Varianten fuehrt. */
    private static final List<String> COMPRESSED_PREFIXES = List.of("Compressed ", "Batch Compressed ");

    private final MiningTaxRateRepository taxRateRepo;
    private final InvTypeRepository invTypeRepo;

    public MiningPriceService(MiningTaxRateRepository taxRateRepo,
                              InvTypeRepository invTypeRepo) {
        this.taxRateRepo = taxRateRepo;
        this.invTypeRepo = invTypeRepo;
    }

    @Transactional
    public void refreshJitaPrices(MarketSnapshot abzug) {
        List<MiningTaxRate> rates = taxRateRepo.findAll();
        if (rates.isEmpty()) {
            log.debug("Keine Steuersaetze hinterlegt, Preisabgleich entfaellt.");
            return;
        }

        List<MiningTaxRate> withoutPrice = applyDirectPrices(rates, abzug);
        int recovered = applyCompressedFallback(withoutPrice, abzug);

        taxRateRepo.saveAll(rates);
        log.info("Jita-Preise abgeglichen: {} von {} Steuersaetzen mit brauchbarem Preis, "
                        + "davon {} ueber die komprimierte Variante.",
                rates.size() - withoutPrice.size() + recovered, rates.size(), recovered);
    }

    /**
     * Setzt die direkt am Markt ermittelbaren Preise.
     *
     * @return die Saetze, fuer die kein Preis zu bekommen war
     */
    private List<MiningTaxRate> applyDirectPrices(List<MiningTaxRate> rates, MarketSnapshot abzug) {
        List<MiningTaxRate> withoutPrice = new ArrayList<>();
        for (MiningTaxRate rate : rates) {
            Double price = referencePrice(abzug.price(rate.getTypeId()));
            if (price != null) {
                rate.setCurrentJitaBuy(isk(price));
            } else {
                // Kein Preis, also auch keine 0: der zuletzt bekannte Satz bleibt
                // stehen. Eine 0 hier hiesse "diese Menge Erz ist steuerfrei".
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
    private int applyCompressedFallback(List<MiningTaxRate> withoutPrice, MarketSnapshot abzug) {
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

        int recovered = 0;
        for (Map.Entry<Long, MiningTaxRate> entry : rateByCompressedTypeId.entrySet()) {
            Double price = referencePrice(abzug.price(entry.getKey()));
            if (price != null) {
                entry.getValue().setCurrentJitaBuy(isk(price));
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
     * Macht aus der Gleitkommazahl des Marktanbieters einen exakten Preis.
     *
     * <p>Hier ist der {@code double} ehrlich: die Preise kommen als JSON-Zahl,
     * genauer geht die Leitung nicht her. {@link BigDecimal#valueOf(double)}
     * nimmt die kuerzeste Darstellung, die denselben {@code double} ergibt -
     * aus einem {@code 12.340000000000001} wird damit wieder {@code 12.34}. Ab
     * hier wird der Preis mit Mengen im Hunderttausenderbereich multipliziert,
     * und genau dabei waechst jede Ungenauigkeit mit.</p>
     */
    private static BigDecimal isk(double price) {
        return BigDecimal.valueOf(price).setScale(ISK_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Der Preis, mit dem gerechnet wird: bevorzugt das hoechste Kaufgebot, sonst
     * das guenstigste Verkaufsangebot.
     *
     * <p>Das Kaufgebot ist der Betrag, den ein Spieler sofort erloesen kann - die
     * ehrlichere Grundlage fuer eine Abgabe. Erst wenn niemand kauft, dient das
     * Verkaufsangebot als Naeherung.</p>
     *
     * <p>Der Abzug liefert schon nur brauchbare Werte; {@link MarketPriceRules}
     * steht hier trotzdem noch einmal - Guertel und Hosentraeger. Nachgebaut
     * wird die Regel dabei nicht, sie wird benutzt.</p>
     *
     * @return der Preis, oder {@code null} wenn der Markt nichts hergibt
     */
    private static Double referencePrice(StationPrice price) {
        if (price == null) {
            return null;
        }
        Double kauf = MarketPriceRules.usable(price.buy());
        return kauf != null ? kauf : MarketPriceRules.usable(price.sell());
    }
}
