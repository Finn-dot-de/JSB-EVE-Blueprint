package com.eve.buy.bot.backend.domain.buybot.service;

import com.eve.buy.bot.backend.audit.AuditCategory;
import com.eve.buy.bot.backend.audit.AuditService;
import com.eve.buy.bot.backend.audit.AuditSeverity;
import com.eve.buy.bot.backend.domain.auth.service.AuthService;
import com.eve.buy.bot.backend.domain.buybot.dto.ParsedItemDto;
import com.eve.buy.bot.backend.domain.buybot.entity.BuybackConfig;
import com.eve.buy.bot.backend.domain.buybot.entity.BuybackLocation;
import com.eve.buy.bot.backend.domain.buybot.entity.ContractCheck;
import com.eve.buy.bot.backend.domain.buybot.repository.BuybackConfigRepository;
import com.eve.buy.bot.backend.domain.buybot.repository.BuybackLocationRepository;
import com.eve.buy.bot.backend.domain.buybot.repository.ContractCheckRepository;
import com.eve.buy.bot.backend.domain.character.entity.Character;
import com.eve.buy.bot.backend.domain.character.repository.CharacterRepository;
import com.eve.buy.bot.backend.esi.EsiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Vertragsprüfung laut Protokoll: offene Verträge des Ziel-Charakters per ESI holen,
 * gegen dieselbe Preis-Matrix rechnen, die auch die Website nutzt, und das Ergebnis
 * per Discord-Webhook oder EVE-Mail melden.
 *
 * Geprüft wird auf: falscher Abgabeort, Preisabweichung über Toleranz,
 * gesperrte/nicht gelistete Items, angeforderte Items und Vertragstyp/Laufzeit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractCheckService {

    // --- Finding-Codes ---
    public static final String F_WRONG_TYPE = "WRONG_TYPE";
    public static final String F_WRONG_LOCATION = "WRONG_LOCATION";
    public static final String F_PRICE_TOO_HIGH = "PRICE_TOO_HIGH";
    public static final String F_PRICE_TOO_LOW = "PRICE_TOO_LOW";
    public static final String F_NO_VALUE = "NO_VALUE";
    public static final String F_BLOCKED_ITEMS = "BLOCKED_ITEMS";
    public static final String F_NOT_LISTED_ITEMS = "NOT_LISTED_ITEMS";
    public static final String F_UNKNOWN_ITEMS = "UNKNOWN_ITEMS";
    public static final String F_REQUESTED_ITEMS = "REQUESTED_ITEMS";
    public static final String F_EXPIRES_SOON = "EXPIRES_SOON";
    public static final String F_REWARD_SET = "REWARD_SET";

    private static final String VERDICT_OK = "OK";
    private static final String VERDICT_WARN = "WARN";
    private static final String VERDICT_REJECT = "REJECT";

    private static final DateTimeFormatter EVE_TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneOffset.UTC);
    private static final int MAX_ITEM_LINES = 25;

    private final BuybackConfigRepository configRepo;
    private final BuybackLocationRepository locationRepo;
    private final ContractCheckRepository checkRepo;
    private final BuybackCalculationService calculationService;
    private final CharacterRepository characterRepo;
    private final AuthService authService;
    private final EsiService esiService;
    private final NotificationService notificationService;
    private final AuditService auditService;

    public record RunResult(boolean success, String message, int scanned, int checked, int notified) {}

    /** Zustand der automatischen Prüfung - damit im Admin-Panel sichtbar ist, dass sie überhaupt tickt. */
    public record CheckStatus(boolean enabled,
                              int intervalMinutes,
                              String notifyTarget,
                              Instant lastRunAt,
                              Instant nextRunAt,
                              String trigger,
                              boolean lastRunSuccess,
                              String lastRunMessage,
                              int scanned,
                              int checked,
                              int notified,
                              long pendingNotifications) {}

    // Letzter Lauf - nur im Speicher, nach einem Neustart läuft die Prüfung ohnehin binnen einer Minute erneut.
    private volatile Instant lastRunAt;
    private volatile String lastRunTrigger;
    private volatile RunResult lastRunResult;

    /**
     * Einstiegspunkt des Schedulers: prüft Schalter und Intervall selbst,
     * damit Intervall-Änderungen ohne Neustart greifen.
     */
    public RunResult runIfDue() {
        BuybackConfig config = configRepo.findById(1L).orElse(null);
        if (config == null || !Boolean.TRUE.equals(config.getContractCheckEnabled())) {
            return null;
        }
        Duration interval = Duration.ofMinutes(config.checkIntervalOrDefault());
        if (lastRunAt != null && Duration.between(lastRunAt, Instant.now()).compareTo(interval) < 0) {
            return null;
        }
        return run(false, "automatisch");
    }

    /**
     * Liefert den Zustand der automatischen Pruefung fuer das Admin-Panel.
     *
     * @return letzter und naechster Lauf, Ergebnis und offene Meldungen
     */
    public CheckStatus status() {
        BuybackConfig config = configRepo.findById(1L).orElse(new BuybackConfig());
        int interval = config.checkIntervalOrDefault();
        RunResult last = lastRunResult;
        return new CheckStatus(
                Boolean.TRUE.equals(config.getContractCheckEnabled()),
                interval,
                config.notifyTargetOrNone(),
                lastRunAt,
                lastRunAt == null ? null : lastRunAt.plus(Duration.ofMinutes(interval)),
                lastRunTrigger,
                last == null || last.success(),
                last == null ? null : last.message(),
                last == null ? 0 : last.scanned(),
                last == null ? 0 : last.checked(),
                last == null ? 0 : last.notified(),
                checkRepo.countByNotifiedFalse()
        );
    }

    /**
     * @param forced true = manuell aus dem Admin-Panel ausgelöst (ignoriert den Enabled-Schalter)
     */
    public RunResult run(boolean forced) {
        return run(forced, "manuell");
    }

    private RunResult run(boolean forced, String trigger) {
        RunResult result = execute(forced);
        lastRunAt = Instant.now();
        lastRunTrigger = trigger;
        lastRunResult = result;

        // Nur auffaellige Laeufe protokollieren, sonst besteht das Protokoll bei kurzem
        // Intervall nur noch aus "nichts Neues".
        if (!result.success() || result.checked() > 0 || result.notified() > 0) {
            auditService.record(AuditCategory.CONTRACT_CHECK,
                    result.success() ? AuditSeverity.INFO : AuditSeverity.WARN,
                    "Vertragspruefung (" + trigger + "): " + result.message(), null);
        }
        return result;
    }

    private RunResult execute(boolean forced) {
        BuybackConfig config = configRepo.findById(1L).orElse(null);
        if (config == null) {
            return new RunResult(false, "Es existiert noch keine Buybot-Konfiguration.", 0, 0, 0);
        }
        if (!forced && !Boolean.TRUE.equals(config.getContractCheckEnabled())) {
            return new RunResult(false, "Vertragsprüfung ist deaktiviert.", 0, 0, 0);
        }

        Long charId = config.getContractCheckCharacterId();
        if (charId == null) {
            return new RunResult(false, "Kein Prüf-Charakter konfiguriert.", 0, 0, 0);
        }

        Optional<Character> charOpt = characterRepo.findById(charId);
        if (charOpt.isEmpty()) {
            return new RunResult(false, "Charakter " + charId + " ist nicht mit dem Auth verknüpft.", 0, 0, 0);
        }

        List<BuybackLocation> locations = locationRepo.findAll();
        if (locations.isEmpty()) {
            return new RunResult(false, "Es ist kein Abgabeort konfiguriert - ohne Ort kann nicht gerechnet werden.", 0, 0, 0);
        }

        String token;
        List<EsiService.EsiContractResponse> contracts;
        try {
            token = authService.getValidAccessToken(charOpt.get());
            contracts = esiService.getAllCharacterContracts(charId, token);
        } catch (Exception e) {
            log.error("Vertragsabfrage für {} fehlgeschlagen: {}", charId, e.getMessage());
            return new RunResult(false, "ESI-Abfrage fehlgeschlagen: " + e.getMessage(), 0, 0, 0);
        }

        List<EsiService.EsiContractResponse> open = contracts.stream()
                .filter(c -> c.contract_id() != null)
                .filter(c -> "outstanding".equalsIgnoreCase(String.valueOf(c.status())))
                .filter(c -> !Objects.equals(charId, c.issuer_id()))
                .sorted(Comparator.comparing(c -> c.date_issued() == null ? Instant.EPOCH : c.date_issued()))
                .toList();

        int checked = 0;
        int notified = 0;
        int retried = 0;
        String lastError = null;

        for (EsiService.EsiContractResponse contract : open) {
            try {
                ContractCheck existing = checkRepo.findById(contract.contract_id()).orElse(null);

                if (existing != null) {
                    // Schon geprüft. Nicht neu bewerten, aber eine gescheiterte Meldung nachholen -
                    // sonst ginge ein Vertrag für immer verloren, nur weil ESI kurz gezickt hat.
                    if (Boolean.TRUE.equals(existing.getNotified()) || !shouldNotify(config, existing)) {
                        continue;
                    }
                    NotificationService.NotifyResult resend = notify(config, existing, charId);
                    applyNotifyResult(existing, resend);
                    checkRepo.save(existing);
                    if (resend.sent()) {
                        notified++;
                        retried++;
                    } else {
                        lastError = resend.error();
                    }
                    continue;
                }

                ContractCheck result = analyse(contract, config, locations, charId, token);
                checkRepo.save(result);
                checked++;

                if (shouldNotify(config, result)) {
                    NotificationService.NotifyResult sendResult = notify(config, result, charId);
                    applyNotifyResult(result, sendResult);
                    checkRepo.save(result);
                    if (sendResult.sent()) {
                        notified++;
                    } else {
                        lastError = sendResult.error();
                    }
                }
            } catch (Exception e) {
                log.error("Vertrag {} konnte nicht geprüft werden: {}", contract.contract_id(), e.getMessage());
                lastError = "Vertrag " + contract.contract_id() + ": " + e.getMessage();
            }
        }

        StringBuilder msg = new StringBuilder("%d offene Verträge gefunden, %d neu geprüft, %d gemeldet"
                .formatted(open.size(), checked, notified));
        if (retried > 0) {
            msg.append(" (davon ").append(retried).append(" nachgeholt)");
        }
        msg.append('.');
        if (lastError != null) {
            msg.append(" Meldung fehlgeschlagen: ").append(lastError);
        }
        return new RunResult(lastError == null, msg.toString(), open.size(), checked, notified);
    }

    /** Wird für diesen Befund überhaupt gemeldet? */
    private boolean shouldNotify(BuybackConfig config, ContractCheck check) {
        if ("NONE".equals(config.notifyTargetOrNone())) {
            return false;
        }
        return !VERDICT_OK.equals(check.getVerdict()) || !Boolean.FALSE.equals(config.getNotifyOnOk());
    }

    private void applyNotifyResult(ContractCheck check, NotificationService.NotifyResult result) {
        int attempts = check.getNotifyAttempts() == null ? 0 : check.getNotifyAttempts();
        check.setNotifyAttempts(attempts + 1);
        check.setNotified(result.sent());
        check.setNotifyError(result.sent() ? null : result.error());
    }

    /**
     * Testnachricht über den konfigurierten Meldeweg - damit sich der Weg prüfen lässt,
     * ohne auf einen echten Vertrag zu warten.
     */
    public RunResult sendTestNotification() {
        BuybackConfig config = configRepo.findById(1L).orElse(null);
        if (config == null) {
            return new RunResult(false, "Es existiert noch keine Buybot-Konfiguration.", 0, 0, 0);
        }
        String target = config.notifyTargetOrNone();
        if ("NONE".equals(target)) {
            return new RunResult(false, "Meldeweg steht auf 'Keine Meldung' - bitte erst Discord oder EVE-Mail wählen.", 0, 0, 0);
        }

        String title = "Testnachricht vom Buybot";
        String body = """
                Das ist eine Testnachricht aus dem Admin-Panel.
                Kommt sie an, funktioniert der Meldeweg für die Vertragsprüfung.
                Toleranz: %s %%
                Prüfintervall: %d Minuten""".formatted(num(config.tolerancePercentOrDefault()), config.checkIntervalOrDefault());

        List<String> errors = new ArrayList<>();
        boolean sent = false;

        if ("DISCORD".equals(target) || "BOTH".equals(target)) {
            var result = notificationService.sendDiscord(config.getDiscordWebhookUrl(), title, body, NotificationService.COLOR_OK);
            sent |= result.sent();
            if (!result.sent()) errors.add(result.error());
        }
        if ("EVEMAIL".equals(target) || "BOTH".equals(target)) {
            var result = notificationService.sendEveMail(config.getContractCheckCharacterId(),
                    config.getNotifyMailRecipientId(), "[Buybot] " + title, body);
            sent |= result.sent();
            if (!result.sent()) errors.add(result.error());
        }

        if (errors.isEmpty()) {
            return new RunResult(true, "Testnachricht verschickt.", 0, 0, 1);
        }
        String msg = (sent ? "Teilweise verschickt. " : "Testnachricht fehlgeschlagen: ") + String.join(" | ", errors);
        return new RunResult(sent, msg, 0, 0, sent ? 1 : 0);
    }

    // =================================================================
    // ANALYSE EINES EINZELNEN VERTRAGS
    // =================================================================
    private ContractCheck analyse(EsiService.EsiContractResponse contract,
                                  BuybackConfig config,
                                  List<BuybackLocation> locations,
                                  Long charId,
                                  String token) {

        Set<String> codes = new LinkedHashSet<>();
        List<String> findings = new ArrayList<>();

        ContractCheck check = new ContractCheck();
        check.setContractId(contract.contract_id());
        check.setIssuerId(contract.issuer_id());
        check.setIssuerName(resolveCharacterName(contract.issuer_id()));
        check.setTitle(contract.title());
        check.setContractType(contract.type());
        check.setIssuedAt(contract.date_issued());
        check.setExpiresAt(contract.date_expired());
        check.setCheckedAt(Instant.now());
        check.setStartLocationId(contract.start_location_id());
        check.setContractPrice(contract.price() != null ? contract.price() : 0.0);

        // --- 1. Vertragstyp ---
        if (!"item_exchange".equalsIgnoreCase(String.valueOf(contract.type()))) {
            codes.add(F_WRONG_TYPE);
            findings.add("Falscher Vertragstyp: '" + contract.type() + "'. Erwartet wird ein Item-Exchange.");
        }
        if (contract.reward() != null && contract.reward() > 0) {
            codes.add(F_REWARD_SET);
            findings.add("Der Vertrag enthält eine Belohnung von " + isk(contract.reward())
                    + " - bei einem Ankauf sollte nur ein Preis gefordert werden.");
        }

        // --- 2. Abgabeort ---
        Optional<BuybackLocation> matched = locations.stream()
                .filter(l -> l.getStationId() != null && Objects.equals(l.getStationId(), contract.start_location_id()))
                .findFirst();

        BuybackLocation pricingLocation;
        if (matched.isPresent()) {
            pricingLocation = matched.get();
            check.setLocationName(pricingLocation.getName());
        } else {
            pricingLocation = locations.getFirst();
            codes.add(F_WRONG_LOCATION);
            String resolved = resolveLocationName(contract.start_location_id(), token);
            check.setLocationName(resolved);
            findings.add("Falscher Standort: " + resolved
                    + " ist kein konfigurierter Abgabeort. Preis wurde nur indikativ mit '"
                    + pricingLocation.getName() + "' gerechnet.");
        }

        // --- 3. Items holen und aufsummieren ---
        List<EsiService.EsiContractItemResponse> items = esiService.getContractItems(charId, contract.contract_id(), token);

        Map<Long, Long> offered = new HashMap<>();
        long requestedCount = 0;
        for (EsiService.EsiContractItemResponse item : items) {
            if (item.type_id() == null) continue;
            long qty = item.quantity() != null ? item.quantity() : 0L;
            if (Boolean.FALSE.equals(item.is_included())) {
                requestedCount += Math.max(qty, 1);
            } else {
                offered.merge(item.type_id(), qty, Long::sum);
            }
        }

        if (requestedCount > 0) {
            codes.add(F_REQUESTED_ITEMS);
            findings.add("Der Vertrag fordert " + requestedCount + " Item(s) von uns zurück - das ist kein reiner Ankauf.");
        }

        // --- 4. Preis über dieselbe Matrix rechnen wie die Website ---
        List<ParsedItemDto> priced = calculationService.calculateForTypeIds(offered, pricingLocation.getId());

        double expected = 0.0;
        double totalVolume = 0.0;
        List<String> blocked = new ArrayList<>();
        List<String> notListed = new ArrayList<>();
        List<String> unknown = new ArrayList<>();

        priced.sort(Comparator.comparingDouble((ParsedItemDto i) -> i.getTotalPrice() == null ? 0.0 : i.getTotalPrice()).reversed());

        for (ParsedItemDto item : priced) {
            expected += item.getTotalPrice() != null ? item.getTotalPrice() : 0.0;
            totalVolume += (item.getVolumeEach() != null ? item.getVolumeEach() : 0.0) * item.getQuantity();

            switch (String.valueOf(item.getStatusCode())) {
                case BuybackCalculationService.STATUS_BLOCKED -> blocked.add(item.getRawName());
                case BuybackCalculationService.STATUS_NOT_LISTED -> notListed.add(item.getRawName());
                case BuybackCalculationService.STATUS_UNKNOWN -> unknown.add(item.getRawName());
                default -> { /* OK */ }
            }
        }

        if (!blocked.isEmpty()) {
            codes.add(F_BLOCKED_ITEMS);
            findings.add("Gesperrte Items im Vertrag: " + join(blocked));
        }
        if (!notListed.isEmpty()) {
            codes.add(F_NOT_LISTED_ITEMS);
            findings.add("Nicht gelistete Items (werden mit 0 ISK bewertet): " + join(notListed));
        }
        if (!unknown.isEmpty()) {
            codes.add(F_UNKNOWN_ITEMS);
            findings.add("Unbekannte Type-IDs (SDE veraltet?): " + join(unknown));
        }

        check.setExpectedPrice(expected);
        check.setTotalVolume(totalVolume);

        // --- 5. Preisabgleich ---
        double tolerance = config.tolerancePercentOrDefault();
        double contractPrice = check.getContractPrice();

        if (expected <= 0.0) {
            codes.add(F_NO_VALUE);
            findings.add("Der Vertrag hat nach der Matrix keinen Ankaufswert (0 ISK), gefordert werden aber "
                    + isk(contractPrice) + ".");
        } else {
            double deviation = (contractPrice - expected) / expected * 100.0;
            check.setDeviationPercent(deviation);

            if (deviation > tolerance) {
                codes.add(F_PRICE_TOO_HIGH);
                findings.add("Preis liegt %s %% über dem berechneten Ankaufspreis (Toleranz %s %%)."
                        .formatted(num(deviation), num(tolerance)));
            } else if (deviation < -tolerance) {
                codes.add(F_PRICE_TOO_LOW);
                findings.add("Preis liegt %s %% unter dem berechneten Ankaufspreis - zu unserem Vorteil, aber prüfen."
                        .formatted(num(Math.abs(deviation))));
            }
        }

        // --- 6. Laufzeit ---
        if (contract.date_expired() != null) {
            long hoursLeft = Duration.between(Instant.now(), contract.date_expired()).toHours();
            if (hoursLeft <= 24) {
                codes.add(F_EXPIRES_SOON);
                findings.add("Vertrag läuft in " + Math.max(hoursLeft, 0) + " h ab.");
            }
        }

        // --- 7. Urteil ---
        check.setVerdict(verdictFor(codes));
        check.setFindingCodes(String.join(",", codes));
        check.setFindings(findings.isEmpty() ? "Keine Auffälligkeiten." : String.join("\n", findings));
        check.setItemSummary(itemSummary(priced));
        check.setNotified(false);
        return check;
    }

    private String verdictFor(Set<String> codes) {
        boolean hardFail = codes.contains(F_WRONG_TYPE)
                || codes.contains(F_WRONG_LOCATION)
                || codes.contains(F_PRICE_TOO_HIGH)
                || codes.contains(F_BLOCKED_ITEMS)
                || codes.contains(F_REQUESTED_ITEMS)
                || codes.contains(F_NO_VALUE);
        if (hardFail) return VERDICT_REJECT;
        return codes.isEmpty() ? VERDICT_OK : VERDICT_WARN;
    }

    // =================================================================
    // BERICHT & VERSAND
    // =================================================================
    private NotificationService.NotifyResult notify(BuybackConfig config, ContractCheck check, Long charId) {
        String target = config.notifyTargetOrNone();
        if ("NONE".equals(target)) {
            return NotificationService.NotifyResult.fail("Meldeweg steht auf 'Keine Meldung'.");
        }

        String title = "%s - Vertrag #%d von %s".formatted(
                switch (check.getVerdict()) {
                    case VERDICT_OK -> "OK";
                    case VERDICT_WARN -> "PRÜFEN";
                    default -> "FEHLER";
                },
                check.getContractId(),
                check.getIssuerName());

        String body = buildReport(check, config);
        int color = switch (check.getVerdict()) {
            case VERDICT_OK -> NotificationService.COLOR_OK;
            case VERDICT_WARN -> NotificationService.COLOR_WARN;
            default -> NotificationService.COLOR_REJECT;
        };

        boolean sent = false;
        List<String> errors = new ArrayList<>();

        if ("DISCORD".equals(target) || "BOTH".equals(target)) {
            var result = notificationService.sendDiscord(config.getDiscordWebhookUrl(), title, body, color);
            sent |= result.sent();
            if (!result.sent()) errors.add(result.error());
        }
        if ("EVEMAIL".equals(target) || "BOTH".equals(target)) {
            var result = notificationService.sendEveMail(charId, config.getNotifyMailRecipientId(),
                    "[Buybot] " + title, body);
            sent |= result.sent();
            if (!result.sent()) errors.add(result.error());
        }

        if (errors.isEmpty()) {
            return NotificationService.NotifyResult.ok();
        }
        // Bei "Beides" gilt die Meldung als raus, sobald ein Weg funktioniert hat -
        // der Fehler des anderen Wegs bleibt aber im Log.
        if (sent) {
            log.warn("Vertrag {}: ein Meldeweg ist fehlgeschlagen: {}", check.getContractId(), String.join(" | ", errors));
            return NotificationService.NotifyResult.ok();
        }
        String reason = String.join(" | ", errors);
        auditService.record(AuditCategory.NOTIFICATION, AuditSeverity.ERROR,
                "Meldung zu Vertrag " + check.getContractId() + " fehlgeschlagen", reason);
        return NotificationService.NotifyResult.fail(reason);
    }

    /**
     * Formuliert den Pruefbericht aus, wie er gemeldet und angezeigt wird.
     *
     * @param check der geprueft Vertrag
     * @param config die geltende Konfiguration
     * @return der mehrzeilige Bericht
     */
    public String buildReport(ContractCheck check, BuybackConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("Von: ").append(check.getIssuerName())
                .append(check.getIssuedAt() != null ? " | eingestellt " + EVE_TIME.format(check.getIssuedAt()) + " EVE" : "")
                .append('\n');
        sb.append("Ort: ").append(check.getLocationName() != null ? check.getLocationName() : "unbekannt").append('\n');
        if (check.getExpiresAt() != null) {
            sb.append("Läuft ab: ").append(EVE_TIME.format(check.getExpiresAt())).append(" EVE\n");
        }
        sb.append("Gefordert: ").append(isk(check.getContractPrice())).append('\n');
        sb.append("Berechnet: ").append(isk(check.getExpectedPrice()));
        if (check.getDeviationPercent() != null) {
            sb.append(" (Abweichung ")
                    .append(check.getDeviationPercent() >= 0 ? "+" : "-")
                    .append(num(Math.abs(check.getDeviationPercent())))
                    .append(" %, Toleranz ").append(num(config.tolerancePercentOrDefault())).append(" %)");
        }
        sb.append('\n');
        sb.append("Volumen: ").append(num(check.getTotalVolume() != null ? check.getTotalVolume() : 0.0)).append(" m3\n");

        if (check.getItemSummary() != null && !check.getItemSummary().isBlank()) {
            sb.append("\nItems:\n").append(check.getItemSummary()).append('\n');
        }
        sb.append("\nBefund (").append(check.getVerdict()).append("):\n").append(check.getFindings());
        return sb.toString();
    }

    private String itemSummary(List<ParsedItemDto> priced) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (ParsedItemDto item : priced) {
            if (shown >= MAX_ITEM_LINES) {
                sb.append("  ... und ").append(priced.size() - shown).append(" weitere Positionen\n");
                break;
            }
            sb.append("  ")
                    .append(String.format(Locale.GERMANY, "%,d", item.getQuantity()))
                    .append(" x ").append(item.getRawName())
                    .append(" [").append(item.getStatusCode()).append("] ")
                    .append(isk(item.getTotalPrice()))
                    .append('\n');
            shown++;
        }
        return sb.toString();
    }

    private String resolveCharacterName(Long characterId) {
        if (characterId == null) return "unbekannt";
        return characterRepo.findById(characterId)
                .map(Character::getName)
                .orElseGet(() -> {
                    try {
                        var esiChar = esiService.getCharacter(characterId, null).data();
                        return esiChar != null ? esiChar.name() : "Charakter " + characterId;
                    } catch (Exception e) {
                        return "Charakter " + characterId;
                    }
                });
    }

    private String resolveLocationName(Long locationId, String token) {
        if (locationId == null) return "unbekannt";
        try {
            if (locationId < 100_000_000L) { // NPC-Stationen liegen unter dieser Grenze
                var station = esiService.getStation(locationId);
                if (station != null && station.name() != null) return station.name();
            } else {
                var structure = esiService.getStructure(locationId, token);
                if (structure != null && structure.name() != null) return structure.name();
            }
        } catch (Exception e) {
            log.debug("Konnte Ort {} nicht auflösen: {}", locationId, e.getMessage());
        }
        return "ID " + locationId;
    }

    /**
     * Liefert die zuletzt geprueften Vertraege.
     *
     * @param limit gewuenschte Anzahl
     * @return die Pruefberichte, neueste zuerst
     */
    public List<ContractCheck> recentChecks(int limit) {
        return checkRepo.findAllByOrderByIssuedAtDesc(Limit.of(limit));
    }

    /** Vertrag aus dem Gedächtnis löschen, damit er erneut geprüft und gemeldet wird. */
    public void forget(Long contractId) {
        checkRepo.deleteById(contractId);
    }

    private String join(List<String> names) {
        if (names.size() <= 8) return String.join(", ", names);
        return String.join(", ", names.subList(0, 8)) + " (+" + (names.size() - 8) + " weitere)";
    }

    private String isk(Double value) {
        return String.format(Locale.GERMANY, "%,.2f ISK", value != null ? value : 0.0);
    }

    private String num(double value) {
        return String.format(Locale.GERMANY, "%,.2f", value);
    }
}
