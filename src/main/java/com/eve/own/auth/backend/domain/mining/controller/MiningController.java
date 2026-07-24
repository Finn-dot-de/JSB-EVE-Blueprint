package com.eve.own.auth.backend.domain.mining.controller;

import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.CharacterActivity;
import com.eve.own.auth.backend.domain.character.entity.CharacterMining;
import com.eve.own.auth.backend.domain.character.repository.CharacterActivityRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterMiningRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.eve.entity.InvType;
import com.eve.own.auth.backend.domain.eve.repository.InvTypeRepository;
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxInvoice;
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxRate;
import com.eve.own.auth.backend.domain.mining.repository.MiningTaxInvoiceRepository;
import com.eve.own.auth.backend.domain.mining.repository.MiningTaxRateRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/mining")
public class MiningController {

    private final CharacterRepository characterRepo;
    private final CharacterMiningRepository characterMiningRepo;
    private final CharacterActivityRepository activityRepo; // NEU
    private final MiningTaxRateRepository taxRateRepo;
    private final InvTypeRepository invTypeRepo;
    private final MiningTaxInvoiceRepository invoiceRepo; // NEU
    private final ObjectMapper objectMapper;

    public MiningController(CharacterRepository characterRepo, CharacterMiningRepository characterMiningRepo,
                            CharacterActivityRepository activityRepo, MiningTaxRateRepository taxRateRepo,
                            InvTypeRepository invTypeRepo, MiningTaxInvoiceRepository invoiceRepo,
                            ObjectMapper objectMapper) {
        this.characterRepo = characterRepo;
        this.characterMiningRepo = characterMiningRepo;
        this.activityRepo = activityRepo;
        this.taxRateRepo = taxRateRepo;
        this.invTypeRepo = invTypeRepo;
        this.invoiceRepo = invoiceRepo;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initAllMineables() {
        try {
            List<InvType> allMineables = invTypeRepo.findAllMineables();
            List<MiningTaxRate> existingRates = taxRateRepo.findAll();
            Set<Long> validIds = allMineables.stream().map(InvType::getTypeId).collect(Collectors.toSet());

            List<MiningTaxRate> junkToDelete = existingRates.stream().filter(r -> !validIds.contains(r.getTypeId())).toList();
            if (!junkToDelete.isEmpty()) taxRateRepo.deleteAll(junkToDelete);

            Set<Long> existingIds = existingRates.stream().map(MiningTaxRate::getTypeId).collect(Collectors.toSet());
            List<MiningTaxRate> toSave = new ArrayList<>();

            for (InvType t : allMineables) {
                // Die korrekte SDE Kategorie ermitteln
                String correctCategory = "ORE";
                if (t.getGroupId() == 423L) correctCategory = "ICE";
                else if (t.getGroupId() == 711L) correctCategory = "GAS";
                else if (List.of(1884L, 1920L, 1921L, 1922L, 1923L).contains(t.getGroupId())) correctCategory = "MOON";

                if (!existingIds.contains(t.getTypeId())) {
                    // Neu anlegen
                    MiningTaxRate rate = new MiningTaxRate();
                    rate.setTypeId(t.getTypeId());
                    rate.setTypeName(t.getTypeName());
                    rate.setTaxPercentage(0.0);
                    rate.setCurrentJitaBuy(0.0);
                    rate.setCategory(correctCategory);
                    toSave.add(rate);
                } else {
                    // Update: Falls die Kategorie in der DB falsch ist (wie bei deinem White Glaze), reparieren wir sie!
                    MiningTaxRate existing = existingRates.stream().filter(r -> r.getTypeId().equals(t.getTypeId())).findFirst().orElse(null);
                    if (existing != null && !correctCategory.equals(existing.getCategory())) {
                        existing.setCategory(correctCategory);
                        toSave.add(existing);
                    }
                }
            }
            if (!toSave.isEmpty()) taxRateRepo.saveAll(toSave);
        } catch (Exception e) {
            System.err.println("Konnte Start-Erze nicht initialisieren: " + e.getMessage());
        }
    }

    public record LedgerItemDto(Long typeId, String typeName, String category, long quantity, double volume, double jitaPrice, double taxToPay) {}
    public record MonthlyLedgerDto(String month, double totalTax, double taxPaid, boolean isPaid, List<LedgerItemDto> details) {}

    // NEU: Wrapper für den gesamten Account-Kontostand
    public record UserLedgerResponse(double totalDebt, double totalPaid, double currentBalance, List<MonthlyLedgerDto> months) {}

    // =============================================================
    // MONATLICHE USER-ABRECHNUNG (MIT VORAUSZAHLUNG / WASSERFALL)
    // =============================================================
    @GetMapping("/my-ledger")
    public ResponseEntity<UserLedgerResponse> getMyLedger() {
        Long charId = (Long) Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication()).getPrincipal();
        assert charId != null;

        Character reqChar = characterRepo.findById(charId).orElseThrow();
        Long mainId = reqChar.getMainCharacterId() != null ? reqChar.getMainCharacterId() : reqChar.getId();

        List<Long> allMyCharIds = characterRepo.findByMainCharacterId(mainId).stream().map(Character::getId).toList();

        List<CharacterMining> myMining = characterMiningRepo.findByCharacterIdIn(allMyCharIds);
        List<CharacterActivity> myActivities = activityRepo.findByCharacterIdIn(allMyCharIds);

        Map<Long, MiningTaxRate> taxRates = taxRateRepo.findAll().stream().collect(Collectors.toMap(MiningTaxRate::getTypeId, t -> t));
        Set<Long> allTypeIds = myMining.stream().map(CharacterMining::getTypeId).collect(Collectors.toSet());
        Map<Long, Double> typeVolumes = invTypeRepo.findAllById(allTypeIds).stream().collect(Collectors.toMap(InvType::getTypeId, InvType::getVolume));

        // 1. Alle geleisteten Zahlungen (Lifetime) summieren
        double totalLifetimePaid = 0.0;
        for (CharacterActivity a : myActivities) {
            if ("TAX_PAYMENT".equals(a.getActivityType()) && a.getValue() != null) {
                totalLifetimePaid += a.getValue();
            }
        }

        // 2. Erze nach "YYYY-MM" gruppieren
        Map<String, Map<Long, Long>> monthlyMining = new HashMap<>();
        for (CharacterMining m : myMining) {
            if (m.getDate() == null || m.getDate().length() < 7) continue;
            String month = m.getDate().substring(0, 7);
            monthlyMining.putIfAbsent(month, new HashMap<>());
            monthlyMining.get(month).merge(m.getTypeId(), m.getQuantity(), Long::sum);
        }

        // 3. Vorhandene Snapshots aus der Datenbank laden
        List<MiningTaxInvoice> existingInvoices = invoiceRepo.findByMainCharacterId(mainId);
        Map<String, MiningTaxInvoice> invoiceMap = existingInvoices.stream()
                .collect(Collectors.toMap(MiningTaxInvoice::getMonth, inv -> inv));

        // Wir brauchen den aktuellen Monat, um zu wissen, was noch live berechnet werden muss
        String currentMonthStr = YearMonth.now(ZoneOffset.UTC).toString(); // z.B. "2026-07"

        // 4. Wasserfall-Prinzip: Wir berechnen chronologisch von alt nach neu!
        List<String> sortedMonths = new ArrayList<>(monthlyMining.keySet());
        Collections.sort(sortedMonths); // Alt -> Neu

        List<MonthlyLedgerDto> resultMonths = new ArrayList<>();
        double totalLifetimeTax = 0.0;
        double remainingMoney = totalLifetimePaid;

        for (String month : sortedMonths) {
            double monthTax = 0;
            List<LedgerItemDto> details = new ArrayList<>();

            // =========================================================
            // A) SNAPSHOT VORHANDEN? (Monat ist eingefroren)
            // =========================================================
            if (invoiceMap.containsKey(month)) {
                MiningTaxInvoice invoice = invoiceMap.get(month);
                monthTax = invoice.getTotalTax();
                try {
                    details = objectMapper.readValue(invoice.getDetailsJson(), new TypeReference<>() {
                    });
                } catch (Exception e) {
                    System.err.println("Konnte Details für Snapshot " + month + " nicht laden.");
                }
            }
            // =========================================================
            // B) KEIN SNAPSHOT -> LIVE BERECHNEN
            // =========================================================
            else {
                Map<Long, Long> monthOres = monthlyMining.get(month);
                for (Map.Entry<Long, Long> entry : monthOres.entrySet()) {
                    Long typeId = entry.getKey();
                    long qty = entry.getValue();
                    MiningTaxRate rate = taxRates.get(typeId);

                    if (rate == null) {
                        MiningTaxRate newRate = new MiningTaxRate();
                        newRate.setTypeId(typeId);
                        newRate.setTaxPercentage(0.0);
                        newRate.setCurrentJitaBuy(0.0);
                        invTypeRepo.findById(typeId).ifPresentOrElse(t -> {
                            newRate.setTypeName(t.getTypeName());
                            if (t.getGroupId() == 423L) newRate.setCategory("ICE");
                            else if (t.getGroupId() == 711L) newRate.setCategory("GAS");
                            else if (List.of(1884L, 1920L, 1921L, 1922L, 1923L).contains(t.getGroupId())) newRate.setCategory("MOON");
                            else newRate.setCategory("ORE");
                        }, () -> {
                            newRate.setTypeName("Unknown Ore (" + typeId + ")");
                            newRate.setCategory("ORE");
                        });
                        taxRateRepo.save(newRate);
                        taxRates.put(typeId, newRate);
                        rate = newRate;
                    }

                    double jitaBuy = rate.getCurrentJitaBuy() != null ? rate.getCurrentJitaBuy() : 0.0;
                    double taxPct = rate.getTaxPercentage() != null ? rate.getTaxPercentage() : 0.0;

                    double taxForThisOre = (qty * jitaBuy) * (taxPct / 100.0);
                    double volume = qty * typeVolumes.getOrDefault(typeId, 0.0);

                    monthTax += taxForThisOre;
                    details.add(new LedgerItemDto(typeId, rate.getTypeName(), rate.getCategory(), qty, volume, jitaBuy, taxForThisOre));
                }

                details.sort((a, b) -> Double.compare(b.taxToPay(), a.taxToPay()));

                // =========================================================
                // C) WENN DER MONAT VERGANGEN IST -> EINFRIEREN!
                // =========================================================
                if (!month.equals(currentMonthStr)) {
                    MiningTaxInvoice newInvoice = new MiningTaxInvoice();
                    newInvoice.setMainCharacterId(mainId);
                    newInvoice.setMonth(month);
                    newInvoice.setTotalTax(monthTax);
                    try {
                        newInvoice.setDetailsJson(objectMapper.writeValueAsString(details));
                    } catch (Exception e) {
                        newInvoice.setDetailsJson("[]");
                    }
                    invoiceRepo.save(newInvoice);
                    invoiceMap.put(month, newInvoice); // Ab sofort ist es ein Snapshot
                }
            }

            totalLifetimeTax += monthTax;

            // Rechnungen mit dem vorhandenen Geld "bezahlen" (Wasserfall)
            double paidForThisMonth = 0.0;
            if (remainingMoney >= monthTax) {
                paidForThisMonth = monthTax;
                remainingMoney -= monthTax;
            } else if (remainingMoney > 0) {
                paidForThisMonth = remainingMoney;
                remainingMoney = 0;
            }

            boolean isPaid = paidForThisMonth >= (monthTax * 0.95);
            resultMonths.add(new MonthlyLedgerDto(month, monthTax, paidForThisMonth, isPaid, details));
        }

        resultMonths.sort((a, b) -> b.month().compareTo(a.month()));
        double currentBalance = totalLifetimePaid - totalLifetimeTax;

        return ResponseEntity.ok(new UserLedgerResponse(totalLifetimeTax, totalLifetimePaid, currentBalance, resultMonths));
    }

