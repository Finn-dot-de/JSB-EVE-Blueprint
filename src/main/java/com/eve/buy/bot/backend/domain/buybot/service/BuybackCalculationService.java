package com.eve.buy.bot.backend.domain.buybot.service;

import com.eve.buy.bot.backend.domain.buybot.dto.MarketPriceDto;
import com.eve.buy.bot.backend.domain.buybot.dto.ParsedItemDto;
import com.eve.buy.bot.backend.domain.buybot.dto.ReprocessMaterialProjection;
import com.eve.buy.bot.backend.domain.buybot.dto.TypeDetailsProjection;
import com.eve.buy.bot.backend.domain.buybot.entity.BuybackCategoryRule;
import com.eve.buy.bot.backend.domain.buybot.entity.BuybackConfig;
import com.eve.buy.bot.backend.domain.buybot.entity.BuybackLocation;
import com.eve.buy.bot.backend.domain.buybot.entity.BuybackTypeRule;
import com.eve.buy.bot.backend.domain.buybot.repository.BuybackCategoryRuleRepository;
import com.eve.buy.bot.backend.domain.buybot.repository.BuybackConfigRepository;
import com.eve.buy.bot.backend.domain.buybot.repository.BuybackLocationRepository;
import com.eve.buy.bot.backend.domain.buybot.repository.BuybackTypeRuleRepository;
import com.eve.buy.bot.backend.domain.eve.repository.InvTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Die Preis-Engine des Buybots.
 *
 * <p>Der Ankaufspreis je Einheit entsteht so: Marktpreis oder Reprocessing-Wert als Basis,
 * darauf der Modifikator (Item schlaegt Kategorie schlaegt Standard), abzueglich
 * Transportgebuehr je Kubikmeter und Sicherheitsgebuehr auf den Warenwert.
 *
 * <p>Wird ein Item ueber die Reprocessing-Ausbeute bewertet, gilt das auch fuer den
 * Transport: berechnet wird dann mit dem Volumen der Ausbeute, nicht mit dem des
 * Ausgangsitems. Der Betreiber verwertet vor Ort und schafft nur noch die Mineralien weg -
 * die sind meist deutlich kleiner, und der Verkaeufer bekommt entsprechend mehr.
 *
 * <p>Website und Vertragspruefung nutzen bewusst dieselbe Klasse, damit beide nie zu
 * unterschiedlichen Preisen kommen.
 */
@Service
@RequiredArgsConstructor
public class BuybackCalculationService {

    // Maschinenlesbare Status-Codes - das Frontend übersetzt darüber (DE/EN).
    public static final String STATUS_OK = "OK";
    public static final String STATUS_BLOCKED = "BLOCKED";
    public static final String STATUS_NOT_LISTED = "NOT_LISTED";
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    // Woraus der Basispreis stammt
    public static final String SOURCE_MARKET = "MARKET";
    public static final String SOURCE_REPROCESSED = "REPROCESSED";

    private final MarketService marketService;
    private final BuybackConfigRepository configRepo;
    private final BuybackLocationRepository locationRepo;
    private final BuybackTypeRuleRepository typeRuleRepo;
    private final BuybackCategoryRuleRepository categoryRuleRepo;
    private final InvTypeRepository invTypeRepo;

    /** Die für ein Item geltenden Regeln - einmal aufgelöst, mehrfach gebraucht. */
    private record RuleContext(BuybackTypeRule typeRule, BuybackCategoryRule categoryRule) {}

    /** Ausbeute pro einzelner Einheit des Items (portionSize schon eingerechnet). */
    private record MaterialYield(long materialTypeId, double perUnit) {}

    /**
     * Alles, was für die Bewertung einer einzelnen Position gebraucht wird.
     *
     * <p>Gebündelt, damit die Bewertung nicht sechs gleichartige Karten durchreicht - drei
     * davon wären Maps, die sich beim Aufruf unbemerkt vertauschen lassen.
     *
     * @param config          die geltende Konfiguration
     * @param location        der gewählte Abgabeort
     * @param prices          Marktpreise je Type-ID, Items und Ausbeute-Materialien
     * @param materialVolumes Volumen je Ausbeute-Material, für die Transportgebühr
     * @param rules           die Regeln je Item
     * @param yields          die Reprocessing-Ausbeute je Item
     */
    private record PricingContext(BuybackConfig config,
                                  BuybackLocation location,
                                  Map<Long, MarketPriceDto> prices,
                                  Map<Long, Double> materialVolumes,
                                  Map<Long, RuleContext> rules,
                                  Map<Long, List<MaterialYield>> yields) {}

