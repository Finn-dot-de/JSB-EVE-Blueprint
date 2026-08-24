package com.eve.own.auth.backend.domain.mining.service;

import com.eve.own.auth.backend.common.EveImageUrls;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.mining.dto.MiningDtos;
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxCredit;
import com.eve.own.auth.backend.domain.mining.repository.MiningTaxCreditRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vergibt Steuergutschriften und nimmt sie zurueck.
 *
 * <p>Das ist der einzige Dienst der Anwendung, der Geld zuspricht. Alles Uebrige
 * am Mining-Steuerwesen rechnet nur: die Schuld faellt aus Menge, Preis und
 * Satz, die Zahlung faellt aus dem Wallet-Journal. Hier entscheidet ein Mensch,
 * wieviel ISK ein anderer bekommt - und deshalb sind die Regeln unter dieser
 * Klasse strenger als anderswo.</p>
 *
 * <h2>Fuenf Regeln, und warum jede davon da ist</h2>
 * <ol>
 *   <li><b>Der Betrag ist exakt.</b> Er kommt als Zeichenkette herein, wird zu
 *       {@link BigDecimal} und liegt in einer {@code numeric(20,2)}-Spalte. In
 *       einem {@code double} verlieren Milliardenbetraege Stellen - im Bestand
 *       steht bereits eine Summe {@code 1319981075.6900005}, die so nie gezahlt
 *       wurde. Bei einer gerechneten Zahl ist das haesslich, bei einer zugesagten
 *       waere es falsch.</li>
 *   <li><b>Der Handelnde kommt aus dem Sicherheitskontext</b>, nie aus dem
 *       Rumpf der Anfrage. Sonst schriebe der Aufrufer den Nachweis ueber sich
 *       selbst, und der Nachweis waere keiner.</li>
 *   <li><b>Geprueft wird hier</b>, nicht nur am Controller - siehe
 *       {@link MiningAdminGuard}.</li>
 *   <li><b>Nichts verschwindet.</b> Es gibt kein Loeschen und kein Aendern des
 *       Betrags. Eine Ruecknahme ist eine Gegenbuchung; die urspruengliche
 *       Buchung bleibt mit Betrag, Handelndem, Zeitpunkt und Grund stehen.</li>
 *   <li><b>Selbstvergabe ist erlaubt und wird gekennzeichnet.</b> Das Leadership
 *       schuerft selbst. Eine Sperre wuerde den Vorgang nur auf einen Weg ohne
 *       Nachweis draengen - der Director bittet dann den naechsten Director.
 *       Sichtbar muss er sein, nicht unmoeglich; dieselbe Entscheidung wie bei
 *       {@code RoleAssignmentService}.</li>
 * </ol>
 *
 * <h2>Was eine Gutschrift ist: ein Nachtrag</h2>
 * <p>Sie ist <b>keine Zuwendung</b>, sondern eine Korrektur. Der Regelfall, aus
 * dem heraus sie vergeben wird, ist immer derselbe: ein Mitglied <em>hat</em>
 * bezahlt, und die Erkennung hat es nicht mitbekommen - die Ueberweisung kam von
 * einem Charakter ausserhalb des Verbunds, lief ueber einen Contract oder ging in
 * einem Zeitraum ein, den kein Abgleich erfasst hat. Jemand aus der Fuehrung
 * traegt das dann von Hand nach.</p>
 *
 * <p>Deshalb laeuft die Gutschrift durch <b>denselben</b> Wasserfall wie die
 * Zahlungen ({@code MiningLedgerService.settleChronologically}), aelteste Schuld
 * zuerst, und ein dadurch gedeckter Monat gilt als <b>bezahlt</b>. Hier stand
 * lange das Gegenteil, mit der Begruendung, eine heute vergebene Gutschrift duerfe
 * den Januar nicht rueckwirkend als bezahlt ausweisen, weil fuer den Januar
 * niemand etwas ueberwiesen habe. Diese Begruendung war auf eine Zuwendung
 * gemuenzt und traegt bei einer Korrektur nicht: der Januar <em>war</em> bezahlt,
 * die Erkennung hat versagt, und die Gutschrift traegt genau das nach. Ihn
 * weiterhin als offen zu fuehren hiesse, an einer Schuld festzuhalten, die
 * jemand ausdruecklich fuer beglichen erklaert hat.</p>
 *
 * <p>Was dabei erhalten bleibt, ist die <b>Herkunft</b>: {@link #applicableFor}
 * gibt nicht nur eine Summe heraus, sondern die einzelnen Buchungen, und die
 * Monatszeile weist getrennt aus, wieviel aus erkannten Zahlungen und wieviel aus
 * nachgetragenen Gutschriften stammt - mit Verweis auf diese Tabelle. Ohne das
 * liesse sich spaeter nicht mehr sagen, ob wirklich Geld geflossen ist oder ob
 * jemand einen Monat per Eintrag geschlossen hat.</p>
 */
@Slf4j
@Service
public class MiningTaxCreditService {

    /** ISK hat ingame genau zwei Nachkommastellen - mehr anzunehmen waere Fiktion. */
    private static final int ISK_SCALE = 2;

    /** Drei Ziffern hinter einem einzelnen Punkt - siehe {@link #normalizeSeparators}. */
    private static final int THOUSANDS_GROUP_LENGTH = 3;

    /**
     * Obergrenze je Buchung.
     *
     * <p>Nicht gegen Betrug - wer die Rolle hat, kann zweimal buchen. Sondern
     * gegen den verrutschten Finger: eine Null zuviel bei zehn Milliarden ist
     * eine Eingabe, die niemand beabsichtigt, und sie faellt in einer Liste aus
     * grossen Zahlen nicht auf. Die Grenze liegt weit ueber jeder realen
     * Ausschuettung und deutlich unter dem, was {@code numeric(20,2)} traegt.</p>
     */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");

    private final MiningTaxCreditRepository creditRepo;
    private final CharacterRepository characterRepo;
    private final MiningAdminGuard guard;

    public MiningTaxCreditService(MiningTaxCreditRepository creditRepo,
                                  CharacterRepository characterRepo,
                                  MiningAdminGuard guard) {
        this.creditRepo = creditRepo;
        this.characterRepo = characterRepo;
        this.guard = guard;
    }

    // ==================================================================
    // Vergeben
    // ==================================================================

    /**
     * Schreibt einem Account einen Betrag gut.
     *
     * @param actorId der Handelnde aus dem Sicherheitskontext
     * @param accountId der beguenstigte Account (Main-ID, siehe
     *     {@code Character.getAccountId()})
     * @param rawAmount der Betrag als Zeichenkette, damit unterwegs keine Stelle
     *     verloren geht - siehe {@code MiningDtos.GrantCreditDto}
     * @param reason freiwillige Begruendung, darf {@code null} sein
     * @return die geschriebene Buchung
     * @throws AccessDeniedException wenn der Handelnde nicht zur Fuehrung gehoert
     * @throws IllegalArgumentException bei unbekanntem Account oder unbrauchbarem Betrag
     */
    @Transactional
    public MiningDtos.TaxCreditDto grant(Long actorId, Long accountId, String rawAmount,
                                         String reason) {
        Character actor = guard.requireLeadership(actorId);
        Character account = requireAccount(accountId);
        BigDecimal amount = parseAmount(rawAmount);

        MiningTaxCredit credit = new MiningTaxCredit();
        credit.setAccountId(account.getAccountId());
        credit.setAmount(amount);
        credit.setStatus(MiningTaxCredit.STATUS_ACTIVE);
        credit.setActorCharacterId(actor.getId());
        // Der Vergleich laeuft gegen den ACCOUNT des Handelnden, nicht gegen
        // seine Charakter-ID: ein Director, der seinem eigenen Alt-Verbund etwas
        // gutschreibt, bedient sich genauso selbst.
        credit.setSelfGranted(actor.getAccountId().equals(account.getAccountId()));
        credit.setReason(blankToNull(reason));
        credit.setOccurredAt(Instant.now());

        // Der Rueckgabewert und nicht das uebergebene Objekt: die erzeugte ID
        // steht erst danach fest, und der Aufrufer bekommt die Buchung zurueck.
        MiningTaxCredit saved = creditRepo.save(credit);
        logBooking("gutgeschrieben", saved, actor, account);
        return toDto(saved, involved(actor, account));
    }

    // ==================================================================
    // Zuruecknehmen
    // ==================================================================

    /**
     * Nimmt eine Gutschrift zurueck, ohne sie zu loeschen.
     *
     * <p>Es entsteht eine zweite Zeile ueber den negativen Betrag; die
     * urspruengliche wechselt auf {@code REVERSED} und bleibt sonst unberuehrt.
     * Wer spaeter nachsieht, findet beide - die Zusage und ihre Ruecknahme, mit
     * je eigenem Handelnden, Zeitpunkt und Grund. Ein {@code DELETE} haette die
     * Frage "was war da eigentlich?" unbeantwortbar gemacht.</p>
     *
     * @param creditId die zurueckzunehmende Buchung
     * @param reason warum - freiwillig, aber hier besonders erwuenscht
     * @return die <b>Gegenbuchung</b>. Nicht die urspruengliche Zeile: der
     *     Aufrufer soll sehen, was neu entstanden ist, und die alte steht
     *     unveraendert im Verlauf.
     * @throws IllegalArgumentException wenn es die Buchung nicht gibt
     * @throws IllegalStateException wenn sie schon zurueckgenommen ist oder
     *     selbst eine Gegenbuchung ist
     */
    @Transactional
    public MiningDtos.TaxCreditDto reverse(Long actorId, Long creditId, String reason) {
        Character actor = guard.requireLeadership(actorId);

        MiningTaxCredit original = creditRepo.findById(creditId).orElseThrow(
                () -> new IllegalArgumentException("Gutschrift " + creditId + " ist unbekannt."));

        // Eine Gegenbuchung gegenzubuchen waere die Ruecknahme der Ruecknahme -
        // also wieder eine Gutschrift, nur ohne dass jemand einen Betrag genannt
        // haette. Wer das Geld doch geben will, legt eine neue Gutschrift an;
        // dann steht der Betrag wieder ausdruecklich da.
        if (MiningTaxCredit.STATUS_REVERSAL.equals(original.getStatus())) {
            throw new IllegalStateException(
                    "Buchung " + creditId + " ist selbst eine Gegenbuchung und laesst sich "
                            + "nicht zuruecknehmen. Lege stattdessen eine neue Gutschrift an.");
        }
        if (!MiningTaxCredit.STATUS_ACTIVE.equals(original.getStatus())
                || creditRepo.existsByReversalOfCreditId(creditId)) {
            throw new IllegalStateException(
                    "Gutschrift " + creditId + " wurde bereits zurueckgenommen.");
        }

        MiningTaxCredit reversal = new MiningTaxCredit();
        reversal.setAccountId(original.getAccountId());
        reversal.setAmount(original.getAmount().negate());
        reversal.setStatus(MiningTaxCredit.STATUS_REVERSAL);
        reversal.setReversalOfCreditId(original.getId());
        reversal.setActorCharacterId(actor.getId());
        // Auch die Ruecknahme kann ein Alleingang in eigener Sache sein - etwa
        // wenn jemand die Gutschrift eines Kollegen an sich selbst kassiert.
        // Sichtbar bleibt sie deshalb genauso wie die Vergabe.
        reversal.setSelfGranted(actor.getAccountId().equals(original.getAccountId()));
        reversal.setReason(blankToNull(reason));
        reversal.setOccurredAt(Instant.now());

        MiningTaxCredit saved = creditRepo.save(reversal);

        // Erst nach dem Speichern der Gegenbuchung: schluepft das Anlegen in die
        // eindeutige Bedingung auf reversal_of_credit_id, faellt die ganze
        // Transaktion zurueck und die urspruengliche Zeile bleibt ACTIVE. Der
        // umgekehrte Weg koennte eine Buchung zurueckgenommen aussehen lassen,
        // ohne dass die Gegenbuchung existiert.
        original.setStatus(MiningTaxCredit.STATUS_REVERSED);
        creditRepo.save(original);

        Character account = characterRepo.findById(original.getAccountId()).orElse(null);
        logBooking("zurueckgenommen", saved, actor, account);
        return toDto(saved, involved(actor, account));
    }

    // ==================================================================
    // Nachlesen
    // ==================================================================

    /** Der Gutschriftenverlauf eines Accounts, das Juengste zuerst. */
    @Transactional(readOnly = true)
    public List<MiningDtos.TaxCreditDto> historyFor(Long actorId, Long accountId) {
        guard.requireLeadership(actorId);
        return toDtos(creditRepo.findByAccountIdOrderByOccurredAtDesc(accountId));
    }

    /** Die juengsten Buchungen ueber alle Accounts - der Blick von oben. */
    @Transactional(readOnly = true)
    public List<MiningDtos.TaxCreditDto> recentHistory(Long actorId) {
        guard.requireLeadership(actorId);
        return toDtos(creditRepo.findTop200ByOrderByOccurredAtDesc());
    }

    // ==================================================================
    // Fuer die Bilanz
    // ==================================================================

    /**
     * Die verrechenbaren Buchungen eines Accounts, <b>aelteste zuerst</b>, fuer
     * {@link MiningLedgerService}.
     *
     * <p>Herausgegeben werden die einzelnen Buchungen und nicht ihre Summe. Das
     * ist die Zusicherung, die von der alten Trennung uebrig bleibt: seit eine
     * Gutschrift einen Monat als bezahlt ausweisen kann, muss sich der so gedeckte
     * Monat von einem unterscheiden lassen, bei dem eine Ueberweisung erkannt
     * wurde. Aus einer blossen Summe laesst sich das nicht mehr herauslesen - der
     * Nachweis existiert in dieser Tabelle, er muss nur bis zur Monatszeile
     * durchgereicht werden.</p>
     *
     * <p>Die Summe faellt aus derselben Liste: der Aufrufer addiert genau das,
     * was er auch verteilt. Zwei getrennte Wege - eine Abfrage fuer die Kopfzeile,
     * eine fuer die Verteilung - koennten fuer dasselbe Geld zwei Zahlen liefern,
     * und dann streiten Kopfzeile und Monatszeile.</p>
     *
     * <p><b>Aelteste zuerst</b>, weil der Wasserfall in dieser Richtung tilgt.
     * Der Zeitstempel allein genuegt dabei nicht: zwei Buchungen derselben Minute
     * sind moeglich, und ohne festen zweiten Schluessel saehe derselbe Account bei
     * zwei Abrufen zwei verschiedene Buchungen als monatsdeckend.</p>
     *
     * <p>Ohne Rechtepruefung, weil es kein Endpunkt ist: der Aufrufer ist
     * {@link MiningLedgerService}, der seinerseits prueft. Die Methode ist
     * paketoeffentlich, damit sie von aussen gar nicht erst erreichbar ist.</p>
     */
    @Transactional(readOnly = true)
    List<MiningDtos.TaxCreditDto> applicableFor(Long accountId) {
        return toDtos(applicable(creditRepo.findByAccountIdOrderByOccurredAtDesc(accountId)));
    }

    /**
     * Dasselbe fuer alle Accounts auf einmal - die Uebersicht rechnet jede Bilanz
     * und darf dafuer nicht je Account eine Abfrage absetzen.
     *
     * <p>Ein Account, dessen Buchungen sich vollstaendig aufheben, bleibt mit
     * einer leeren Liste stehen statt zu verschwinden: "kein Guthaben" und "kein
     * Eintrag" sind fuer den Aufrufer dasselbe, aber ein fehlender Schluessel
     * verleitet zu einem {@code null}.</p>
     */
    @Transactional(readOnly = true)
    Map<Long, List<MiningDtos.TaxCreditDto>> applicableByAccount() {
        List<MiningTaxCredit> all = creditRepo.findAll();
        Map<Long, Character> byId = namesFor(all);

        Map<Long, List<MiningTaxCredit>> perAccount = new HashMap<>();
        all.forEach(credit -> perAccount
                .computeIfAbsent(credit.getAccountId(), key -> new ArrayList<>()).add(credit));

        Map<Long, List<MiningDtos.TaxCreditDto>> result = new HashMap<>();
        perAccount.forEach((accountId, credits) -> result.put(accountId,
                applicable(credits).stream().map(credit -> toDto(credit, byId)).toList()));
        return result;
    }

    /**
     * Die Buchungen, die tatsaechlich Geld darstellen - Gegenbuchungen und die von
     * ihnen aufgehobenen Zeilen fallen heraus.
     *
     * <p>Gefiltert wird ueber {@link MiningTaxCredit#getReversalOfCreditId()} und
     * nicht ueber den Zustand. Fuer eine <em>Summe</em> waere beides gleichwertig -
     * ein Paar {@code (+x, REVERSED)} und {@code (-x, REVERSAL)} hebt sich auf,
     * egal ob man es weglaesst oder mitrechnet. Fuer die <em>Verteilung</em> ist es
     * das nicht: eine negative Zeile im Wasserfall wuerde per {@code min()} als
     * Deckung durchgereicht und der faellige Betrag eines Monats waere groesser als
     * seine Schuld. Die Verweisspalte traegt zudem die eindeutige Bedingung, ist
     * also die verlaesslichere der beiden Angaben, wenn ein Zustand einmal nicht
     * nachgezogen wurde.</p>
     */
    private static List<MiningTaxCredit> applicable(List<MiningTaxCredit> credits) {
        Set<Long> cancelled = credits.stream()
                .map(MiningTaxCredit::getReversalOfCreditId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return credits.stream()
                .filter(credit -> credit.getReversalOfCreditId() == null)
                .filter(credit -> !cancelled.contains(credit.getId()))
                .sorted(OLDEST_FIRST)
                .toList();
    }

    /**
     * Aelteste zuerst, bei gleichem Zeitpunkt die kleinere ID.
     *
     * <p>Die ID ist hier nicht Zierrat, sondern der Grund, warum die Verteilung
     * ueberhaupt wiederholbar ist - siehe {@link #applicableFor}. Ein leerer
     * Zeitpunkt oder eine leere ID kaeme nur aus einer Zeile, die an der Anwendung
     * vorbei entstanden ist; sie wandert ans Ende, statt die Sortierung mit einer
     * {@code NullPointerException} abzubrechen.</p>
     */
    private static final Comparator<MiningTaxCredit> OLDEST_FIRST =
            Comparator.comparing(MiningTaxCredit::getOccurredAt,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(MiningTaxCredit::getId,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    /** Der Verlauf eines Accounts ohne erneute Rechtepruefung - der Aufrufer hat geprueft. */
    @Transactional(readOnly = true)
    List<MiningDtos.TaxCreditDto> historyForChecked(Long accountId) {
        return toDtos(creditRepo.findByAccountIdOrderByOccurredAtDesc(accountId));
    }

    // ==================================================================
    // Innereien
    // ==================================================================

    /**
     * Liest den eingegebenen Betrag.
     *
     * <p>Jede Ablehnung hier ist eine, die sonst als stiller Unsinn in der
     * Datenbank laege:</p>
     * <ul>
     *   <li><b>Leer oder keine Zahl</b> - {@code new BigDecimal("")} wirft, und
     *       die Ausnahme waere fuer den Aufrufer nicht lesbar.</li>
     *   <li><b>Mehrdeutig</b> - siehe {@link #normalizeSeparators}.</li>
     *   <li><b>Null oder negativ</b> - eine Belastung darf nur als Gegenbuchung
     *       einer echten Gutschrift entstehen, nie durch direkte Eingabe. Sonst
     *       koennte jemand ein Minus buchen, das nach nichts aussieht und keine
     *       Gegenseite hat.</li>
     *   <li><b>Zu gross</b> - siehe {@link #MAX_AMOUNT}.</li>
     *   <li><b>Mehr als zwei Nachkommastellen</b> - wird nicht heimlich
     *       gerundet, sondern abgelehnt. Aus 10,9999 stillschweigend 11,00 zu
     *       machen hiesse, einen Betrag zu buchen, den niemand genannt hat.</li>
     * </ul>
     */
    private static BigDecimal parseAmount(String rawAmount) {
        if (rawAmount == null || rawAmount.isBlank()) {
            throw new IllegalArgumentException("Ohne Betrag gibt es keine Gutschrift.");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(normalizeSeparators(rawAmount));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "\"" + rawAmount + "\" ist kein lesbarer ISK-Betrag.");
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Eine Gutschrift ist groesser als null. Um eine bestehende zurueckzunehmen, "
                            + "nutze die Ruecknahme - sie hinterlaesst einen Nachweis.");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException(
                    "Der Betrag liegt ueber der Grenze von " + MAX_AMOUNT.toPlainString()
                            + " ISK je Buchung. Falls das so gemeint war, buche mehrfach.");
        }
        if (amount.scale() > ISK_SCALE) {
            throw new IllegalArgumentException(
                    "ISK hat zwei Nachkommastellen. \"" + rawAmount + "\" hat mehr - "
                            + "gerundet wird hier absichtlich nicht.");
        }
        // setScale und nicht stripTrailingZeros: "5" und "5,00" sollen in der
        // Datenbank zeichengleich landen, sonst unterscheiden sich zwei gleiche
        // Betraege in der Anzeige.
        return amount.setScale(ISK_SCALE, RoundingMode.UNNECESSARY);
    }

    /**
     * Bringt die Schreibweise des Betrags auf die Form, die {@link BigDecimal}
     * liest - und lehnt ab, wo sie nicht eindeutig ist.
     *
     * <p>Der Punkt ist im Deutschen der Tausendertrenner und im Maschinenformat
     * das Dezimalzeichen. Ein erster Anlauf hat die Punkte einfach entfernt; aus
     * dem voellig regulaeren {@code "12345678901.23"} wurde damit das
     * Tausendfache, und nur die Obergrenze hat den Fehler noch aufgehalten. Blind
     * in die andere Richtung zu raten waere genauso falsch: {@code "12.500.000"}
     * ist dann gar keine Zahl mehr.</p>
     *
     * <p>Deshalb wird nicht geraten, sondern unterschieden:</p>
     * <ul>
     *   <li><b>Ein Komma ist dabei</b> - deutsche Schreibweise. Punkte sind
     *       Tausendertrenner, das Komma ist das Dezimalzeichen:
     *       {@code "12.500.000,50"}.</li>
     *   <li><b>Mehr als ein Punkt, kein Komma</b> - Tausendertrenner, denn eine
     *       Dezimalzahl hat hoechstens einen Punkt: {@code "12.500.000"}.</li>
     *   <li><b>Genau ein Punkt mit drei Ziffern dahinter, kein Komma</b> -
     *       MEHRDEUTIG. {@code "12.500"} heisst je nach Herkunft 12,50 oder
     *       12500, und beide Lesarten sind vertretbar. Bei einem Faktor 1000 auf
     *       einem Geldbetrag wird nicht gewaehlt, sondern zurueckgefragt.</li>
     *   <li><b>Alles Uebrige</b> - der Punkt ist das Dezimalzeichen:
     *       {@code "12345678901.23"}.</li>
     * </ul>
     */
    private static String normalizeSeparators(String rawAmount) {
        // Leer- und Unterstriche sind nie bedeutungstragend, nur Gliederung.
        // Das geschuetzte Leerzeichen kommt beim Kopieren aus einer Webseite mit.
        String cleaned = rawAmount.trim()
                .replace(" ", "")
                .replace(" ", "")
                .replace("_", "");

        if (cleaned.indexOf(',') >= 0) {
            return cleaned.replace(".", "").replace(",", ".");
        }

        int firstDot = cleaned.indexOf('.');
        if (firstDot < 0) {
            return cleaned;
        }
        if (cleaned.indexOf('.', firstDot + 1) >= 0) {
            return cleaned.replace(".", "");
        }
        if (cleaned.length() - firstDot - 1 == THOUSANDS_GROUP_LENGTH) {
            throw new IllegalArgumentException(
                    "\"" + rawAmount + "\" ist mehrdeutig: der Punkt kann Tausendertrenner "
                            + "oder Dezimalzeichen sein. Schreibe den Betrag eindeutig, "
                            + "etwa \"12500\" oder \"12,50\".");
        }
        return cleaned;
    }

    /**
     * Der beguenstigte Account.
     *
     * <p>Ueber {@code getAccountId()} umgeleitet: wer versehentlich die ID eines
     * Alts angibt, bucht auf den Verbund, zu dem der Alt gehoert. Die Steuer
     * wird ebenso ueber den Verbund gefuehrt, eine Gutschrift an einen einzelnen
     * Alt liesse sich gegen nichts verrechnen.</p>
     */
    private Character requireAccount(Long accountId) {
        return characterRepo.findById(accountId).orElseThrow(
                () -> new IllegalArgumentException(
                        "Charakter " + accountId + " ist unbekannt - ohne Empfaenger keine Gutschrift."));
    }

    /**
     * Das Echo der Buchung im Protokoll.
     *
     * <p>Kein Ersatz fuer die Tabelle, sondern ihr Abbild an einer Stelle, die
     * ein Betreiber ohnehin liest. Bei einer Selbstvergabe steht sie auf WARN -
     * das ist der Vorgang, ueber den spaeter jemand stolpern soll; derselbe
     * Gedanke wie bei {@code RoleAssignmentService.record}.</p>
     */
    private static void logBooking(String verb, MiningTaxCredit credit, Character actor,
                                   Character account) {
        String amount = credit.getAmount().toPlainString();
        String reason = credit.getReason() != null ? credit.getReason() : "ohne Angabe";
        String target = account != null
                ? account.getName() + " (" + credit.getAccountId() + ")"
                : "Account " + credit.getAccountId();

        if (credit.isSelfGranted()) {
            log.warn("{} ({}) hat SICH SELBST {} ISK {} - Grund: {}",
                    actor.getName(), actor.getId(), amount, verb, reason);
        } else {
            log.info("{} ({}) hat {} {} ISK {} - Grund: {}",
                    actor.getName(), actor.getId(), target, amount, verb, reason);
        }
    }

    /**
     * Handelnder und Beguenstigter als Karte fuer {@link #toDto}.
     *
     * <p>Eine veraenderliche Karte und kein {@code Map.of}: bei einer
     * Selbstvergabe sind beide derselbe Charakter, und {@code Map.of} wirft bei
     * einem doppelten Schluessel. Genau der Fall, den dieser Dienst besonders
     * sichtbar machen soll, waere damit der einzige, der scheitert.</p>
     */
    private static Map<Long, Character> involved(Character actor, Character account) {
        Map<Long, Character> byId = new HashMap<>();
        byId.put(actor.getId(), actor);
        if (account != null) {
            byId.put(account.getId(), account);
        }
        return byId;
    }

    private List<MiningDtos.TaxCreditDto> toDtos(List<MiningTaxCredit> credits) {
        if (credits.isEmpty()) {
            return List.of();
        }
        Map<Long, Character> byId = namesFor(credits);
        return credits.stream().map(credit -> toDto(credit, byId)).toList();
    }

    /**
     * Laedt die Namen zu den IDs eines Verlaufs in EINEM Zug statt je Zeile.
     *
     * <p>Aufgerufen wird sie einmal je Liste, auch von
     * {@link #applicableByAccount()} - dort ueber <em>alle</em> Accounts zusammen.
     * Je Account eine Abfrage waere in der Uebersicht eine je Mitglied.</p>
     */
    private Map<Long, Character> namesFor(Collection<MiningTaxCredit> credits) {
        Set<Long> characterIds = new LinkedHashSet<>();
        credits.forEach(credit -> {
            characterIds.add(credit.getAccountId());
            characterIds.add(credit.getActorCharacterId());
        });
        return characterRepo.findAllById(characterIds).stream()
                .collect(Collectors.toMap(Character::getId, Function.identity(), (a, b) -> a));
    }

    /** Der Charakter kann zwischenzeitlich verschwunden sein; die ID bleibt die beste Auskunft. */
    private static MiningDtos.TaxCreditDto toDto(MiningTaxCredit credit, Map<Long, Character> byId) {
        return new MiningDtos.TaxCreditDto(
                credit.getId(),
                credit.getAccountId(),
                nameOf(byId, credit.getAccountId()),
                EveImageUrls.portrait(credit.getAccountId()),
                amountOf(credit),
                credit.getStatus(),
                credit.getReversalOfCreditId(),
                credit.getActorCharacterId(),
                nameOf(byId, credit.getActorCharacterId()),
                credit.isSelfGranted(),
                credit.getReason(),
                credit.getOccurredAt());
    }

    private static String nameOf(Map<Long, Character> byId, Long characterId) {
        Character character = byId.get(characterId);
        return character != null ? character.getName() : "Charakter " + characterId;
    }

    /**
     * Der Betrag einer Buchung, notfalls null.
     *
     * <p>Die Spalte ist {@code NOT NULL}; die Absicherung gilt Zeilen, die an
     * der Anwendung vorbei entstanden sind. Eine {@code NullPointerException}
     * mitten in der Bilanz waere die schlechtere Antwort - dann sieht niemand
     * mehr irgendeine Zahl.</p>
     */
    private static BigDecimal amountOf(MiningTaxCredit credit) {
        return credit.getAmount() != null ? credit.getAmount() : BigDecimal.ZERO;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
