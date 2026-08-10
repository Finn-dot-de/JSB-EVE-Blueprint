package com.eve.own.auth.backend.domain.industry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.industry.IndustryActivity;
import com.eve.own.auth.backend.domain.industry.entity.IndustryJob;
import com.eve.own.auth.backend.domain.industry.entity.IndustryOrder;
import com.eve.own.auth.backend.domain.industry.entity.IndustryOrderJob;
import com.eve.own.auth.backend.domain.industry.repository.IndustryOrderBaselineRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryJobRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryOrderJobRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryOrderRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Die Zuordnung von Jobs zu Auftraegen.
 *
 * <p>Der Kern ist die Zeitbedingung: ein Job, der schon vor der Auftragsanlage
 * lief, darf nicht gutgeschrieben werden. Sonst faengt der Fortschritt bei einem
 * Wert an, den niemand erarbeitet hat.</p>
 */
class IndustryAttributionServiceTest {

    private static final long ACCOUNT = 100L;
    private static final long RAVEN = 638L;
    private static final Instant ANGELEGT = Instant.parse("2026-08-01T12:00:00Z");

    private IndustryOrderRepository orderRepo;
    private IndustryOrderJobRepository orderJobRepo;
    private IndustryJobRepository jobRepo;
    private IndustryOrderBaselineRepository baselineRepo;
    private IndustryAttributionService service;

    @BeforeEach
    void setUp() {
        orderRepo = Mockito.mock(IndustryOrderRepository.class);
        orderJobRepo = Mockito.mock(IndustryOrderJobRepository.class);
        jobRepo = Mockito.mock(IndustryJobRepository.class);
        baselineRepo = Mockito.mock(IndustryOrderBaselineRepository.class);
        service = new IndustryAttributionService(orderRepo, orderJobRepo, jobRepo, baselineRepo);

        when(orderJobRepo.findById(anyLong())).thenReturn(Optional.empty());
    }

    private static IndustryOrder auftrag() {
        IndustryOrder o = new IndustryOrder();
        o.setId(1L);
        o.setAccountId(ACCOUNT);
        o.setProductTypeId(RAVEN);
        o.setTargetQuantity(50L);
        o.setStatus("ACTIVE");
        o.setCreatedAt(ANGELEGT);
        return o;
    }

    private static IndustryJob job(long id, String status, Instant start, int runs) {
        IndustryJob j = new IndustryJob();
        j.setJobId(id);
        j.setStatus(status);
        j.setStartDate(start);
        j.setRuns(runs);
        j.setProductTypeId(RAVEN);
        j.setActivityIdSde(IndustryActivity.MANUFACTURING);
        return j;
    }

    @Test
    @DisplayName("rechnet einen gelieferten Job dem Auftrag zu")
    void ordnetGelieferteJobsZu() {
        IndustryJob fertig = job(9001L, "delivered", ANGELEGT.plus(1, ChronoUnit.HOURS), 10);
        fertig.setSuccessfulRuns(10);
        when(orderRepo.findByAccountIdAndStatus(ACCOUNT, "ACTIVE")).thenReturn(List.of(auftrag()));
        when(jobRepo.findForProduct(anyCollection(), any())).thenReturn(List.of(fertig));

        int geaendert = service.attribute(ACCOUNT, Set.of(1L, 2L));

        assertThat(geaendert).isEqualTo(1);
        ArgumentCaptor<List<IndustryOrderJob>> captor = ArgumentCaptor.captor();
        verify(orderJobRepo).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement()
                .satisfies(z -> {
                    assertThat(z.getOrderId()).isEqualTo(1L);
                    assertThat(z.getUnitsProduced()).isEqualTo(10);
                    assertThat(z.getAttributedBy()).isEqualTo("AUTO");
                });
    }

    @Test
    @DisplayName("übergeht einen Job, der vor der Auftragsanlage begann")
    void uebergehtAeltereJobs() {
        // Genau der Fall, der den Fortschritt sonst geschenkt bekäme.
        IndustryJob alt = job(9002L, "delivered", ANGELEGT.minus(2, ChronoUnit.DAYS), 10);
        alt.setSuccessfulRuns(10);
        when(orderRepo.findByAccountIdAndStatus(ACCOUNT, "ACTIVE")).thenReturn(List.of(auftrag()));
        when(jobRepo.findForProduct(anyCollection(), any())).thenReturn(List.of(alt));

        int geaendert = service.attribute(ACCOUNT, Set.of(1L));

        assertThat(geaendert).isZero();
    }

