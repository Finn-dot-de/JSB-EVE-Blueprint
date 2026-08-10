package com.eve.own.auth.backend.domain.industry.service;

import com.eve.own.auth.backend.domain.industry.entity.IndustryJob;
import com.eve.own.auth.backend.domain.industry.entity.IndustryOrder;
import com.eve.own.auth.backend.domain.industry.entity.IndustryOrderBaseline;
import com.eve.own.auth.backend.domain.industry.entity.IndustryOrderJob;
import com.eve.own.auth.backend.domain.industry.repository.IndustryOrderBaselineRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryJobRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryOrderJobRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryOrderRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ordnet Industriejobs den Auftraegen zu.
 *
 * <p>Diese Verbindung liefert ESI nicht - dort ist ein Job nur ein Job, ohne
 * jeden Bezug zu einer Absicht. Sie muss also hergestellt werden, und zwar so,
 * dass sie nachvollziehbar bleibt.</p>
 *
 * <p>Die Regel ist bewusst eng: ein Job zaehlt auf einen Auftrag ein, wenn er
 * dasselbe Produkt herstellt, einem Charakter desselben Kontos gehoert, der
 * Auftrag laeuft und der Job nach der Auftragsanlage begonnen wurde. Das letzte
 * Kriterium ist das wichtigste - ohne es wuerde ein Job, der schon vorher lief,
 * dem neuen Auftrag gutgeschrieben, und der Fortschritt begaenne bei einem Wert,
 * den niemand erarbeitet hat.</p>
 *
 * <p>Eine von Hand gesetzte Zuordnung wird nie ueberschrieben. Wer korrigiert,
 * soll seine Korrektur behalten.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndustryAttributionService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String BY_AUTO = "AUTO";
    private static final String BY_MANUAL = "MANUAL";

    private final IndustryOrderRepository orderRepo;
    private final IndustryOrderJobRepository orderJobRepo;
    private final IndustryJobRepository jobRepo;
    private final IndustryOrderBaselineRepository baselineRepo;

    /**
     * Ordnet die Jobs eines Kontos seinen laufenden Auftraegen zu.
     *
     * @param characterIds alle Charaktere des Kontos
     * @param accountId    das Konto
     * @return wie viele Zuordnungen neu entstanden oder aktualisiert wurden
     */
    @Transactional
    public int attribute(Long accountId, Set<Long> characterIds) {
        if (characterIds == null || characterIds.isEmpty()) {
            return 0;
        }
        List<IndustryOrder> offen = orderRepo.findByAccountIdAndStatus(accountId, STATUS_ACTIVE);
        if (offen.isEmpty()) {
            return 0;
        }

        int geaendert = 0;
        for (IndustryOrder order : offen) {
            geaendert += attributeOne(order, characterIds);
        }
        return geaendert;
    }

    /** Die Zuordnung fuer einen einzelnen Auftrag. */
    private int attributeOne(IndustryOrder order, Set<Long> characterIds) {
        List<IndustryJob> passende =
                jobRepo.findForProduct(characterIds, order.getProductTypeId());
        if (passende.isEmpty()) {
            return 0;
        }

        List<IndustryOrderJob> zuSpeichern = new ArrayList<>();
        Instant jetzt = Instant.now();

        for (IndustryJob job : passende) {
            if (!belongsTo(job, order)) {
                continue;
            }
            Optional<IndustryOrderJob> vorhanden = orderJobRepo.findById(job.getJobId());
            if (vorhanden.isPresent() && BY_MANUAL.equals(vorhanden.get().getAttributedBy())) {
                // Eine Korrektur von Hand bleibt stehen - nur die Stueckzahl wird
                // nachgefuehrt, falls der Job inzwischen geliefert wurde.
                IndustryOrderJob manuell = vorhanden.get();
                if (!manuell.getOrderId().equals(order.getId())) {
                    continue;
                }
                manuell.setUnitsProduced(producedUnits(job));
                zuSpeichern.add(manuell);
                continue;
            }

            IndustryOrderJob zeile = vorhanden.orElseGet(IndustryOrderJob::new);
            zeile.setJobId(job.getJobId());
            zeile.setOrderId(order.getId());
            zeile.setAttributedBy(BY_AUTO);
            zeile.setAttributedAt(zeile.getAttributedAt() == null ? jetzt : zeile.getAttributedAt());
            zeile.setUnitsProduced(producedUnits(job));
            zuSpeichern.add(zeile);
        }

        orderJobRepo.saveAll(zuSpeichern);
        return zuSpeichern.size();
    }

    /**
     * Ob ein Job zu einem Auftrag gehoert.
     *
     * <p>Die Zeitbedingung ist der Kern: ein Job, der vor der Auftragsanlage
     * begonnen wurde, gehoert nicht dazu - egal wie gut Produkt und Ort passen.
     * Sonst faengt der Fortschritt bei einem geschenkten Wert an.</p>
     */
    private boolean belongsTo(IndustryJob job, IndustryOrder order) {
        if (job.getActivityIdSde() == null
                || !com.eve.own.auth.backend.domain.industry.IndustryActivity
                        .producesItems(job.getActivityIdSde())) {
            return false;
        }
        if (order.getCreatedAt() != null && job.getStartDate() != null
                && job.getStartDate().isBefore(order.getCreatedAt())) {
            return false;
        }
        // Steht ein Bauort fest, muss der Job auch dort laufen.
        return order.getBuildLocationId() == null
                || order.getBuildLocationId().equals(job.getFacilityId());
    }

    /**
     * Wie viele Stueck ein Job zum Auftrag beigetragen hat.
     *
     * <p>Nur gelieferte Jobs zaehlen. Ein laufender Job hat noch nichts
     * produziert - er steht im Ofen, und ein Fortschritt, der Ofeninhalt
     * mitzaehlt, verspricht zu viel.</p>
     */
    private long producedUnits(IndustryJob job) {
        if (!IndustrySyncService.isDelivered(job)) {
            return 0;
        }
        Integer erfolgreich = job.getSuccessfulRuns();
        int laeufe = erfolgreich != null ? erfolgreich : job.getRuns();
        return Math.max(0, laeufe);
    }

    /**
     * Setzt eine Zuordnung von Hand.
     *
     * <p>Nach diesem Aufruf laesst die Automatik den Job in Ruhe.</p>
     */
    @Transactional
    public void assignManually(Long jobId, Long orderId) {
        IndustryOrderJob zeile = orderJobRepo.findById(jobId).orElseGet(IndustryOrderJob::new);
        zeile.setJobId(jobId);
        zeile.setOrderId(orderId);
        zeile.setAttributedBy(BY_MANUAL);
        zeile.setAttributedAt(Instant.now());
        zeile.setUnitsProduced(jobRepo.findById(jobId).map(this::producedUnits).orElse(0L));
        orderJobRepo.save(zeile);
    }

    /**
     * Zieht die Nullmessung von einer gezaehlten Menge ab.
     *
     * <p>Wird gebraucht, wenn der Fortschritt aus Bestaenden statt aus dem
     * Jobbuch abgeleitet werden muss - etwa bei einem Auftrag, dessen Jobs ein
     * anderer Charakter gestartet hat, den das Konto nicht sieht.</p>
     */
    @Transactional(readOnly = true)
    public long minusBaseline(Long orderId, Long typeId, long counted) {
        return baselineRepo.findByOrderIdAndTypeId(orderId, typeId)
                .map(IndustryOrderBaseline::getQuantityAtStart)
                .map(start -> Math.max(0, counted - start))
                .orElse(counted);
    }
}
