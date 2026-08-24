package com.eve.own.auth.backend.domain.mining.scheduler;

import com.eve.own.auth.backend.domain.mining.service.MiningLedgerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Stoesst das Einfrieren abgeschlossener Monatsrechnungen an.
 *
 * <p>Das geschah bis hierher als Nebenwirkung von {@code GET /my-ledger} - ein
 * Abruf schrieb eine Rechnung. Die Begruendung, warum das weg musste, steht
 * ausfuehrlich an {@link MiningLedgerService}; kurz: zwei gleichzeitige Leser
 * liefen in eine Eindeutigkeitsbedingung, und der erste Seitenaufruf nach einem
 * Monatswechsel schloss den Vormonat ab, bevor der ESI-Ledger seine letzten
 * Zeilen nachgeliefert hatte.</p>
 *
 * <p>Ein geplanter Lauf hat den Vorteil, den ein Seitenaufruf nie haben kann:
 * er laeuft auch dann, wenn niemand hinsieht. Zuvor blieb ein Monat ungefroren,
 * solange kein Mitglied seine Seite oeffnete - und fehlte damit auch in der
 * Uebersicht der Fuehrung.</p>
 */
@Slf4j
@Component
public class MiningInvoiceFreezeScheduler {

    /**
     * Taeglich. Haeufiger waere sinnlos: die Karenzzeit misst in Tagen, nicht in
     * Stunden, und ein Lauf, der nichts zu tun findet, ist der Normalfall.
     *
     * <p>Als Literal, weil {@code @Scheduled} einen Konstantenausdruck verlangt.</p>
     */
    private static final long INTERVAL_MS = 24 * 60 * 60 * 1000L;

    /**
     * Eine Stunde Vorlauf nach dem Start.
     *
     * <p>Der Charakterabgleich braucht bei mehreren hundert Charakteren Minuten;
     * ein Einfrieren unmittelbar beim Hochfahren rechnete auf dem Stand von
     * gestern. Die Karenzzeit faengt das zwar ohnehin ab - der Vorlauf kostet
     * aber nichts und nimmt die Frage aus der Welt.</p>
     */
    private static final long INITIAL_DELAY_MS = 60 * 60 * 1000L;

    private final MiningLedgerService ledgerService;

    public MiningInvoiceFreezeScheduler(MiningLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @Scheduled(fixedRate = INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    public void freezeDueMonths() {
        try {
            ledgerService.freezeDueMonths();
        } catch (Exception e) {
            // Ein fehlgeschlagener Lauf ist kein Schaden - der naechste holt es
            // nach, und eine nicht eingefrorene Rechnung wird weiter live
            // gerechnet. Nur still verschwinden darf er nicht.
            log.error("Einfrieren der Monatsrechnungen fehlgeschlagen: {}", e.getMessage(), e);
        }
    }
}
