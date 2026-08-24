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
import com.eve.own.auth.backend.domain.mining.OreCategory;
import com.eve.own.auth.backend.domain.mining.dto.MiningDtos;
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxInvoice;
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxRate;
import com.eve.own.auth.backend.domain.mining.repository.MiningTaxInvoiceRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Berechnet die Mining-Steuerbilanz eines Accounts.
 *
 * <p>Zwei Eigenheiten praegen die Rechnung:</p>
 * <ul>
 *   <li><b>Eingefrorene Monate.</b> Ist ein Monat sicher abgeschlossen, wird
 *       seine Abrechnung als Snapshot gespeichert. Sonst wuerde sich eine
 *       laengst gestellte Rechnung nachtraeglich aendern, sobald sich
 *       Marktpreise oder Steuersaetze bewegen.</li>
 *   <li><b>Wasserfall.</b> Zahlungen sind nicht einzelnen Monaten zugeordnet.
 *       Alles jemals Gezahlte wird deshalb chronologisch auf die aeltesten
 *       offenen Monate verrechnet - eine Vorauszahlung deckt damit automatisch
 *       kommende Monate ab. Die nachgetragenen Gutschriften laufen durch denselben
 *       Wasserfall.</li>
 * </ul>
 *
 * <h2>Lesen schreibt nicht</h2>
 * <p>Das Einfrieren geschah einmal als Nebenwirkung von {@link #ledgerOf} -
 * also beim blossen Abruf einer Seite. Das hatte zwei Folgen, und beide sind
 * eingetreten. Erstens legten zwei gleichzeitige Abrufe desselben Accounts
 * dieselbe Rechnung zweimal an und liefen in die Bedingung
 * {@code UNIQUE(main_character_id, month)}; der zweite Leser bekam einen
 * Serverfehler auf einen reinen Lesevorgang. Es brauchte dafuer nicht einmal
 * einen Doppelklick - ein Director, der die Akte eines Mitglieds oeffnet,
 * waehrend das Mitglied seine eigene Seite laedt, genuegt. Zweitens fror der
 * erste Seitenaufruf nach einem Monatswechsel den Vormonat sofort ein, obwohl
 * der ESI-Mining-Ledger rund 30 Tage zurueckreicht und ein spaeterer Abgleich
 * noch Zeilen dieses Monats nachliefert. Im Bestand fehlten einer Rechnung
 * dadurch 482 Einheiten Erz.</p>
 *
 * <p>Deshalb: alle drei Lesewege sind {@code readOnly}, und das Einfrieren
 * geschieht in {@link #freezeDueMonths()} aus einem geplanten Lauf heraus. Das
 * {@code readOnly} ist dabei kein Schmuck - es macht aus einem versehentlich
 * wieder eingebauten Schreibzugriff einen lauten Fehler statt einer stillen
 * Rechnung.</p>
 *
 * <h2>Alle Betraege sind {@link BigDecimal}</h2>
 * <p>Schuld, Zahlung, Gutschrift und Saldo. Der Preis geht mit der Menge
 * multipliziert in jede Zeile ein, und {@code 10.0/100} ist in einem
 * {@code double} nicht exakt - dieser Fehler steckte damit in jeder Zeile jeder
 * Rechnung und wuchs sich in der Summe aus. Gerundet wird an genau einer Stelle:
 * je Erzposten auf die zweite Nachkommastelle. Die Monatssumme ist danach die
 * Summe eben dieser gerundeten Posten, damit die Aufschluesselung nachrechenbar
 * bleibt.</p>
 *
 * <p><b>Falle:</b> {@link BigDecimal#equals} ist skalenempfindlich -
 * {@code 2.5} und {@code 2.50} sind nicht gleich. Verglichen wird deshalb
 * ausschliesslich mit {@link BigDecimal#compareTo}.</p>
 *
 * <h2>Eine Gutschrift ist ein Nachtrag, keine Zuwendung</h2>
 * <p>Hier stand die umgekehrte Regel, und sie ist ersetzt statt geloescht, damit
 * niemand denselben Weg noch einmal geht. Frueher liefen die Gutschriften aus
 * {@link MiningTaxCreditService} ausdruecklich <b>nicht</b> durch den Wasserfall,
 * mit der Begruendung, eine heute vergebene Gutschrift wuerde sonst rueckwirkend
 * den Januar als "bezahlt" ausweisen, obwohl fuer den Januar niemand etwas
 * ueberwiesen hat. Diese Begruendung setzte voraus, dass eine Gutschrift eine
 * <em>Zuwendung</em> ist - geschenktes Geld ohne Zahlungsvorgang dahinter.</p>
 *
 * <p>Das ist sie in diesem Haus nicht. Sie wird vergeben, um eine
 * <em>Korrektur</em> zu buchen: ein Mitglied hatte bezahlt, und die Erkennung hat
 * es nicht mitbekommen. Damit dreht sich die Folgerung um. Der Januar
 * <em>war</em> bezahlt, die Erkennung hat versagt, und die Gutschrift traegt
 * genau das nach - ein so gedeckter Monat MUSS als bezahlt gelten. Zahlungen und
 * Gutschriften decken deshalb gemeinsam, aelteste Schuld zuerst, und
 * {@code isPaid} sieht beide.</p>
 *
 * <p>Was von der alten Entscheidung bleibt, ist die <b>Sichtbarkeit der
 * Herkunft</b>. Nicht im Status - der ist in beiden Faellen "bezahlt" - sondern
 * in der Aufschluesselung: der Monat weist getrennt aus, wieviel aus erkannten
 * Zahlungen ({@code taxPaid}) und wieviel aus nachgetragenen Gutschriften
 * ({@code creditApplied}) stammt, und nennt in {@code appliedCredits} die
 * Buchungen samt Begruender, Zeitpunkt und Grund. Ohne das liesse sich spaeter
 * nicht mehr sagen, ob wirklich Geld geflossen ist oder ob jemand einen Monat per
 * Eintrag geschlossen hat.</p>
 *
 * <p>Verteilt wird <em>hier</em> und nicht in der Oberflaeche - eine zweite
 * Verteilung derselben Gutschrift wuerde frueher oder spaeter anders verteilen
 * als diese, und dann streiten Kopfzeile und Monatszeile ueber dasselbe Geld.
 * Siehe {@link #settleChronologically}.</p>
 *
 * <p>Dieselbe Rechnung bedient drei Sichten: die Eigensicht eines Mitglieds
 * ({@link #ledgerOf}), die Akte eines Members fuer die Fuehrung
 * ({@link #memberLedger}) und die Bilanz ueber alle ({@link #allAccountSummaries}).
 * Alle drei teilen sich {@link #ledgerForAccount} - bewusst, denn zwei
 * Fassungen derselben Aufschluesselung koennten unterschiedlich runden oder
 * sortieren, und dann streiten der Bildschirm des Mitglieds und der des
 * Directors ueber dieselbe Rechnung.</p>
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
    private static final BigDecimal PAID_THRESHOLD = new BigDecimal("0.95");

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** ISK hat ingame genau zwei Nachkommastellen - wie bei {@code MiningTaxCredit}. */
    private static final int ISK_SCALE = 2;

    private static final RoundingMode ISK_ROUNDING = RoundingMode.HALF_UP;

    /**
     * Genauigkeit der Zwischenschritte - reichlich ueber allem, was gebraucht
     * wird; dieselbe Wahl wie in {@code IndustryMath}.
     */
    private static final MathContext MC = new MathContext(24, RoundingMode.HALF_UP);

    /**
     * Ab dem wievielten Tag des uebernaechsten Monats eingefroren werden darf.
     *
     * <p>Der ESI-Mining-Ledger reicht rund 30 Tage zurueck. Der letzte Tag des
     * Monats M ist also erst gegen Ende von M+1 aus dem Fenster gefallen; der
     * zweite Tag von M+2 laesst zusaetzlich einen ausgefallenen Abgleich zu.
     * Wer frueher einfriert, schliesst die Rechnung ab, bevor die letzte Zeile
     * angekommen ist - genau der Schaden, der im Bestand steht.</p>
     */
    private static final int FREEZE_DAY_OF_MONTH = 2;

    /** Wie weit nach hinten das Einfrieren wartet, gerechnet ab dem Abrechnungsmonat. */
    private static final int FREEZE_DELAY_MONTHS = 2;

    private final CharacterRepository characterRepo;
    private final CharacterMiningRepository miningRepo;
    private final CharacterActivityRepository activityRepo;
    private final MiningTaxInvoiceRepository invoiceRepo;
    private final MiningTaxRateService taxRateService;
    private final InvTypeRepository invTypeRepo;
    private final ObjectMapper objectMapper;
    private final MiningTaxCreditService creditService;
    private final MiningAdminGuard guard;

    public MiningLedgerService(CharacterRepository characterRepo,
                               CharacterMiningRepository miningRepo,
                               CharacterActivityRepository activityRepo,
                               MiningTaxInvoiceRepository invoiceRepo,
                               MiningTaxRateService taxRateService,
                               InvTypeRepository invTypeRepo,
                               ObjectMapper objectMapper,
                               MiningTaxCreditService creditService,
                               MiningAdminGuard guard) {
        this.characterRepo = characterRepo;
        this.miningRepo = miningRepo;
        this.activityRepo = activityRepo;
        this.invoiceRepo = invoiceRepo;
        this.taxRateService = taxRateService;
        this.invTypeRepo = invTypeRepo;
        this.objectMapper = objectMapper;
        this.creditService = creditService;
        this.guard = guard;
    }

    // ==================================================================
    // Bilanz eines Accounts
    // ==================================================================

    /**
     * Die Eigensicht: die Bilanz des Accounts, zu dem dieser Charakter gehoert.
     *
     * <p>{@code readOnly}, und das ist die eigentliche Absicherung dieser
     * Klasse - siehe den Abschnitt "Lesen schreibt nicht" oben.</p>
     */
    @Transactional(readOnly = true)
    public MiningDtos.UserLedgerResponse ledgerOf(Long characterId) {
        Character character = requireCharacter(characterId);
        AccountLedger ledger = ledgerForAccount(character.getAccountId());

        return new MiningDtos.UserLedgerResponse(ledger.totalTax(), ledger.totalPaid(),
                ledger.credited(), ledger.balance(), ledger.months());
    }

    /**
     * Die Steuerakte eines Members, wie sie die Fuehrung sieht: Monate,
     * Aufschluesselung nach Erz und der Gutschriftenverlauf.
     *
     * <p>Bis hierher sah die Fuehrung je Account vier Zahlen und kein einziges
     * Erz. Die Aufschluesselung existierte laengst - sie hing nur ausschliesslich
     * an der Eigensicht des Mitglieds.</p>
     *
     * <p>Der Einstieg ist die <b>Account</b>-ID aus der Uebersicht, nicht eine
     * Charakter-ID wie bei {@link #ledgerOf}. Wer trotzdem die ID eines Alts
     * schickt, bekommt die Akte des Verbunds - eine getrennte Akte je Alt gibt
     * es nicht, weil die Steuer ueber den Verbund gefuehrt wird.</p>
     *
     * @throws AccessDeniedException wenn der Aufrufer nicht zur Fuehrung gehoert.
     *     Geprueft <em>hier</em> und nicht nur am Endpunkt - siehe
     *     {@link MiningAdminGuard}.
     */
    @Transactional(readOnly = true)
    public MiningDtos.AdminMemberLedgerDto memberLedger(Long actorId, Long accountId) {
        guard.requireLeadership(actorId);

        Character member = requireCharacter(accountId);
        Long resolvedAccountId = member.getAccountId();
        AccountLedger ledger = ledgerForAccount(resolvedAccountId);

        String name = characterRepo.findById(resolvedAccountId)
                .map(Character::getName)
                .orElseGet(member::getName);

        return new MiningDtos.AdminMemberLedgerDto(resolvedAccountId, name,
                EveImageUrls.portrait(resolvedAccountId),
                ledger.totalTax(), ledger.totalPaid(), ledger.credited(), ledger.balance(),
                ledger.months(), creditService.historyForChecked(resolvedAccountId));
    }

    /**
     * Die gemeinsame Rechnung hinter allen drei Sichten.
     *
     * @param totalTax Summe ueber die Monate - und zwar ueber genau die
     *     {@link MiningDtos.MonthlyLedgerDto}, die auch hinausgehen. Wer den Wert
     *     stattdessen noch einmal aus den Rohdaten zoege, koennte bei einem
     *     eingefrorenen Monat eine andere Zahl bekommen als die Anzeige.
     * @param balance Zahlung plus Gutschrift minus Schuld - exakt, weil alle drei
     *     Summanden es sind.
     */
    private record AccountLedger(BigDecimal totalTax, BigDecimal totalPaid, BigDecimal credited,
                                 BigDecimal balance, List<MiningDtos.MonthlyLedgerDto> months) {}

    private AccountLedger ledgerForAccount(Long accountId) {
        return ledgerForAccount(accountId, creditService.applicableFor(accountId));
    }

    /**
     * @param credits die Gutschriften werden hereingereicht, weil die Uebersicht
     *     sie fuer alle Accounts auf einmal holt - dieselbe Rechnung, eine Abfrage
     *     statt einer je Account. Als Liste und nicht als Summe: die Monatszeile
     *     nennt die Buchungen, die sie decken, und die Summe faellt hier aus
     *     genau derselben Liste. Zwei Wege zur selben Zahl koennten auseinander
     *     laufen.
     */
    private AccountLedger ledgerForAccount(Long accountId,
                                           List<MiningDtos.TaxCreditDto> credits) {
        List<Long> accountCharacterIds = characterIdsOf(accountId);

        BigDecimal totalPaid = sumTaxPayments(activityRepo.findByCharacterIdIn(accountCharacterIds));
        Map<String, Map<Long, Long>> minedByMonth = groupByMonth(
                miningRepo.findByCharacterIdIn(accountCharacterIds));
        Map<String, MiningTaxInvoice> invoices = invoiceRepo.findByMainCharacterId(accountId).stream()
                .collect(Collectors.toMap(MiningTaxInvoice::getMonth, Function.identity(), (a, b) -> a));

        BigDecimal credited = sumCredits(credits);
        List<MiningDtos.MonthlyLedgerDto> months =
                settleChronologically(minedByMonth, invoices, totalPaid, credits);

        BigDecimal totalTax = months.stream()
                .map(MiningDtos.MonthlyLedgerDto::totalTax)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Neueste Abrechnung zuerst - so wird sie im Frontand zuerst gelesen.
        List<MiningDtos.MonthlyLedgerDto> newestFirst = months.stream()
                .sorted(Comparator.comparing(MiningDtos.MonthlyLedgerDto::month).reversed())
                .toList();

        return new AccountLedger(totalTax, totalPaid, credited,
                totalPaid.add(credited).subtract(totalTax), newestFirst);
    }

    /**
     * Alle Charaktere eines Accounts.
     *
     * <p>Die ID des Accounts steht ausdruecklich mit drin, und das ist kein
     * Zierrat: ein Main traegt seine eigene ID in {@code main_character_id} ODER
     * gar keine - {@code Character.getAccountId()} sagt das ausdruecklich. In der
     * zweiten Schreibweise findet {@code findByMainCharacterId} ihn nicht, und er
     * fiele aus seiner eigenen Bilanz heraus: seine Mining-Zeilen und seine
     * Zahlungen zaehlten dann nicht mit. Die Admin-Uebersicht gruppiert bereits
     * ueber {@code getAccountId()} und haette fuer denselben Account eine andere
     * Zahl gezeigt als die Detailakte.</p>
     */
    private List<Long> characterIdsOf(Long accountId) {
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(accountId);
        characterRepo.findByMainCharacterId(accountId).forEach(character -> ids.add(character.getId()));
        return List.copyOf(ids);
    }

    private Character requireCharacter(Long characterId) {
        return characterRepo.findById(characterId).orElseThrow(
                () -> new IllegalArgumentException("Charakter " + characterId + " ist unbekannt."));
    }

    /**
     * Rechnet die Monate von alt nach neu ab und verteilt das gedeckte Geld dabei
     * nach dem Wasserfall-Prinzip.
     *
     * <p>Ein Monat ohne Snapshot wird aus den Rohdaten gerechnet, und zwar bei
     * jedem Abruf neu. Genau das ist gewollt, solange er nicht eingefroren ist:
     * eine nachgelieferte ESI-Zeile soll noch zaehlen.</p>
     *
     * <h2>Ein Wasserfall, zwei Quellen</h2>
     * <p>Je Monat deckt zuerst, was an Ueberweisungen erkannt wurde, dann was an
     * Gutschriften nachgetragen ist; beide Toepfe laufen von alt nach neu weiter.
     * Ein Monat, dessen Deckung die Schwelle erreicht, ist <b>bezahlt</b> - egal
     * aus welcher der beiden Quellen sie stammt. Eine Gutschrift ist ein Nachtrag
     * auf eine Zahlung, die stattgefunden hat; ihn weiter als offen zu fuehren
     * hiesse, auf einer Schuld zu bestehen, die jemand fuer beglichen erklaert
     * hat. Die frueher hier begruendete Trennung der beiden Wasserfaelle ist damit
     * hinfaellig - warum, steht im Klassenkommentar.</p>
     *
     * <p><b>Erst die Zahlung, dann die Gutschrift</b>, und diese Reihenfolge
     * entscheidet nicht ueber den Status, sondern ueber die Herkunft: was eine
     * erkannte Ueberweisung deckt, soll nicht als Nachtrag ausgewiesen werden.
     * Andersherum verbrauchte eine Gutschrift Monate, die ohnehin ueberwiesen
     * sind, und ein wirklich offener Monat forderte Geld ein, das da ist.</p>
     *
     * <p>Warum auch die Gutschriften von alt nach neu laufen: sie sind ein Topf,
     * kein Monatsbetrag. Reicht er nicht fuer alle offenen Monate, muss irgendeine
     * Reihenfolge entscheiden - und die aelteste Schuld zuerst zu tilgen ist die
     * einzige, die sich jemandem erklaeren laesst. Die Alternative "neueste
     * zuerst" liesse ausgerechnet den aeltesten Rueckstand stehen.</p>
     *
     * <p>Ist das Konto im Minus, ist der Topf verbraucht, bevor der letzte Monat
     * erreicht ist, und die verbleibenden Monate fordern weiter zur Ueberweisung
     * auf. Die Summe aller {@code amountDue} ist dabei genau das Minus des
     * Saldos.</p>
     *
     * @param credits die verrechenbaren Buchungen des Accounts, aelteste zuerst -
     *     hereingereicht statt hier erneut geholt, damit Kopfzeile und
     *     Monatszeilen nicht zwei Abfragen mit zwei Ergebnissen sehen koennen
     */
    private List<MiningDtos.MonthlyLedgerDto> settleChronologically(
            Map<String, Map<Long, Long>> minedByMonth,
            Map<String, MiningTaxInvoice> invoices,
            BigDecimal totalPaid,
            List<MiningDtos.TaxCreditDto> credits) {

        Set<String> allMonths = new TreeSet<>(minedByMonth.keySet());
        allMonths.addAll(invoices.keySet());

        Map<Long, MiningTaxRate> rates = taxRateService.findAllByTypeId();
        Map<Long, Double> volumes = volumesOf(minedByMonth);

        List<MiningDtos.MonthlyLedgerDto> result = new ArrayList<>(allMonths.size());
        BigDecimal unallocated = totalPaid;
        List<OpenCredit> openCredits = credits.stream().map(OpenCredit::new).toList();

        for (String month : allMonths) {
            MonthlyBill bill = invoices.containsKey(month)
                    ? restoreFrozenBill(invoices.get(month))
                    : calculateBill(minedByMonth.get(month), rates, volumes);

            BigDecimal fromPayments = unallocated.min(bill.totalTax());
            unallocated = unallocated.subtract(fromPayments);

            // Was nach den erkannten Ueberweisungen offen steht - und NUR das
            // traegt eine Gutschrift nach. Ein Monat, der laengst ueberwiesen ist,
            // hat hier eine Null stehen und verbraucht keine Buchung.
            BigDecimal stillOpen = bill.totalTax().subtract(fromPayments).max(BigDecimal.ZERO);
            CreditCoverage fromCredits = applyCredits(openCredits, stillOpen);

            BigDecimal covered = fromPayments.add(fromCredits.applied());
            // compareTo, nicht equals: 2.5 und 2.50 sind fuer equals verschieden.
            boolean paid = covered.compareTo(bill.totalTax().multiply(PAID_THRESHOLD, MC)) >= 0;

            result.add(new MiningDtos.MonthlyLedgerDto(month, bill.totalTax(), fromPayments,
                    fromCredits.applied(), paid, stillOpen.subtract(fromCredits.applied()),
                    fromCredits.bookings(), bill.details()));
        }
        return result;
    }

    /**
     * Eine Gutschrift und der Teil, der von ihr noch nicht verrechnet ist.
     *
     * <p>Veraenderlich, weil eine Buchung ueber mehrere Monate reicht: 500 Mio
     * koennen den Januar ganz und den Februar zur Haelfte nachtragen. Ohne diesen
     * Rest muesste die Verteilung entweder je Monat neu von vorn zaehlen oder eine
     * Buchung ganz einem Monat zuschlagen - das eine vergibt dasselbe Geld
     * mehrfach, das andere reisst eine Luecke.</p>
     */
    private static final class OpenCredit {

        private final MiningDtos.TaxCreditDto booking;

        private BigDecimal remaining;

        private OpenCredit(MiningDtos.TaxCreditDto booking) {
            this.booking = booking;
            // Einen negativen Betrag gibt es hier nicht mehr - die Gegenbuchungen
            // sind bereits herausgefiltert. Bliebe doch einer stehen, wuerde ihn
            // min() als Deckung durchreichen und amountDue waere groesser als die
            // Schuld des Monats.
            this.remaining = orZero(booking.amount()).max(BigDecimal.ZERO);
        }
    }

    /**
     * Was Gutschriften bei einem Monat gedeckt haben: der Betrag und der Nachweis
     * dazu, aus einer Rechnung und nicht aus zweien.
     */
    private record CreditCoverage(BigDecimal applied, List<MiningDtos.AppliedCreditDto> bookings) {}

    /**
     * Traegt aus den noch offenen Gutschriften nach, was der Monat braucht.
     *
     * <p>Verbucht wird buchungsweise und nicht aus einem Sammeltopf, weil sonst
     * nur die Summe uebrig bliebe. Die Herkunft ist aber genau das, was von der
     * alten Trennung erhalten bleiben muss: ein Monat, der durch einen Nachtrag
     * als bezahlt gilt, soll sich von einem unterscheiden lassen, bei dem eine
     * Ueberweisung erkannt wurde.</p>
     *
     * <p>Buchungen ohne Anteil kommen nicht in die Liste - eine Zeile "0,00 ISK
     * aus dieser Gutschrift" behauptet einen Zusammenhang, den es nicht gibt.</p>
     */
    private static CreditCoverage applyCredits(List<OpenCredit> credits, BigDecimal stillOpen) {
        BigDecimal open = stillOpen;
        BigDecimal applied = BigDecimal.ZERO;
        List<MiningDtos.AppliedCreditDto> bookings = new ArrayList<>();

        for (OpenCredit credit : credits) {
            if (open.signum() <= 0) {
                break;
            }
            BigDecimal take = credit.remaining.min(open);
            if (take.signum() <= 0) {
                continue;
            }
            credit.remaining = credit.remaining.subtract(take);
            open = open.subtract(take);
            applied = applied.add(take);
            bookings.add(new MiningDtos.AppliedCreditDto(credit.booking.id(), take,
                    credit.booking.amount(), credit.booking.actorCharacterId(),
                    credit.booking.actorName(), credit.booking.reason(),
                    credit.booking.occurredAt()));
        }
        return new CreditCoverage(applied, List.copyOf(bookings));
    }

    /**
     * Die Gutschriftensumme des Accounts - aus genau der Liste, die auch verteilt
     * wird.
     *
     * <p>Nicht aus einer zweiten Abfrage: Kopfzeile und Monatszeilen wuerden dann
     * ueber dasselbe Geld streiten, sobald zwischen beiden Abfragen jemand bucht.</p>
     */
    private static BigDecimal sumCredits(List<MiningDtos.TaxCreditDto> credits) {
        return credits.stream()
                .map(credit -> orZero(credit.amount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Eine Monatsabrechnung, unabhaengig davon ob sie eingefroren oder frisch gerechnet ist. */
    private record MonthlyBill(BigDecimal totalTax, List<MiningDtos.LedgerItemDto> details) {}

    private MonthlyBill restoreFrozenBill(MiningTaxInvoice invoice) {
        return new MonthlyBill(orZero(invoice.getTotalTax()), readDetails(invoice));
    }

    /**
     * Rechnet einen Monat aus den Rohdaten.
     *
     * <p>Rein - kein Schreibzugriff, auch nicht auf einen fehlenden Steuersatz.
     * Hier stand einmal ein {@code createMissingRate}, das mitten im Lesepfad
     * eine Zeile anlegte; ein unbekannter Typ kostet ohnehin nichts, und die
     * Saetze pflegt {@code MiningTaxRateService.synchronizeWithSde()} beim
     * Start.</p>
     *
     * @param minedQuantities die abgebauten Mengen dieses Monats, nie
     *     {@code null}: beide Aufrufer holen sie mit einem Schluessel aus
     *     {@link #groupByMonth}, und dort entsteht kein leerer Eintrag. Ein
     *     Monat, der nur als eingefrorene Rechnung existiert, kommt hier gar
     *     nicht an - fuer ihn gewinnt der Snapshot.
     */
    private MonthlyBill calculateBill(Map<Long, Long> minedQuantities,
                                      Map<Long, MiningTaxRate> rates, Map<Long, Double> volumes) {
        List<MiningDtos.LedgerItemDto> details = new ArrayList<>(minedQuantities.size());

        for (Map.Entry<Long, Long> mined : minedQuantities.entrySet()) {
            Long typeId = mined.getKey();
            long quantity = mined.getValue();
            MiningTaxRate rate = rates.get(typeId);

            details.add(new MiningDtos.LedgerItemDto(typeId, typeNameOf(typeId, rate),
                    categoryOf(rate), quantity, quantity * volumes.getOrDefault(typeId, 0.0),
                    priceOf(rate), taxFor(quantity, rate)));
        }

        details.sort(Comparator.comparing(MiningDtos.LedgerItemDto::taxToPay).reversed());

        // Die Summe wird aus genau der Liste gezogen, die hinausgeht, und aus
        // Posten, die bereits auf die zweite Nachkommastelle gerundet sind. Damit
        // gilt die Zusage der ganzen Ansicht: wer die Erzanteile zusammenzaehlt,
        // bekommt die Gesamtsteuer - nicht fast. Zuvor lief die Summe waehrend
        // des Aufbaus mit, also in der Reihenfolge der HashMap, und
        // Gleitkommaaddition ist nicht assoziativ: die Aufschluesselung konnte
        // sich in der letzten Stelle von ihrer eigenen Gesamtsumme unterscheiden.
        BigDecimal totalTax = details.stream()
                .map(MiningDtos.LedgerItemDto::taxToPay)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new MonthlyBill(totalTax, details);
    }

    private static String typeNameOf(Long typeId, MiningTaxRate rate) {
        return rate != null && rate.getTypeName() != null
                ? rate.getTypeName()
                : "Unknown Ore (" + typeId + ")";
    }

    private static String categoryOf(MiningTaxRate rate) {
        return rate != null && rate.getCategory() != null
                ? rate.getCategory()
                : OreCategory.ORE.dbValue();
    }

    private static BigDecimal priceOf(MiningTaxRate rate) {
        return rate == null ? BigDecimal.ZERO : orZero(rate.getCurrentJitaBuy());
    }

    // ==================================================================
    // Einfrieren - aus einem geplanten Lauf, nie aus einem GET
    // ==================================================================

    /**
     * Friert die Monate ein, die sicher abgeschlossen sind.
     *
     * <p>Aufgerufen von {@code MiningInvoiceFreezeScheduler}. Zwei Bedingungen
     * muessen zusammenkommen, und beide haben einen Schaden im Bestand als
     * Anlass:</p>
     * <ol>
     *   <li><b>Karenzzeit.</b> Ein Monat wird nicht eingefroren, sobald er vorbei
     *       ist, sondern erst, wenn das ESI-Fenster keine Zeile fuer ihn mehr
     *       liefern kann - siehe {@link #FREEZE_DAY_OF_MONTH}.</li>
     *   <li><b>Gueltige Anmeldung.</b> Hat auch nur ein Charakter des Accounts
     *       ein ungueltiges Token, wird aufgeschoben. Sonst friert man einem
     *       Mitglied, dessen Anmeldung sechs Wochen abgelaufen war, einen Monat
     *       ein, der nie abgeglichen wurde - eine Rechnung ueber null ISK, die
     *       danach nie wieder korrigiert wird.</li>
     * </ol>
     *
     * @return wie viele Rechnungen neu geschrieben wurden
     */
    @Transactional
    public int freezeDueMonths() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Map<Long, List<Character>> charactersByAccount = characterRepo.findAll().stream()
                .collect(Collectors.groupingBy(Character::getAccountId));

        Map<Long, MiningTaxRate> rates = taxRateService.findAllByTypeId();
        int frozen = 0;

        for (Map.Entry<Long, List<Character>> account : charactersByAccount.entrySet()) {
            Long accountId = account.getKey();
            if (hasInvalidToken(account.getValue())) {
                log.info("Einfrieren fuer Account {} aufgeschoben: mindestens ein Token ist ungueltig, "
                        + "die Rohdaten waeren unvollstaendig.", accountId);
                continue;
            }
            frozen += freezeAccount(accountId, today, rates);
        }

        if (frozen > 0) {
            log.info("{} Monatsrechnungen eingefroren.", frozen);
        }
        return frozen;
    }

    private static boolean hasInvalidToken(List<Character> characters) {
        return characters.stream().anyMatch(character -> character.getTokenInvalidSince() != null);
    }

    private int freezeAccount(Long accountId, LocalDate today, Map<Long, MiningTaxRate> rates) {
        List<Long> characterIds = characterIdsOf(accountId);
        Map<String, Map<Long, Long>> minedByMonth =
                groupByMonth(miningRepo.findByCharacterIdIn(characterIds));
        if (minedByMonth.isEmpty()) {
            return 0;
        }

        Set<String> alreadyFrozen = invoiceRepo.findByMainCharacterId(accountId).stream()
                .map(MiningTaxInvoice::getMonth)
                .collect(Collectors.toSet());
        Map<Long, Double> volumes = volumesOf(minedByMonth);

        int frozen = 0;
        for (String month : new TreeSet<>(minedByMonth.keySet())) {
            if (alreadyFrozen.contains(month) || !isSettled(month, today)) {
                continue;
            }
            frozen += freeze(accountId, month, calculateBill(minedByMonth.get(month), rates, volumes));
        }
        return frozen;
    }

    /**
     * Ob ein Monat aus dem ESI-Fenster gefallen und damit vollstaendig ist.
     *
     * <p>Ein unbrauchbarer Monatsschluessel gilt als nicht abgeschlossen: lieber
     * eine Rechnung zu spaet als eine ueber Daten, die niemand deuten kann.</p>
     */
    private static boolean isSettled(String month, LocalDate today) {
        try {
            LocalDate release = YearMonth.parse(month)
                    .plusMonths(FREEZE_DELAY_MONTHS)
                    .atDay(FREEZE_DAY_OF_MONTH);
            return !today.isBefore(release);
        } catch (RuntimeException e) {
            log.warn("Monatsschluessel {} ist unlesbar, wird nicht eingefroren: {}",
                    month, e.getMessage());
            return false;
        }
    }

    /**
     * Haelt die Abrechnung eines abgeschlossenen Monats unveraenderlich fest.
     *
     * <p>Ueber ein {@code INSERT ... ON CONFLICT DO NOTHING} und nicht ueber
     * {@code save} - siehe {@code MiningTaxInvoiceRepository.insertIfAbsent}.
     * Damit ist auch ein doppelt gestarteter Lauf harmlos.</p>
     *
     * @return 1, wenn die Rechnung neu ist, sonst 0
     */
    private int freeze(Long accountId, String month, MonthlyBill bill) {
        int written = invoiceRepo.insertIfAbsent(accountId, month,
                bill.totalTax().setScale(ISK_SCALE, ISK_ROUNDING),
                writeDetails(month, bill.details()), Instant.now());
        if (written == 0) {
            log.debug("Rechnung fuer Account {} im Monat {} bestand bereits.", accountId, month);
        }
        return written;
    }

    // ==================================================================
    // Admin-Uebersicht
    // ==================================================================

    /**
     * Die Bilanz aller Accounts, das groesste Minus zuerst.
     *
     * <p>Bewusst ueber dieselbe {@link #ledgerForAccount}, die auch die
     * Eigensicht und die Akte tragen. Zuvor addierte diese Sicht die
     * eingefrorenen Rechnungen und rechnete nur den <em>laufenden</em> Monat
     * live dazu. Seit das Einfrieren eine Karenzzeit hat, bleibt ein vergangener
     * Monat regulaer bis zu rund 32 Tage ohne Rechnung - er haette in der
     * Uebersicht schlicht mit null Steuer gestanden, waehrend die Akte desselben
     * Accounts den richtigen Betrag zeigte.</p>
     *
     * <p>Die Gutschrift ist die dritte Groesse und keine Kosmetik: ohne sie
     * zeigte die Bilanz weiter ein Minus, das laengst ausgeglichen ist, und
     * jemand wuerde einem Mitglied hinterherlaufen, dem er das Geld selbst
     * zugesprochen hat.</p>
     *
     * @throws AccessDeniedException wenn der Aufrufer nicht zur Fuehrung gehoert.
     *     Der Parameter kam mit dieser Pruefung dazu - zuvor stand die Regel nur
     *     als Annotation am Endpunkt.
     */
    @Transactional(readOnly = true)
    public List<MiningDtos.AdminLedgerSummaryDto> allAccountSummaries(Long actorId) {
        guard.requireLeadership(actorId);

        Map<Long, List<Character>> charactersByAccount = characterRepo.findAll().stream()
                .collect(Collectors.groupingBy(Character::getAccountId));
        Map<Long, List<MiningDtos.TaxCreditDto>> creditsByAccount =
                creditService.applicableByAccount();

        return charactersByAccount.entrySet().stream()
                .map(entry -> toSummary(entry.getKey(), entry.getValue(),
                        ledgerForAccount(entry.getKey(),
                                creditsByAccount.getOrDefault(entry.getKey(), List.of()))))
                .sorted(Comparator.comparing(MiningDtos.AdminLedgerSummaryDto::currentBalance))
                .toList();
    }

    private MiningDtos.AdminLedgerSummaryDto toSummary(Long accountId, List<Character> characters,
                                                       AccountLedger ledger) {
        String name = characters.stream()
                .filter(character -> character.getId().equals(accountId))
                .findFirst()
                .orElse(characters.getFirst())
                .getName();

        return new MiningDtos.AdminLedgerSummaryDto(accountId, name, EveImageUrls.portrait(accountId),
                ledger.totalTax(), ledger.totalPaid(), ledger.credited(), ledger.balance());
    }

    /**
     * Die Steuerformel - Menge mal Jita-Kaufpreis mal Satz, gerundet auf die
     * zweite Nachkommastelle.
     *
     * <p>Sie steht hier genau einmal. Zuvor gab es sie zweimal: einmal fuer die
     * Monatsabrechnung und einmal fuer den laufenden Monat der Admin-Uebersicht.
     * Zwei Kopien einer Rechenregel sind eine Kopie zu viel - wer den Satz
     * spaeter etwa auf den Verkaufspreis umstellt, aendert eine davon, und die
     * beiden Sichten zeigen fuer denselben Monat verschiedene Zahlen.</p>
     *
     * <p>Gerundet wird genau hier und nirgends sonst: die Monatssumme ist die
     * Summe dieser Posten, also ist sie ohne weiteres Zutun exakt.</p>
     *
     * <p>Ohne Steuersatz kostet ein Erz nichts: ein Typ, den die SDE erst nach
     * dem letzten Abgleich bekommen hat, soll keine Rechnung erzeugen, die
     * niemand festgelegt hat.</p>
     */
    private static BigDecimal taxFor(long quantity, MiningTaxRate rate) {
        if (rate == null) {
            return BigDecimal.ZERO.setScale(ISK_SCALE);
        }
        return BigDecimal.valueOf(quantity)
                .multiply(orZero(rate.getCurrentJitaBuy()), MC)
                .multiply(orZero(rate.getTaxPercentage()), MC)
                .divide(HUNDRED, MC)
                .setScale(ISK_SCALE, ISK_ROUNDING);
    }

    // ==================================================================
    // Helfer
    // ==================================================================

    private static BigDecimal sumTaxPayments(List<CharacterActivity> activities) {
        return activities.stream()
                .filter(activity -> activity.isOfType(ActivityType.TAX_PAYMENT))
                .filter(activity -> activity.getValue() != null)
                .map(CharacterActivity::getValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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

    private static BigDecimal orZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
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
