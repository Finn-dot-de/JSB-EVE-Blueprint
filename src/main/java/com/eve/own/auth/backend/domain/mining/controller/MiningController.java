package com.eve.own.auth.backend.domain.mining.controller;

import com.eve.own.auth.backend.common.AccessRules;
import com.eve.own.auth.backend.common.CurrentUser;
import com.eve.own.auth.backend.domain.mining.dto.MiningDtos;
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxRate;
import com.eve.own.auth.backend.domain.mining.service.MiningLeaderboardService;
import com.eve.own.auth.backend.domain.mining.service.MiningLedgerService;
import com.eve.own.auth.backend.domain.mining.service.MiningTaxCreditService;
import com.eve.own.auth.backend.domain.mining.service.MiningTaxRateService;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Die Endpunkte rund um Mining-Steuern und -Rangliste.
 *
 * <p>Der Controller nimmt entgegen, prueft Berechtigungen und gibt zurueck -
 * gerechnet wird in den Services. Zuvor lag die vollstaendige Steuerberechnung
 * samt Snapshot-Verwaltung in dieser Klasse.</p>
 *
 * <p>Die {@code @PreAuthorize}-Ausdruecke stehen weiter an jedem Admin-Endpunkt,
 * aber die Dienste dahinter pruefen denselben Kreis noch einmal selbst (siehe
 * {@code MiningAdminGuard}). Das ist keine Doppelung aus Unsicherheit: die
 * Annotation gehoert zu <em>diesem</em> Einstiegspunkt und faellt bei einem
 * Umbau lautlos weg. Bei den Gutschriften haengt daran, wer wieviel ISK bekommt
 * - das ist die gefaehrlichste Stelle dieses Controllers.</p>
 *
 * <p>Der Handelnde kommt bei jedem schreibenden Aufruf aus
 * {@link CurrentUser} und nie aus dem Rumpf: sonst schriebe der Aufrufer den
 * Nachweis ueber sich selbst.</p>
 */
@RestController
@RequestMapping("/api/mining")
public class MiningController {

    private final MiningLedgerService ledgerService;
    private final MiningLeaderboardService leaderboardService;
    private final MiningTaxRateService taxRateService;
    private final MiningTaxCreditService creditService;

    public MiningController(MiningLedgerService ledgerService,
                            MiningLeaderboardService leaderboardService,
                            MiningTaxRateService taxRateService,
                            MiningTaxCreditService creditService) {
        this.ledgerService = ledgerService;
        this.leaderboardService = leaderboardService;
        this.taxRateService = taxRateService;
        this.creditService = creditService;
    }

    /** Die eigene Steuerbilanz ueber alle Monate. */
    @GetMapping("/my-ledger")
    public ResponseEntity<MiningDtos.UserLedgerResponse> getMyLedger() {
        return ResponseEntity.ok(ledgerService.ledgerOf(CurrentUser.characterId()));
    }

    /**
     * Die Mining-Rangliste.
     *
     * @param month "YYYY-MM" oder "ALL"; ohne Angabe der neueste Monat mit Daten
     */
    @GetMapping("/leaderboard")
    public ResponseEntity<MiningDtos.MiningLeaderboardDto> getLeaderboard(
            @RequestParam(required = false) String month) {
        return ResponseEntity.ok(leaderboardService.leaderboard(month, CurrentUser.characterId()));
    }

    // ==================================================================
    // Verwaltung der Steuersaetze
    // ==================================================================

    @PreAuthorize(AccessRules.LEADERSHIP)
    @GetMapping("/taxes")
    public ResponseEntity<List<MiningTaxRate>> getTaxRates() {
        return ResponseEntity.ok(taxRateService.findAll());
    }

    @PreAuthorize(AccessRules.LEADERSHIP)
    @PostMapping("/taxes")
    public ResponseEntity<MiningTaxRate> saveTaxRate(@RequestBody MiningTaxRate rate) {
        return ResponseEntity.ok(taxRateService.save(rate));
    }

    @PreAuthorize(AccessRules.LEADERSHIP)
    @DeleteMapping("/taxes/{typeId}")
    public ResponseEntity<Void> deleteTaxRate(@PathVariable Long typeId) {
        taxRateService.delete(typeId);
        return ResponseEntity.ok().build();
    }

    /**
     * Setzt denselben Prozentsatz fuer eine ganze Steuerklasse (ORE, ICE, GAS, MOON).
     *
     * <p>Der Satz kommt als {@link BigDecimal} an und nicht als {@code Double}:
     * er wird mit jeder abgebauten Menge multipliziert, und {@code 10.0/100} ist
     * in einem {@code double} nicht exakt. Spring wandelt den Parameter aus dem
     * uebertragenen Text um, es geht also unterwegs keine Stelle verloren.</p>
     */
    @PreAuthorize(AccessRules.LEADERSHIP)
    @PostMapping("/taxes/bulk")
    public ResponseEntity<Void> updateBulkTax(@RequestParam String category,
                                              @RequestParam BigDecimal taxPercentage) {
        taxRateService.updateCategory(category, taxPercentage);
        return ResponseEntity.ok().build();
    }