    /**
     * Bewertet die übergebenen Positionen und schreibt Preis und Status hinein.
     *
     * <p>Alle Marktpreise werden in einem Zug geholt - auch die der
     * Reprocessing-Materialien, damit keine zweite Marktabfrage nötig wird.
     *
     * @param items      die zu bewertenden Positionen
     * @param locationId der gewählte Abgabeort
     */
    public void calculatePrices(List<ParsedItemDto> items, Long locationId) {
        // Fehlende Konfiguration ist ein Betriebsfehler, ein unbekannter Abgabeort dagegen
        // eine fehlerhafte Anfrage - die Unterscheidung bestimmt Statuscode und Protokollschwere.
        BuybackConfig config = configRepo.findById(1L)
                .orElseThrow(() -> new IllegalStateException("Es existiert keine Buybot-Konfiguration."));
        BuybackLocation location = locationRepo.findById(locationId)
                .orElseThrow(() -> new IllegalArgumentException("Unbekannter Abgabeort: " + locationId));

        // 1. Regeln je Item einmal auflösen (Blacklist, Whitelist, Modifikator, Reprocessing)
        Map<Long, RuleContext> rules = new HashMap<>();
        for (ParsedItemDto item : items) {
            if (!item.isResolved()) continue;
            rules.computeIfAbsent(item.getTypeId(), id -> new RuleContext(
                    typeRuleRepo.findById(id).orElse(null),
                    item.getCategoryId() == null ? null : categoryRuleRepo.findById(item.getCategoryId()).orElse(null)
            ));
        }

        // 2. Welche Items sollen über die Reprocessing-Ausbeute bewertet werden?
        Set<Long> reprocessTypeIds = items.stream()
                .filter(ParsedItemDto::isResolved)
                .map(ParsedItemDto::getTypeId)
                .filter(id -> wantsReprocessedValue(rules.get(id)))
                .collect(Collectors.toSet());

        Map<Long, List<MaterialYield>> yields = loadYields(reprocessTypeIds);

        // 3. Alle nötigen Jita-Preise in einem Rutsch holen: Items plus deren Ausbeute-Materialien
        Set<Long> priceTypeIds = items.stream()
                .filter(ParsedItemDto::isResolved)
                .map(ParsedItemDto::getTypeId)
                .collect(Collectors.toCollection(HashSet::new));
        yields.values().forEach(list -> list.forEach(y -> priceTypeIds.add(y.materialTypeId())));

        PricingContext context = new PricingContext(
                config,
                location,
                marketService.getJitaPrices(priceTypeIds),
                loadMaterialVolumes(yields),
                rules,
                yields);

        // 4. Berechnung für jedes Item
        for (ParsedItemDto item : items) {
            priceItem(item, context);
        }
    }

    /**
     * Gleiche Preis-Matrix, aber ID-basiert: Verträge kommen über ESI ohne Item-Namen an.
     * Wird von der Vertragsprüfung genutzt, damit Website und Prüfung nie auseinanderlaufen.
     *
     * @param quantityByTypeId Stückzahl je Type-ID
     * @param locationId       der zugrunde zu legende Abgabeort
     * @return die bewerteten Positionen
     */
    public List<ParsedItemDto> calculateForTypeIds(Map<Long, Long> quantityByTypeId, Long locationId) {
        List<ParsedItemDto> items = new ArrayList<>();
        if (quantityByTypeId == null || quantityByTypeId.isEmpty()) {
            return items;
        }

        Map<Long, TypeDetailsProjection> details = new HashMap<>();
        for (TypeDetailsProjection row : invTypeRepo.findTypeDetailsByIds(quantityByTypeId.keySet())) {
            details.put(row.getTypeId(), row);
        }

        for (Map.Entry<Long, Long> entry : quantityByTypeId.entrySet()) {
            ParsedItemDto dto = new ParsedItemDto();
            dto.setTypeId(entry.getKey());
            dto.addQuantity(entry.getValue() != null ? entry.getValue() : 0L);

            TypeDetailsProjection row = details.get(entry.getKey());
            if (row != null) {
                dto.setRawName(row.getTypeName());
                dto.setVolumeEach(row.getVolume() != null ? row.getVolume() : 0.0);
                dto.setCategoryId(row.getCategoryId());
                dto.setResolved(true);
            } else {
                dto.setRawName("Type #" + entry.getKey());
            }
            items.add(dto);
        }

        calculatePrices(items, locationId);
        return items;
    }

