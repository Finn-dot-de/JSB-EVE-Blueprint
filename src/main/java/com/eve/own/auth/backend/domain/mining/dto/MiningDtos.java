package com.eve.own.auth.backend.domain.mining.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Die Antworttypen der Mining-Endpunkte.
 *
 * <p>Als Sammelklasse, weil die Records ausschliesslich zusammen auftreten und
 * einzelne Dateien mit je drei Zeilen hier nichts gewinnen wuerden.</p>
 *
 * <p><b>Zu den Betraegen.</b> Jeder ISK-Betrag steht als {@link BigDecimal} -
 * Schuld, Zahlung, Gutschrift und Saldo. So liegen sie auch in der Datenbank
 * ({@code numeric(20,2)}), und so sind sie gerechnet. Hier stand zuvor ein
 * ausdruecklich in Kauf genommener Bruch: Steuer und Zahlung als {@code double},
 * nur die Gutschrift exakt. Er ist aufgeloest, und zwar nach oben - der Saldo
 * ist die Zahl, auf die geschaut wird, und er erbte die Ungenauigkeit der beiden
 * anderen.</p>
 *
 * <p><b>Ueber die Leitung</b> gehen sie als JSON-<em>Zahl</em> hinaus und als
 * <em>Zeichenkette</em> herein. Das ist kein Widerspruch: fuer die Anzeige ist
 * der {@code double} des Browsers bis 10^12 ISK unschaedlich (der Fehler liegt
 * dort bei rund 0,0002 ISK), beim Senden waere der Betrag dagegen schon ungenau,
 * bevor er losgeschickt wird - siehe {@link GrantCreditDto}.</p>
 *
 * <p>Nicht jede Zahl hier ist Geld. {@code volume} ist ein Volumen in m³ und
 * die Rangliste eine Reihenfolge, keine Forderung - beide bleiben
 * {@code double}.</p>
 */
public final class MiningDtos {

    private MiningDtos() {
        throw new AssertionError("Nur ein Namensraum fuer die Antworttypen.");
    }

    /**
     * Ein abgebauter Typ innerhalb eines Abrechnungsmonats.
     *
     * @param volume in m³ und deshalb als {@code double} - kein Geld
     * @param taxToPay der Steueranteil dieses Erzes, auf die zweite Nachkommastelle
     *     gerundet. Die Summe dieser Anteile ist die Monatssteuer, nicht nur
     *     ungefaehr - siehe {@code MiningLedgerService.calculateBill}.
     */
    public record LedgerItemDto(Long typeId, String typeName, String category, long quantity,
                                double volume, BigDecimal jitaPrice, BigDecimal taxToPay) {}

    /**
     * Der Anteil einer einzelnen Gutschrift an einem Monat.
     *
     * <p>Eine Gutschrift ist ein <b>Nachtrag</b> und keine Zuwendung: ein Mitglied
     * hatte bezahlt, die Erkennung hat es nicht mitbekommen, und jemand traegt das
     * von Hand nach. Ein so gedeckter Monat gilt deshalb als bezahlt - und genau
     * deswegen muss er sich von einem unterscheiden lassen, bei dem eine
     * Ueberweisung wirklich erkannt wurde. Ohne diese Zeilen liesse sich spaeter
     * nicht mehr sagen, ob Geld geflossen ist oder ob jemand einen Monat per
     * Eintrag geschlossen hat.</p>
     *
     * <p>Der Nachweis liegt in {@code mining_tax_credits} und wird hier nicht
     * kopiert, sondern <em>erreichbar</em> gemacht: {@code creditId} zeigt auf die
     * Buchung, die restlichen Felder sagen ohne zweiten Abruf, wer sie wann und
     * mit welcher Begruendung angelegt hat.</p>
     *
     * @param applied was von dieser Buchung auf <em>diesen</em> Monat entfaellt -
     *     eine Gutschrift kann sich ueber mehrere Monate verteilen
     * @param amount der volle Betrag der Buchung, damit die Zeile fuer sich
     *     lesbar bleibt: "500 Mio nachgetragen, davon 38 Mio hier"
     */
    public record AppliedCreditDto(Long creditId, BigDecimal applied, BigDecimal amount,
                                   Long actorCharacterId, String actorName, String reason,
                                   Instant occurredAt) {}

