package com.eve.own.auth.backend.domain.mining.service;

import com.eve.own.auth.backend.common.EveImageUrls;
import com.eve.own.auth.backend.domain.character.entity.ActivityType;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.CharacterActivity;
import com.eve.own.auth.backend.domain.character.entity.CharacterMining;
import com.eve.own.auth.backend.domain.character.repository.CharacterActivityRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterMiningRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.eve.entity.InvType;
import com.eve.own.auth.backend.domain.eve.repository.InvTypeRepository;
import com.eve.own.auth.backend.domain.mining.dto.MiningDtos;
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxInvoice;
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxRate;
import com.eve.own.auth.backend.domain.mining.repository.MiningTaxInvoiceRepository;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Berechnet die Mining-Steuerbilanz eines Accounts.
 *
 * <p>Zwei Eigenheiten praegen die Rechnung:</p>
 * <ul>
 *   <li><b>Eingefrorene Monate.</b> Sobald ein Monat vorbei ist, wird seine
 *       Abrechnung als Snapshot gespeichert. Sonst wuerde sich eine laengst
 *       gestellte Rechnung nachtraeglich aendern, sobald sich Marktpreise oder
 *       Steuersaetze bewegen.</li>
 *   <li><b>Wasserfall.</b> Zahlungen sind nicht einzelnen Monaten zugeordnet.
 *       Alles jemals Gezahlte wird deshalb chronologisch auf die aeltesten
 *       offenen Monate verrechnet - eine Vorauszahlung deckt damit automatisch
 *       kommende Monate ab.</li>
 * </ul>
 */
@Slf4j
@Service
public class MiningLedgerService {

    /** Laenge des Monatsschluessels "YYYY-MM" im ESI-Datum "YYYY-MM-DD". */
    private static final int MONTH_KEY_LENGTH = 7;

    /**
     * Ab welchem Deckungsgrad ein Monat als bezahlt gilt.
     *
     * <p>Etwas Spielraum ist noetig: Spieler runden ihre Ueberweisungen, und die
     * Preisbasis wandert zwischen Berechnung und Zahlung leicht.</p>
     */
    private static final double PAID_THRESHOLD = 0.95;

    private static final double PERCENT_DIVISOR = 100.0;

    private final CharacterRepository characterRepo;
    private final CharacterMiningRepository miningRepo;
    private final CharacterActivityRepository activityRepo;
    private final MiningTaxInvoiceRepository invoiceRepo;
    private final MiningTaxRateService taxRateService;
    private final InvTypeRepository invTypeRepo;
    private final ObjectMapper objectMapper;

    public MiningLedgerService(CharacterRepository characterRepo,
                               CharacterMiningRepository miningRepo,
                               CharacterActivityRepository activityRepo,
                               MiningTaxInvoiceRepository invoiceRepo,
                               MiningTaxRateService taxRateService,
                               InvTypeRepository invTypeRepo,
                               ObjectMapper objectMapper) {
        this.characterRepo = characterRepo;
        this.miningRepo = miningRepo;
        this.activityRepo = activityRepo;
        this.invoiceRepo = invoiceRepo;
        this.taxRateService = taxRateService;
        this.invTypeRepo = invTypeRepo;
        this.objectMapper = objectMapper;
    }

    // ==================================================================
    // Bilanz eines Accounts
    // ==================================================================