    // ==================================================================
    // Einsicht der Fuehrung
    // ==================================================================

    /**
     * Die Steuerbilanz aller Accounts, das groesste Minus zuerst.
     *
     * <p>Seit die Gutschriften existieren, traegt jede Zeile vier Groessen statt
     * dreien: Schuld, Zahlung, Gutschrift und den Saldo daraus.</p>
     */
    @PreAuthorize(AccessRules.LEADERSHIP_OR_IT)
    @GetMapping("/admin/ledgers")
    public ResponseEntity<List<MiningDtos.AdminLedgerSummaryDto>> getAllLedgersSummary() {
        return ResponseEntity.ok(ledgerService.allAccountSummaries(CurrentUser.characterId()));
    }

    /**
     * Die Steuerakte eines einzelnen Members: welche Erze er geschuerft hat, mit
     * Menge und Steueranteil je Monat, dazu sein Gutschriftenverlauf.
     *
     * <p>Das ist der Klick auf eine Zeile der Uebersicht. {@code accountId} ist
     * deshalb die {@code mainId} von dort.</p>
     *
     * <p>Hier stand zuvor ein {@code /admin/member/mining/ore/composition}, das
     * sein eigenes {@code ResponseEntity} auf eine {@code List} umbog. Der Cast
     * uebersetzte, warf aber bei jedem Aufruf eine {@code ClassCastException} -
     * es gab keinen Test, der ihn je ausgefuehrt haette. Ersatzlos entfernt: die
     * Aufschluesselung liefert dieser Endpunkt, und zwar aus derselben Rechnung
     * wie die Eigensicht des Mitglieds.</p>
     */
    @PreAuthorize(AccessRules.LEADERSHIP_OR_IT)
    @GetMapping("/admin/ledgers/{accountId}")
    public ResponseEntity<MiningDtos.AdminMemberLedgerDto> getMemberLedger(
            @PathVariable Long accountId) {
        return ResponseEntity.ok(ledgerService.memberLedger(CurrentUser.characterId(), accountId));
    }

    // ==================================================================
    // Steuergutschriften
    // ==================================================================

    /**
     * Schreibt einem Member einen Betrag gut.
     *
     * <p>Der schaerfste Endpunkt dieser Klasse: hier entscheidet ein Mensch,
     * wieviel ISK ein anderer bekommt. Der Betrag geht als Zeichenkette ueber die
     * Leitung, damit unterwegs keine Stelle verloren geht - die Begruendung steht
     * an {@code MiningDtos.GrantCreditDto}.</p>
     *
     * @return die geschriebene Buchung samt Nachweis
     */
    @PreAuthorize(AccessRules.LEADERSHIP_OR_IT)
    @PostMapping("/admin/credits/accounts/{accountId}")
    public ResponseEntity<MiningDtos.TaxCreditDto> grantCredit(
            @PathVariable Long accountId,
            @RequestBody MiningDtos.GrantCreditDto dto) {
        return ResponseEntity.ok(creditService.grant(
                CurrentUser.characterId(), accountId, dto.amount(), dto.reason()));
    }

    /**
     * Nimmt eine Gutschrift zurueck.
     *
     * <p>{@code POST .../reverse} und nicht {@code DELETE}, aus zwei Gruenden.
     * Erstens geloescht wird nichts - es entsteht eine Gegenbuchung, und die
     * urspruengliche Zeile bleibt lesbar stehen. Zweitens traegt der Vorgang
     * einen freiwilligen Grund im Rumpf, und ein {@code DELETE} mit Rumpf ist
     * bestenfalls geduldet; in der Adresszeile haette der Grund nichts verloren,
     * dort landet er in jedem Zugriffsprotokoll. Dieselbe Ueberlegung wie beim
     * {@code RoleAssignmentController}.</p>
     *
     * @return die Gegenbuchung
     */
    @PreAuthorize(AccessRules.LEADERSHIP_OR_IT)
    @PostMapping("/admin/credits/{creditId}/reverse")
    public ResponseEntity<MiningDtos.TaxCreditDto> reverseCredit(
            @PathVariable Long creditId,
            @RequestBody(required = false) MiningDtos.ReverseCreditDto dto) {
        return ResponseEntity.ok(creditService.reverse(CurrentUser.characterId(), creditId,
                dto != null ? dto.reason() : null));
    }

    /** Der Gutschriftenverlauf eines Accounts, das Juengste zuerst. */
    @PreAuthorize(AccessRules.LEADERSHIP_OR_IT)
    @GetMapping("/admin/credits/accounts/{accountId}")
    public ResponseEntity<List<MiningDtos.TaxCreditDto>> getCreditsFor(
            @PathVariable Long accountId) {
        return ResponseEntity.ok(creditService.historyFor(CurrentUser.characterId(), accountId));
    }

    /** Die juengsten Buchungen ueber alle Accounts - der Blick von oben. */
    @PreAuthorize(AccessRules.LEADERSHIP_OR_IT)
    @GetMapping("/admin/credits")
    public ResponseEntity<List<MiningDtos.TaxCreditDto>> getRecentCredits() {
        return ResponseEntity.ok(creditService.recentHistory(CurrentUser.characterId()));
    }
}