    @Test
    @DisplayName("zählt einen laufenden Job mit null Stück")
    void laufendeJobsZaehlenNochNicht() {
        // Der Job steht im Ofen. Er ist zugeordnet, hat aber nichts hergestellt -
        // ein Fortschritt, der Ofeninhalt mitzählt, verspricht zu viel.
        IndustryJob laeuft = job(9003L, "active", ANGELEGT.plus(1, ChronoUnit.HOURS), 10);
        when(orderRepo.findByAccountIdAndStatus(ACCOUNT, "ACTIVE")).thenReturn(List.of(auftrag()));
        when(jobRepo.findForProduct(anyCollection(), any())).thenReturn(List.of(laeuft));

        service.attribute(ACCOUNT, Set.of(1L));

        ArgumentCaptor<List<IndustryOrderJob>> captor = ArgumentCaptor.captor();
        verify(orderJobRepo).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement()
                .satisfies(z -> assertThat(z.getUnitsProduced()).isZero());
    }

    @Test
    @DisplayName("nimmt eine Forschungsaktivität nicht als Fertigung")
    void nurErzeugendeAktivitaetenZaehlen() {
        IndustryJob forschung = job(9004L, "delivered", ANGELEGT.plus(1, ChronoUnit.HOURS), 1);
        forschung.setActivityIdSde(IndustryActivity.MATERIAL_EFFICIENCY_RESEARCH);
        when(orderRepo.findByAccountIdAndStatus(ACCOUNT, "ACTIVE")).thenReturn(List.of(auftrag()));
        when(jobRepo.findForProduct(anyCollection(), any())).thenReturn(List.of(forschung));

        assertThat(service.attribute(ACCOUNT, Set.of(1L))).isZero();
    }

    @Test
    @DisplayName("lässt eine Zuordnung von Hand unangetastet")
    void behaeltHandzuordnungen() {
        IndustryJob fertig = job(9005L, "delivered", ANGELEGT.plus(1, ChronoUnit.HOURS), 10);
        fertig.setSuccessfulRuns(10);

        IndustryOrderJob vonHand = new IndustryOrderJob();
        vonHand.setJobId(9005L);
        vonHand.setOrderId(1L);
        vonHand.setAttributedBy("MANUAL");
        vonHand.setAttributedAt(ANGELEGT);
        when(orderJobRepo.findById(9005L)).thenReturn(Optional.of(vonHand));
        when(orderRepo.findByAccountIdAndStatus(ACCOUNT, "ACTIVE")).thenReturn(List.of(auftrag()));
        when(jobRepo.findForProduct(anyCollection(), any())).thenReturn(List.of(fertig));

        service.attribute(ACCOUNT, Set.of(1L));

        ArgumentCaptor<List<IndustryOrderJob>> captor = ArgumentCaptor.captor();
        verify(orderJobRepo).saveAll(captor.capture());
        // Die Kennzeichnung bleibt MANUAL - sonst nähme die Automatik dem Nutzer
        // im nächsten Durchlauf seine Korrektur wieder weg.
        assertThat(captor.getValue()).singleElement()
                .satisfies(z -> {
                    assertThat(z.getAttributedBy()).isEqualTo("MANUAL");
                    assertThat(z.getUnitsProduced()).isEqualTo(10);
                });
    }

    @Test
    @DisplayName("bindet einen Job an den gewählten Bauort")
    void achtetAufDenBauort() {
        IndustryOrder mitOrt = auftrag();
        mitOrt.setBuildLocationId(60003760L);

        IndustryJob woanders = job(9006L, "delivered", ANGELEGT.plus(1, ChronoUnit.HOURS), 10);
        woanders.setFacilityId(1234L);
        when(orderRepo.findByAccountIdAndStatus(ACCOUNT, "ACTIVE")).thenReturn(List.of(mitOrt));
        when(jobRepo.findForProduct(anyCollection(), any())).thenReturn(List.of(woanders));

        assertThat(service.attribute(ACCOUNT, Set.of(1L))).isZero();
    }

    @Test
    @DisplayName("tut nichts ohne laufende Aufträge")
    void ohneAuftraegeNichts() {
        when(orderRepo.findByAccountIdAndStatus(ACCOUNT, "ACTIVE")).thenReturn(List.of());

        assertThat(service.attribute(ACCOUNT, Set.of(1L))).isZero();
        verify(jobRepo, never()).findForProduct(anyCollection(), any());
    }

    @Test
    @DisplayName("tut nichts ohne Charaktere")
    void ohneCharaktereNichts() {
        assertThat(service.attribute(ACCOUNT, Set.of())).isZero();
        assertThat(service.attribute(ACCOUNT, null)).isZero();
        verify(orderRepo, never()).findByAccountIdAndStatus(anyLong(), any());
    }
}