    @Transactional
    public MiningDtos.UserLedgerResponse ledgerOf(Long characterId) {
        Character character = characterRepo.findById(characterId).orElseThrow(
                () -> new IllegalArgumentException("Charakter " + characterId + " ist unbekannt."));
        Long accountId = character.getAccountId();

        List<Long> accountCharacterIds = characterRepo.findByMainCharacterId(accountId).stream()
                .map(Character::getId)
                .toList();

        double totalPaid = sumTaxPayments(activityRepo.findByCharacterIdIn(accountCharacterIds));
        Map<String, Map<Long, Long>> minedByMonth = groupByMonth(
                miningRepo.findByCharacterIdIn(accountCharacterIds));
        Map<String, MiningTaxInvoice> invoices = invoiceRepo.findByMainCharacterId(accountId).stream()
                .collect(Collectors.toMap(MiningTaxInvoice::getMonth, Function.identity(), (a, b) -> a));

        List<MiningDtos.MonthlyLedgerDto> months =
                settleChronologically(accountId, minedByMonth, invoices, totalPaid);

        double totalTax = months.stream().mapToDouble(MiningDtos.MonthlyLedgerDto::totalTax).sum();

        // Neueste Abrechnung zuerst - so wird sie im Frontand zuerst gelesen.
        List<MiningDtos.MonthlyLedgerDto> newestFirst = months.stream()
                .sorted(Comparator.comparing(MiningDtos.MonthlyLedgerDto::month).reversed())
                .toList();

        return new MiningDtos.UserLedgerResponse(totalTax, totalPaid, totalPaid - totalTax, newestFirst);
    }

    /**
     * Rechnet die Monate von alt nach neu ab und verteilt das gezahlte Geld dabei
     * nach dem Wasserfall-Prinzip.
     */
    private List<MiningDtos.MonthlyLedgerDto> settleChronologically(
            Long accountId,
            Map<String, Map<Long, Long>> minedByMonth,
            Map<String, MiningTaxInvoice> invoices,
            double totalPaid) {

        Set<String> allMonths = new TreeSet<>(minedByMonth.keySet());
        allMonths.addAll(invoices.keySet());

        Map<Long, MiningTaxRate> rates = new HashMap<>(taxRateService.findAllByTypeId());
        Map<Long, Double> volumes = volumesOf(minedByMonth);
        String currentMonth = currentMonth();

        List<MiningDtos.MonthlyLedgerDto> result = new ArrayList<>(allMonths.size());
        double unallocated = totalPaid;

        for (String month : allMonths) {
            MonthlyBill bill = invoices.containsKey(month)
                    ? restoreFrozenBill(invoices.get(month))
                    : calculateBill(accountId, month, minedByMonth.get(month), rates, volumes, currentMonth);

            double covered = Math.min(unallocated, bill.totalTax());
            unallocated -= covered;

            boolean paid = covered >= bill.totalTax() * PAID_THRESHOLD;
            result.add(new MiningDtos.MonthlyLedgerDto(month, bill.totalTax(), covered, paid, bill.details()));
        }
        return result;
    }

    /** Eine Monatsabrechnung, unabhaengig davon ob sie eingefroren oder frisch gerechnet ist. */
    private record MonthlyBill(double totalTax, List<MiningDtos.LedgerItemDto> details) {}

    private MonthlyBill restoreFrozenBill(MiningTaxInvoice invoice) {
        return new MonthlyBill(invoice.getTotalTax(), readDetails(invoice));
    }

    /**
     * Rechnet einen Monat aus den Rohdaten und friert ihn ein, sobald er vorbei ist.
     */
    private MonthlyBill calculateBill(Long accountId, String month, Map<Long, Long> minedQuantities,
                                      Map<Long, MiningTaxRate> rates, Map<Long, Double> volumes,
                                      String currentMonth) {
        if (minedQuantities == null || minedQuantities.isEmpty()) {
            return new MonthlyBill(0.0, List.of());
        }

        List<MiningDtos.LedgerItemDto> details = new ArrayList<>(minedQuantities.size());
        double totalTax = 0.0;

        for (Map.Entry<Long, Long> mined : minedQuantities.entrySet()) {
            Long typeId = mined.getKey();
            long quantity = mined.getValue();

            MiningTaxRate rate = rates.computeIfAbsent(typeId, taxRateService::createMissingRate);
            double jitaPrice = orZero(rate.getCurrentJitaBuy());
            double tax = quantity * jitaPrice * (orZero(rate.getTaxPercentage()) / PERCENT_DIVISOR);

            totalTax += tax;
            details.add(new MiningDtos.LedgerItemDto(typeId, rate.getTypeName(), rate.getCategory(),
                    quantity, quantity * volumes.getOrDefault(typeId, 0.0), jitaPrice, tax));
        }

        details.sort(Comparator.comparingDouble(MiningDtos.LedgerItemDto::taxToPay).reversed());

        if (!month.equals(currentMonth)) {
            freeze(accountId, month, totalTax, details);
        }
        return new MonthlyBill(totalTax, details);
    }