    /**
     * Ein Abrechnungsmonat.
     *
     * <p><b>Vier Betraege statt einem</b>, und sie beantworten vier verschiedene
     * Fragen: {@code totalTax} sagt, was der Monat gekostet hat, {@code taxPaid},
     * was davon aus <em>erkannten</em> Ueberweisungen gedeckt ist,
     * {@code creditApplied}, was aus <em>nachgetragenen</em> Gutschriften gedeckt
     * ist, und {@code amountDue}, was jetzt noch zu tun ist. Die letzte Zahl ist
     * die einzige, die zur Handlung auffordert.</p>
     *
     * @param taxPaid der Anteil aus erkannten Zahlungen
     * @param creditApplied der Anteil aus nachgetragenen Gutschriften. Getrennt
     *     ausgewiesen und nicht in {@code taxPaid} eingerechnet - das ist die
     *     einzige Stelle, an der die Herkunft der Deckung noch sichtbar ist.
     * @param isPaid ob der Monat als beglichen gilt, gerechnet aus
     *     {@code taxPaid} <b>plus</b> {@code creditApplied}. Eine Gutschrift
     *     traegt eine Zahlung nach, die stattgefunden hat; ein dadurch gedeckter
     *     Monat als "offen" auszuweisen hiesse, an einer Schuld festzuhalten, die
     *     jemand ausdruecklich fuer beglichen erklaert hat.
     * @param appliedCredits welche Buchungen diesen Monat gedeckt haben, aelteste
     *     zuerst - leer, wenn er allein aus Ueberweisungen bezahlt ist. Der
     *     Unterschied zwischen "hat ueberwiesen" und "wurde nachgetragen" steht
     *     damit nicht nur als Betrag da, sondern mit Nachweis.
     * @param amountDue was nach Zahlungen UND Gutschriften noch zu ueberweisen
     *     ist. Ueber alle Monate summiert ergibt sie genau das Minus aus
     *     {@code currentBalance}, beziehungsweise null, solange der Saldo im
     *     Guthaben liegt.
     */
    public record MonthlyLedgerDto(String month, BigDecimal totalTax, BigDecimal taxPaid,
                                   BigDecimal creditApplied, boolean isPaid, BigDecimal amountDue,
                                   List<AppliedCreditDto> appliedCredits,
                                   List<LedgerItemDto> details) {}

    /**
     * Der Kontostand eines Accounts ueber alle Monate.
     *
     * @param totalCredited die Summe der gueltigen Gutschriften. Sie steht weiter
     *     als eigenes Feld neben dem Saldo - aber nicht mehr, weil nur sie exakt
     *     waere. Inzwischen sind alle vier Groessen exakt; das Feld bleibt, weil
     *     eine Gutschrift etwas anderes ist als eine Zahlung und beides getrennt
     *     lesbar sein muss.
     */
    public record UserLedgerResponse(BigDecimal totalDebt, BigDecimal totalPaid,
                                     BigDecimal totalCredited, BigDecimal currentBalance,
                                     List<MonthlyLedgerDto> months) {}

    /** Eine Zeile der Rangliste - ein Account, also Main samt Alts. */
    public record MiningLeaderRowDto(int rank, Long mainId, String mainName, String portraitUrl,
                                     double volume, double value, long units, boolean isMe) {}

    public record MiningLeaderboardDto(String month, List<String> availableMonths,
                                       double totalVolume, double totalValue,
                                       List<MiningLeaderRowDto> rows) {}

    /**
     * Die Bilanz eines Accounts in der Admin-Uebersicht.
     *
     * @param totalCredited siehe {@link UserLedgerResponse#totalCredited()}
     */
    public record AdminLedgerSummaryDto(Long mainId, String mainName, String portraitUrl,
                                        BigDecimal totalTax, BigDecimal totalPaid,
                                        BigDecimal totalCredited, BigDecimal currentBalance) {}