    // =============================================================
    // ADMIN ENDPUNKTE (Prozente)
    // =============================================================

    @PreAuthorize("hasAnyRole('ROLE_DIRECTOR', 'ROLE_CEO')")
    @GetMapping("/taxes")
    public ResponseEntity<List<MiningTaxRate>> getTaxRates() {
        return ResponseEntity.ok(taxRateRepo.findAll());
    }

    @PreAuthorize("hasAnyRole('ROLE_DIRECTOR', 'ROLE_CEO')")
    @PostMapping("/taxes")
    public ResponseEntity<MiningTaxRate> saveTaxRate(@RequestBody MiningTaxRate rate) {
        return ResponseEntity.ok(taxRateRepo.save(rate));
    }

    @PreAuthorize("hasAnyRole('ROLE_DIRECTOR', 'ROLE_CEO')")
    @DeleteMapping("/taxes/{typeId}")
    public ResponseEntity<Void> deleteTaxRate(@PathVariable Long typeId) {
        taxRateRepo.deleteById(typeId);
        return ResponseEntity.ok().build();
    }

    @PreAuthorize("hasAnyRole('ROLE_DIRECTOR', 'ROLE_CEO')")
    @PostMapping("/taxes/bulk")
    public ResponseEntity<Void> updateBulkTax(@RequestParam String category, @RequestParam Double taxPercentage) {
        List<MiningTaxRate> rates = taxRateRepo.findAll().stream()
                .filter(r -> r.getCategory().equalsIgnoreCase(category))
                .toList();
        for (MiningTaxRate r : rates) {
            r.setTaxPercentage(taxPercentage);
        }
        taxRateRepo.saveAll(rates);
        return ResponseEntity.ok().build();
    }
}