package com.eve.own.auth.backend.domain.mining.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.eve.own.auth.backend.domain.mining.service.MiningLedgerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Geplantes Einfrieren der Monatsrechnungen")
class MiningInvoiceFreezeSchedulerTest {

    @Mock private MiningLedgerService ledgerService;

    @Test
    @DisplayName("stoesst den Lauf an")
    void triggersTheRun() {
        new MiningInvoiceFreezeScheduler(ledgerService).freezeDueMonths();

        verify(ledgerService).freezeDueMonths();
    }

    @Test
    @DisplayName("laesst einen fehlgeschlagenen Lauf den Zeitplan nicht abwuergen")
    void survivesAFailedRun() {
        // OHNE DIESE REGEL beendet eine durchgereichte Ausnahme die geplante
        // Aufgabe dauerhaft: Springs Scheduler startet eine Methode, die geworfen
        // hat, bei fixedRate nicht neu ein. Danach wuerde nie wieder etwas
        // eingefroren, und weil eine nicht eingefrorene Rechnung weiter live
        // gerechnet wird, faellt genau das niemandem auf.
        doThrow(new IllegalStateException("Datenbank weg")).when(ledgerService).freezeDueMonths();

        assertThatCode(() -> new MiningInvoiceFreezeScheduler(ledgerService).freezeDueMonths())
                .doesNotThrowAnyException();
    }
}