    // =================================================================
    // REPROCESSING
    // =================================================================

    /** Einzelitem schlägt Kategorie, ohne Angabe wird der Marktpreis genommen. */
    private boolean wantsReprocessedValue(RuleContext ctx) {
        if (ctx == null) return false;
        if (ctx.typeRule() != null && ctx.typeRule().getUseReprocessedValue() != null) {
            return ctx.typeRule().getUseReprocessedValue();
        }
        if (ctx.categoryRule() != null && ctx.categoryRule().getUseReprocessedValue() != null) {
            return ctx.categoryRule().getUseReprocessedValue();
        }
        return false;
    }

    /**
     * Lädt die Reprocessing-Ausbeute und rechnet sie auf eine einzelne Einheit herunter.
     *
     * @param typeIds die Items, die über die Ausbeute bewertet werden sollen
     * @return Ausbeute je Item, leer wenn nichts zu verwerten ist
     */
    private Map<Long, List<MaterialYield>> loadYields(Set<Long> typeIds) {
        if (typeIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<MaterialYield>> result = new HashMap<>();
        for (ReprocessMaterialProjection row : invTypeRepo.findReprocessMaterials(typeIds)) {
            if (row.getMaterialTypeId() == null || row.getQuantity() == null) continue;
            // Erze & Co. werden in Batches verarbeitet - auf eine Einheit herunterrechnen
            int portion = (row.getPortionSize() == null || row.getPortionSize() < 1) ? 1 : row.getPortionSize();
            double perUnit = row.getQuantity() / (double) portion;
            result.computeIfAbsent(row.getTypeId(), k -> new ArrayList<>())
                    .add(new MaterialYield(row.getMaterialTypeId(), perUnit));
        }
        return result;
    }

    /**
     * Holt die Volumen der Ausbeute-Materialien für die Transportgebühr.
     *
     * <p>Wird nur abgefragt, wenn überhaupt etwas verwertet wird - eine Abfrage mit leerer
     * Liste würde zu einem ungültigen {@code IN ()} führen.
     *
     * @param yields die Ausbeute je Item
     * @return Volumen je Material-Type-ID, leer wenn nichts verwertet wird
     */
    private Map<Long, Double> loadMaterialVolumes(Map<Long, List<MaterialYield>> yields) {
        Set<Long> materialTypeIds = new HashSet<>();
        yields.values().forEach(list -> list.forEach(y -> materialTypeIds.add(y.materialTypeId())));
        if (materialTypeIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Double> volumes = new HashMap<>();
        for (TypeDetailsProjection row : invTypeRepo.findTypeDetailsByIds(materialTypeIds)) {
            volumes.put(row.getTypeId(), row.getVolume() != null ? row.getVolume() : 0.0);
        }
        return volumes;
    }

    // =================================================================
    // PREISBILDUNG
    // =================================================================

    /**
     * Bewertet eine einzelne Position und schreibt das Ergebnis hinein.
     *
     * @param item    die zu bewertende Position
     * @param context die für den ganzen Lauf vorbereiteten Daten
     */
    private void priceItem(ParsedItemDto item, PricingContext context) {
        if (!item.isResolved()) {
            reject(item, STATUS_UNKNOWN, "NICHT GEFUNDEN");
            return;
        }

        RuleContext ctx = context.rules().get(item.getTypeId());
        BuybackTypeRule typeRule = ctx != null ? ctx.typeRule() : null;
        BuybackCategoryRule categoryRule = ctx != null ? ctx.categoryRule() : null;

        // Blacklist-Check: schlägt die Kategorie-Whitelist bewusst aus
        if (typeRule != null && Boolean.TRUE.equals(typeRule.getIsBlacklisted())) {
            reject(item, STATUS_BLOCKED, "GESPERRT (ITEM)");
            return;
        }

        // Whitelist-Check (Muss auf Item- oder Kategorie-Ebene erlaubt sein)
        if (typeRule == null && categoryRule == null) {
            reject(item, STATUS_NOT_LISTED, "NICHT GELISTET");
            return;
        }

        // Modifikator bestimmen (Strenge Hierarchie: Item > Kategorie > Global)
        double modPercent = context.config().getGlobalModifier();
        if (categoryRule != null && categoryRule.getModifier() != null) {
            modPercent = categoryRule.getModifier();
        }
        if (typeRule != null && typeRule.getModifier() != null) {
            modPercent = typeRule.getModifier();
        }

        MarketPriceDto priceData = context.prices().get(item.getTypeId());
        double jitaSell = (priceData != null) ? priceData.getSellMin() : 0.0;
        double jitaBuy = (priceData != null) ? priceData.getBuyMax() : 0.0;
        boolean useSell = "sell".equalsIgnoreCase(context.config().getPriceBasis());

        // Basis: entweder der Marktpreis des Items oder der Wert seiner Reprocessing-Ausbeute.
        // Ist ein Item nicht verwertbar, fällt es automatisch auf den Marktpreis zurück.
        List<MaterialYield> mats = context.yields().get(item.getTypeId());
        double rate = context.config().reprocessingRateOrDefault();
        double basisPrice;
        double grossValue;
        double transportVolume;

        if (mats != null && !mats.isEmpty()) {
            basisPrice = reprocessedValue(mats, context.prices(), rate, useSell);
            grossValue = basisPrice;
            // Wer über die Ausbeute bewertet, verwertet vor Ort - wegzuschaffen sind dann
            // nur noch die Mineralien, also wird auch deren Volumen berechnet.
            transportVolume = reprocessedVolume(mats, context.materialVolumes(), rate);
            item.setPriceSource(SOURCE_REPROCESSED);
        } else {
            basisPrice = useSell ? jitaSell : jitaBuy;
            grossValue = jitaSell;
            transportVolume = item.getVolumeEach() != null ? item.getVolumeEach() : 0.0;
            item.setPriceSource(SOURCE_MARKET);
        }

        double unitBase = basisPrice * (modPercent / 100.0);
        double unitTransport = transportVolume * context.location().getTransportFee();
        double unitSecurity = grossValue * (context.location().getSecurityFee() / 100.0);

        double unitTotal = unitBase - unitTransport - unitSecurity;
        if (unitTotal < 0) {
            unitTotal = 0.0;
        }

        item.setUnitPrice(unitTotal);
        item.setTotalPrice(unitTotal * item.getQuantity());
        item.setAppliedModifier(modPercent);
        item.setStatusCode(STATUS_OK);
        item.setStatus("OK");
    }

    /**
     * Wert der Ausbeute einer einzelnen Einheit.
     *
     * @param mats    die Ausbeute des Items
     * @param prices  die Marktpreise
     * @param ratePercent die eingestellte Reprocessing-Ausbeute in Prozent
     * @param useSell {@code true}, wenn mit dem Verkaufspreis gerechnet wird
     * @return der Wert in ISK je Einheit des Ausgangsitems
     */
    private double reprocessedValue(List<MaterialYield> mats,
                                    Map<Long, MarketPriceDto> prices,
                                    double ratePercent,
                                    boolean useSell) {
        double rate = ratePercent / 100.0;
        double sum = 0.0;
        for (MaterialYield yield : mats) {
            MarketPriceDto matPrice = prices.get(yield.materialTypeId());
            if (matPrice == null) continue;
            double unit = useSell ? matPrice.getSellMin() : matPrice.getBuyMax();
            sum += yield.perUnit() * rate * unit;
        }
        return sum;
    }

    /**
     * Volumen der Ausbeute einer einzelnen Einheit - die Grundlage der Transportgebühr,
     * wenn über das Reprocessing bewertet wird.
     *
     * @param mats            die Ausbeute des Items
     * @param materialVolumes Volumen je Material
     * @param ratePercent     die eingestellte Reprocessing-Ausbeute in Prozent
     * @return das Volumen in m3 je Einheit des Ausgangsitems
     */
    private double reprocessedVolume(List<MaterialYield> mats,
                                     Map<Long, Double> materialVolumes,
                                     double ratePercent) {
        double rate = ratePercent / 100.0;
        double sum = 0.0;
        for (MaterialYield yield : mats) {
            double matVolume = materialVolumes.getOrDefault(yield.materialTypeId(), 0.0);
            sum += yield.perUnit() * rate * matVolume;
        }
        return sum;
    }

    /**
     * Markiert eine Position als nicht ankaufbar.
     *
     * @param item        die Position
     * @param statusCode  der maschinenlesbare Status
     * @param legacyLabel der deutsche Klartext für Logs und Altbestand
     */
    private void reject(ParsedItemDto item, String statusCode, String legacyLabel) {
        item.setStatusCode(statusCode);
        item.setStatus(legacyLabel);
        item.setUnitPrice(0.0);
        item.setTotalPrice(0.0);
    }
}
