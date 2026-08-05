package com.eve.own.auth.backend.domain.mining.controller;

import com.eve.own.auth.backend.common.AccessRules;
import com.eve.own.auth.backend.common.CurrentUser;
import com.eve.own.auth.backend.domain.mining.dto.MiningDtos;
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxRate;
import com.eve.own.auth.backend.domain.mining.service.MiningLeaderboardService;
import com.eve.own.auth.backend.domain.mining.service.MiningLedgerService;
import com.eve.own.auth.backend.domain.mining.service.MiningTaxRateService;
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
 */
@RestController
@RequestMapping("/api/mining")
public class MiningController {

    private final MiningLedgerService ledgerService;
    private final MiningLeaderboardService leaderboardService;
    private final MiningTaxRateService taxRateService;

    public MiningController(MiningLedgerService ledgerService,
                            MiningLeaderboardService leaderboardService,
                            MiningTaxRateService taxRateService) {
        this.ledgerService = ledgerService;
        this.leaderboardService = leaderboardService;
        this.taxRateService = taxRateService;
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

    /** Setzt denselben Prozentsatz fuer eine ganze Steuerklasse (ORE, ICE, GAS, MOON). */
    @PreAuthorize(AccessRules.LEADERSHIP)
    @PostMapping("/taxes/bulk")
    public ResponseEntity<Void> updateBulkTax(@RequestParam String category,
                                              @RequestParam Double taxPercentage) {
        taxRateService.updateCategory(category, taxPercentage);
        return ResponseEntity.ok().build();
    }

    /** Die Bilanzen aller Accounts, das groesste Minus zuerst. */
    @PreAuthorize(AccessRules.LEADERSHIP_OR_IT)
    @GetMapping("/admin/ledgers")
    public ResponseEntity<List<MiningDtos.AdminLedgerSummaryDto>> getAllLedgersSummary() {
        return ResponseEntity.ok(ledgerService.allAccountSummaries());
    }
}
