package com.eve.own.auth.backend.domain.mining.service;

import com.eve.own.auth.backend.domain.eve.entity.InvType;
import com.eve.own.auth.backend.domain.eve.repository.InvTypeRepository;
import com.eve.own.auth.backend.domain.mining.OreCategory;
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxRate;
import com.eve.own.auth.backend.domain.mining.repository.MiningTaxRateRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verwaltet die Steuersaetze je abbaubarem Typ.
 *
 * <p>Ein Satz besteht aus dem Prozentwert, den ein Director festlegt, und dem
 * zuletzt bekannten Jita-Preis, den {@link MiningPriceService} nachfuehrt.</p>
 */
@Slf4j
@Service
public class MiningTaxRateService {

    /** Neue Saetze starten steuerfrei - ein Director gibt den Prozentwert bewusst vor. */
    private static final BigDecimal DEFAULT_TAX_PERCENTAGE = BigDecimal.ZERO;

    private static final BigDecimal UNKNOWN_PRICE = BigDecimal.ZERO;

    private final MiningTaxRateRepository taxRateRepo;
    private final InvTypeRepository invTypeRepo;

    public MiningTaxRateService(MiningTaxRateRepository taxRateRepo, InvTypeRepository invTypeRepo) {
        this.taxRateRepo = taxRateRepo;
        this.invTypeRepo = invTypeRepo;
    }

    @Transactional(readOnly = true)
    public List<MiningTaxRate> findAll() {
        return taxRateRepo.findAll();
    }

    @Transactional(readOnly = true)
    public Map<Long, MiningTaxRate> findAllByTypeId() {
        return taxRateRepo.findAll().stream()
                .collect(Collectors.toMap(MiningTaxRate::getTypeId, Function.identity()));
    }

    @Transactional
    public MiningTaxRate save(MiningTaxRate rate) {
        return taxRateRepo.save(rate);
    }

    @Transactional
    public void delete(Long typeId) {
        taxRateRepo.deleteById(typeId);
    }

    /** Setzt denselben Prozentsatz fuer eine ganze Steuerklasse. */
    @Transactional
    public int updateCategory(String category, BigDecimal taxPercentage) {
        List<MiningTaxRate> rates = taxRateRepo.findAll().stream()
                .filter(rate -> rate.getCategory() != null && rate.getCategory().equalsIgnoreCase(category))
                .toList();
        rates.forEach(rate -> rate.setTaxPercentage(taxPercentage));
        taxRateRepo.saveAll(rates);
        return rates.size();
    }

    /**
     * Gleicht die Steuersaetze mit den abbaubaren Typen der SDE ab.
     *
     * <p>Drei Faelle: neue Typen kommen hinzu, Saetze zu nicht mehr abbaubaren
     * Typen fliegen raus, und falsch einsortierte Typen werden umgehaengt. Der
     * letzte Fall tritt auf, wenn CCP die SDE-Gruppe eines Typs aendert.</p>
     *
     * <p>Die vergebenen Prozentwerte bleiben dabei unangetastet.</p>
     *
     * <p>Hier stand daneben ein {@code createMissingRate}, das aus der
     * Steuerbilanz heraus einen fehlenden Satz anlegte - mitten im Lesepfad
     * eines {@code GET}. Der Fall, den es abdecken sollte (ein Erz, das die SDE
     * erst nach dem letzten Abgleich bekommen hat), ist genau der erste Fall
     * dieser Methode; ein Typ ohne Satz kostet in der Zwischenzeit nichts.</p>
     */
    @Transactional
    public void synchronizeWithSde() {
        List<InvType> mineables = invTypeRepo.findAllMineables();
        if (mineables.isEmpty()) {
            log.warn("Die SDE meldet keine abbaubaren Typen - Steuersaetze bleiben unveraendert.");
            return;
        }

        List<MiningTaxRate> existing = taxRateRepo.findAll();
        removeObsolete(existing, mineables);

        Map<Long, MiningTaxRate> byTypeId = existing.stream()
                .collect(Collectors.toMap(MiningTaxRate::getTypeId, Function.identity(), (a, b) -> a));

        List<MiningTaxRate> changed = mineables.stream()
                .map(type -> reconcile(type, byTypeId.get(type.getTypeId())))
                .filter(Objects::nonNull)
                .toList();

        if (!changed.isEmpty()) {
            taxRateRepo.saveAll(changed);
        }
        log.info("Steuersaetze abgeglichen: {} abbaubare Typen, {} angelegt oder korrigiert.",
                mineables.size(), changed.size());
    }

    /** @return den zu speichernden Satz, oder {@code null}, wenn nichts zu tun ist */
    private MiningTaxRate reconcile(InvType type, MiningTaxRate existing) {
        String category = OreCategory.ofGroup(type.getGroupId()).dbValue();

        if (existing == null) {
            MiningTaxRate rate = new MiningTaxRate();
            rate.setTypeId(type.getTypeId());
            rate.setTypeName(type.getTypeName());
            rate.setTaxPercentage(DEFAULT_TAX_PERCENTAGE);
            rate.setCurrentJitaBuy(UNKNOWN_PRICE);
            rate.setCategory(category);
            return rate;
        }
        if (!category.equals(existing.getCategory())) {
            existing.setCategory(category);
            return existing;
        }
        return null;
    }

    private void removeObsolete(List<MiningTaxRate> existing, List<InvType> mineables) {
        Set<Long> validTypeIds = mineables.stream().map(InvType::getTypeId).collect(Collectors.toSet());
        List<MiningTaxRate> obsolete = existing.stream()
                .filter(rate -> !validTypeIds.contains(rate.getTypeId()))
                .toList();
        if (!obsolete.isEmpty()) {
            taxRateRepo.deleteAll(obsolete);
            existing.removeAll(obsolete);
            log.info("{} Steuersaetze zu nicht mehr abbaubaren Typen entfernt.", obsolete.size());
        }
    }
}