    /**
     * Die Steuerakte eines einzelnen Members, wie sie die Fuehrung sieht.
     *
     * <p>Sie traegt dieselben {@link MonthlyLedgerDto} wie die Eigensicht des
     * Mitglieds, samt der Aufschluesselung nach Erz in
     * {@link MonthlyLedgerDto#details()} - und zwar absichtlich dieselben und
     * nicht eigene: eine zweite Fassung der Aufschluesselung koennte anders
     * runden oder anders sortieren als die, die das Mitglied vor sich hat, und
     * dann streiten zwei Bildschirme ueber dieselbe Rechnung.</p>
     *
     * <p>Der Unterschied zur Eigensicht ist der Kopf: Name und Portrait des
     * Members stehen dabei, und die Gutschriften liegen als Verlauf bei, nicht
     * nur als Summe.</p>
     */
    public record AdminMemberLedgerDto(Long accountId, String accountName, String portraitUrl,
                                       BigDecimal totalTax, BigDecimal totalPaid,
                                       BigDecimal totalCredited, BigDecimal currentBalance,
                                       List<MonthlyLedgerDto> months, List<TaxCreditDto> credits) {}

    /**
     * Eine Buchung aus dem Gutschriftenverlauf.
     *
     * <p>Namen und Portrait stehen mit drin, obwohl in der Tabelle nur IDs
     * liegen: der Verlauf wird gelesen, um zu verstehen, wer gehandelt hat, und
     * eine Liste aus Zahlen beantwortet das nicht. Ist ein Charakter
     * zwischenzeitlich verschwunden, bleibt die ID die beste Auskunft - genau
     * wie bei {@code RoleAssignmentDtos.RoleAuditDto}.</p>
     *
     * @param amount positiv bei einer Gutschrift, negativ bei einer Gegenbuchung
     * @param status ACTIVE, REVERSED oder REVERSAL - siehe {@code MiningTaxCredit}
     * @param reversalOfCreditId bei einer Gegenbuchung die zurueckgenommene
     *     Buchung, sonst {@code null}. Damit kann die Oberflaeche die beiden
     *     Zeilen nebeneinanderstellen, statt den Zusammenhang aus Betrag und
     *     Zeitpunkt zu erraten.
     * @param selfGranted ob sich der Handelnde selbst bedient hat. Als eigenes
     *     Feld und nicht als Vergleich zweier IDs, damit die Oberflaeche den Fall
     *     hervorheben kann, ohne ihn erst kennen zu muessen.
     * @param occurredAt geht als ISO-8601-Zeichenkette hinaus, damit die
     *     Oberflaeche den Zeitpunkt in der Zone des Betrachters anzeigen kann
     */
    public record TaxCreditDto(Long id, Long accountId, String accountName, String portraitUrl,
                               BigDecimal amount, String status, Long reversalOfCreditId,
                               Long actorCharacterId, String actorName, boolean selfGranted,
                               String reason, Instant occurredAt) {}

    /**
     * Was die Oberflaeche beim Anlegen einer Gutschrift schickt.
     *
     * <p>Der Betrag kommt als <b>Zeichenkette</b> und nicht als Zahl. Das ist
     * kein Versehen: JSON kennt nur einen Zahlentyp, und JavaScript liest ihn als
     * {@code double}. Ein Betrag, den der Browser als Zahl verpackt, ist schon
     * ungenau, bevor er losgeschickt wird - dann nuetzt das exakte
     * {@code numeric(20,2)} in der Datenbank nichts mehr. Als Zeichenkette geht
     * genau das ueber die Leitung, was jemand eingetippt hat, und
     * {@code MiningTaxCreditService} macht daraus einen {@link BigDecimal}.</p>
     *
     * <p>{@code reason} darf fehlen - siehe {@code MiningTaxCredit.reason}.</p>
     */
    public record GrantCreditDto(String amount, String reason) {}

    /** Was die Oberflaeche beim Zuruecknehmen schickt - nur der freiwillige Grund. */
    public record ReverseCreditDto(String reason) {}
}