    /** Haelt die Abrechnung eines abgeschlossenen Monats unveraenderlich fest. */
    private void freeze(Long accountId, String month, double totalTax,
                        List<MiningDtos.LedgerItemDto> details) {
        MiningTaxInvoice invoice = new MiningTaxInvoice();
        invoice.setMainCharacterId(accountId);
        invoice.setMonth(month);
        invoice.setTotalTax(totalTax);
        invoice.setDetailsJson(writeDetails(month, details));
        invoiceRepo.save(invoice);
    }

    // ==================================================================
    // Admin-Uebersicht
    // ==================================================================

    /**
     * Die Bilanz aller Accounts, das groesste Minus zuerst.
     *
     * <p>Bewusst ohne den Wasserfall aus der Nutzersicht: hier interessiert nur
     * die Gesamtdifferenz aus Schuld und Zahlung, nicht ihre Verteilung auf
     * einzelne Monate.</p>
     */
    @Transactional(readOnly = true)
    public List<MiningDtos.AdminLedgerSummaryDto> allAccountSummaries() {
        Map<Long, List<Character>> charactersByAccount = characterRepo.findAll().stream()
                .collect(Collectors.groupingBy(Character::getAccountId));

        Map<Long, Double> paidByAccount = paidByAccount(charactersByAccount);
        Map<Long, Double> frozenTaxByAccount = invoiceRepo.findAll().stream()
                .collect(Collectors.groupingBy(MiningTaxInvoice::getMainCharacterId,
                        Collectors.summingDouble(MiningTaxInvoice::getTotalTax)));
        Map<Long, Double> liveTaxByAccount = currentMonthTaxByAccount(charactersByAccount);

        return charactersByAccount.entrySet().stream()
                .map(entry -> toSummary(entry.getKey(), entry.getValue(),
                        paidByAccount.getOrDefault(entry.getKey(), 0.0),
                        frozenTaxByAccount.getOrDefault(entry.getKey(), 0.0)
                                + liveTaxByAccount.getOrDefault(entry.getKey(), 0.0)))
                .sorted(Comparator.comparingDouble(MiningDtos.AdminLedgerSummaryDto::currentBalance))
                .toList();
    }

    private MiningDtos.AdminLedgerSummaryDto toSummary(Long accountId, List<Character> characters,
                                                       double totalPaid, double totalTax) {
        String name = characters.stream()
                .filter(character -> character.getId().equals(accountId))
                .findFirst()
                .orElse(characters.getFirst())
                .getName();

        return new MiningDtos.AdminLedgerSummaryDto(accountId, name, EveImageUrls.portrait(accountId),
                totalTax, totalPaid, totalPaid - totalTax);
    }

    private Map<Long, Double> paidByAccount(Map<Long, List<Character>> charactersByAccount) {
        Map<Long, Long> accountByCharacter = accountByCharacter(charactersByAccount);
        return activityRepo.findAll().stream()
                .filter(activity -> activity.isOfType(ActivityType.TAX_PAYMENT))
                .filter(activity -> activity.getValue() != null)
                .filter(activity -> accountByCharacter.containsKey(activity.getCharacterId()))
                .collect(Collectors.groupingBy(
                        activity -> accountByCharacter.get(activity.getCharacterId()),
                        Collectors.summingDouble(CharacterActivity::getValue)));
    }

    /** Die noch nicht eingefrorene Steuer des laufenden Monats. */
    private Map<Long, Double> currentMonthTaxByAccount(Map<Long, List<Character>> charactersByAccount) {
        Map<Long, Long> accountByCharacter = accountByCharacter(charactersByAccount);
        Map<Long, MiningTaxRate> rates = taxRateService.findAllByTypeId();
        String currentMonth = currentMonth();

        return miningRepo.findAll().stream()
                .filter(entry -> entry.getDate() != null && entry.getDate().startsWith(currentMonth))
                .filter(entry -> accountByCharacter.containsKey(entry.getCharacterId()))
                .collect(Collectors.groupingBy(
                        entry -> accountByCharacter.get(entry.getCharacterId()),
                        Collectors.summingDouble(entry -> taxOf(entry, rates.get(entry.getTypeId())))));
    }

    private static double taxOf(CharacterMining entry, MiningTaxRate rate) {
        if (rate == null) {
            return 0.0;
        }
        return entry.getQuantity() * orZero(rate.getCurrentJitaBuy())
                * (orZero(rate.getTaxPercentage()) / PERCENT_DIVISOR);
    }

    private static Map<Long, Long> accountByCharacter(Map<Long, List<Character>> charactersByAccount) {
        Map<Long, Long> accountByCharacter = new HashMap<>();
        charactersByAccount.forEach((accountId, characters) ->
                characters.forEach(character -> accountByCharacter.put(character.getId(), accountId)));
        return accountByCharacter;
    }

    // ==================================================================
    // Helfer
    // ==================================================================

    private static double sumTaxPayments(List<CharacterActivity> activities) {
        return activities.stream()
                .filter(activity -> activity.isOfType(ActivityType.TAX_PAYMENT))
                .filter(activity -> activity.getValue() != null)
                .mapToDouble(CharacterActivity::getValue)
                .sum();
    }

    /** Monatsschluessel "YYYY-MM" auf abgebaute Menge je Typ. */
    private static Map<String, Map<Long, Long>> groupByMonth(List<CharacterMining> entries) {
        Map<String, Map<Long, Long>> byMonth = new HashMap<>();
        for (CharacterMining entry : entries) {
            String month = monthKeyOf(entry.getDate());
            if (month == null) {
                continue;
            }
            byMonth.computeIfAbsent(month, key -> new HashMap<>())
                    .merge(entry.getTypeId(), entry.getQuantity(), Long::sum);
        }
        return byMonth;
    }

    private static String monthKeyOf(String date) {
        if (date == null || date.length() < MONTH_KEY_LENGTH) {
            return null;
        }
        return date.substring(0, MONTH_KEY_LENGTH);
    }

    /** Stueckvolumen der abgebauten Typen aus der SDE. */
    private Map<Long, Double> volumesOf(Map<String, Map<Long, Long>> minedByMonth) {
        Set<Long> typeIds = new HashSet<>();
        minedByMonth.values().forEach(perType -> typeIds.addAll(perType.keySet()));
        if (typeIds.isEmpty()) {
            return Map.of();
        }
        return invTypeRepo.findAllById(typeIds).stream()
                .filter(type -> type.getVolume() != null)
                .collect(Collectors.toMap(InvType::getTypeId, InvType::getVolume));
    }

    private static String currentMonth() {
        // ESI datiert nach UTC - jede andere Zeitzone verschoebe den Monatswechsel.
        return YearMonth.now(ZoneOffset.UTC).toString();
    }

    private static double orZero(Double value) {
        return value != null ? value : 0.0;
    }

    private List<MiningDtos.LedgerItemDto> readDetails(MiningTaxInvoice invoice) {
        try {
            return objectMapper.readValue(invoice.getDetailsJson(), new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Snapshot-Details fuer {} nicht lesbar, Monat wird ohne Aufschluesselung gezeigt: {}",
                    invoice.getMonth(), e.getMessage());
            return List.of();
        }
    }

    private String writeDetails(String month, List<MiningDtos.LedgerItemDto> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            // Die Summe bleibt korrekt, nur die Aufschluesselung geht verloren.
            log.warn("Snapshot-Details fuer {} nicht schreibbar: {}", month, e.getMessage());
            return "[]";
        }
    }
}
