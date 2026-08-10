package com.eve.own.auth.backend.domain.industry.service;

import com.eve.own.auth.backend.domain.assets.service.MyAssetService;
import com.eve.own.auth.backend.domain.industry.IndustryActivity;
import com.eve.own.auth.backend.domain.industry.dto.IndustryDtos;
import com.eve.own.auth.backend.domain.industry.entity.CharacterBlueprint;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository.BlueprintInfo;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository.BomNode;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository.Holding;
import com.eve.own.auth.backend.domain.industry.repository.CharacterBlueprintRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rechnet einen Bauwunsch durch: wie viele Jobs, wie lange, was wird gebraucht.
 *
 * <p>Der Dienst legt nichts an. Er beantwortet nur die Frage "was kaeme dabei
 * heraus" - man soll durchrechnen duerfen, ohne sich festzulegen. Das Anlegen
 * und Verfolgen eines Auftrags liegt in {@link IndustryOrderService}.</p>
 *
 * <p>Die Bestaende kommen ueber dieselbe Kontoabgrenzung wie die Assets:
 * {@link MyAssetService#resolveMainId} und {@link MyAssetService#ownCharacterIds}.
 * Damit gilt hier wortgleich, was dort zugesagt ist - niemand sieht fremde
 * Hangars, auch nicht als Zwischensumme in einer Bedarfstabelle.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndustryPlanningService {

    /**
     * Die Herkunftsarten, aus denen sich per Industriejob etwas herstellen laesst.
     *
     * <p>PI-Gueter stehen bewusst nicht dabei. Nanites, Test Cultures und
     * Sterile Conduits entstehen auf Planeten, nicht in einer Fabrik - wer dort
     * "Bauen" anbietet, schickt den Nutzer in eine Sackgasse, aus der ihn erst
     * ein gescheiterter Jobstart wieder herausholt.</p>
     */
    private static final Set<String> BUILDABLE_KINDS = Set.of("BUILDABLE", "REACTION");

    private final IndustryQueryRepository queryRepo;
    private final CharacterBlueprintRepository blueprintRepo;
    private final MyAssetService assetService;

    /** Vorschlagsliste fuer das Suchfeld. */
    @Transactional(readOnly = true)
    public List<IndustryDtos.ProductHitDto> search(String query, int limit) {
        return queryRepo.searchProducts(query, limit).stream()
                .map(h -> new IndustryDtos.ProductHitDto(
                        h.typeId(), h.typeName(), h.groupName(), h.blueprintTypeId()))
                .toList();
    }

    /**
     * Rechnet einen Bauwunsch durch, ohne etwas anzulegen.
     *
     * @param characterId   der angemeldete Charakter - bestimmt, welche Bestaende zaehlen
     * @param depth         wie tief die Stueckliste aufgeloest wird. 1 liefert nur die
     *                      unmittelbaren Materialien; genau das zeigt die Oberflaeche zuerst,
     *                      weil ein Titan sonst mit ueber hundert Zeilen aufschlaegt.
     * @param buildSystemId wo gebaut werden soll. Bestimmt, welches Material als
     *                      vorhanden gilt; {@code null} zaehlt ganz EVE zusammen.
     */
    @Transactional(readOnly = true)
    public IndustryDtos.PlanPreviewDto preview(Long characterId, long productTypeId,
                                               long quantity, int depth, Long buildSystemId) {
        long wanted = Math.max(1, quantity);
        BlueprintInfo bp = queryRepo.blueprintFor(productTypeId);

        if (bp == null) {
            // Kein Bauplan - das kann passieren, wenn jemand eine typeId von Hand
            // eintraegt. Ehrlich leer antworten statt mit Nullen zu tun, als ginge es.
            return new IndustryDtos.PlanPreviewDto(productTypeId, "", wanted,
                    new IndustryDtos.PlanSummaryDto(0, 0, 0, 0, 0, 0, 0, 0, false, false),
                    List.of());
        }

        BlueprintContext bpCtx = blueprintContextFor(characterId, bp);
        IndustryContext ctx = bpCtx.context();
        JobPlan plan = planJobs(bp, wanted, ctx);
        List<BomNode> bom = queryRepo.billOfMaterials(productTypeId, Math.max(1, depth));

        Map<Long, Long> needed = aggregateNeeds(bom, plan, ctx);
        List<IndustryDtos.RequirementDto> rows = enrich(characterId, bom, needed, buildSystemId);

        double volume = packagedVolumeOf(productTypeId) * wanted;
        long directMaterials = bom.stream().filter(n -> n.depth() == 1).count();

        IndustryDtos.PlanSummaryDto summary = new IndustryDtos.PlanSummaryDto(
                plan.split().jobCount(), plan.split().runsPerJob(), plan.split().totalRuns(),
                plan.totalSeconds(), (int) directMaterials, volume,
                ctx.materialEfficiency(), ctx.timeEfficiency(), true, bpCtx.owned());

        return new IndustryDtos.PlanPreviewDto(
                productTypeId, bp.productName(), wanted, summary, rows);
    }

    // ===========================================================
    //  Bausteine, die auch der Auftragsdienst braucht
    // ===========================================================

    /** Die Jobzerlegung samt gerechneter Gesamtdauer. */
    public record JobPlan(IndustryMath.JobSplit split, long totalSeconds, long secondsPerRun) {}

    /**
     * Zerlegt einen Bauwunsch in Jobs.
     *
     * <p>Erst wird ausgerechnet, wie viele <em>Laeufe</em> noetig sind - ein Lauf
     * kann mehr als ein Stueck liefern. Dann greifen die beiden Grenzen: die
     * hoechste Laufzahl der Blaupause und die Dreissig-Tage-Grenze eines Jobs.</p>
     */
    public JobPlan planJobs(BlueprintInfo bp, long wantedUnits, IndustryContext ctx) {
        long runs = IndustryMath.runsForQuantity(wantedUnits, bp.unitsPerRun());
        long secondsPerRun = IndustryMath.productionSeconds(1, bp.secondsPerRun(), ctx);
        IndustryMath.JobSplit split =
                IndustryMath.splitIntoJobs(runs, bp.maxProductionLimit(), secondsPerRun);
        long total = IndustryMath.productionSeconds(split.totalRuns(), bp.secondsPerRun(), ctx);
        return new JobPlan(split, total, secondsPerRun);
    }

    /**
     * Der Rechenrahmen fuer diesen Charakter und diese Blaupause.
     *
     * <p>ME und TE kommen aus der besten Blaupause im Kontoverbund. Findet sich
     * keine, wird mit null gerechnet - das ist die sichere Richtung: der Bedarf
     * faellt dann zu hoch aus, nie zu niedrig.</p>
     *
     * <p>Struktur- und Rig-Boni bleiben in dieser Fassung neutral. Sie haengen
     * am Bauort, und solange keiner gewaehlt ist, waere jede Annahme geraten.
     * Lieber eine ehrliche Obergrenze als eine schmeichelnde Schaetzung.</p>
     */
    public record BlueprintContext(IndustryContext context, boolean owned) {}

    /** Bequemer Zugriff fuer Aufrufer, die nur die Rechenwerte brauchen. */
    @Transactional(readOnly = true)
    public IndustryContext contextFor(Long characterId, BlueprintInfo bp) {
        return blueprintContextFor(characterId, bp).context();
    }

    @Transactional(readOnly = true)
    public BlueprintContext blueprintContextFor(Long characterId, BlueprintInfo bp) {
        int me = 0;
        int te = 0;
        boolean owned = false;
        try {
            Long mainId = assetService.resolveMainId(characterId);
            Set<Long> chars = assetService.ownCharacterIds(mainId);
            List<CharacterBlueprint> found = chars.isEmpty()
                    ? List.of()
                    : blueprintRepo.findBest(chars, bp.blueprintTypeId());
            if (!found.isEmpty()) {
                CharacterBlueprint best = found.getFirst();
                me = best.getMaterialEfficiency();
                te = best.getTimeEfficiency();
                owned = true;
            }
        } catch (IllegalStateException e) {
            // Charakter nicht registriert - mit null Forschung weiterrechnen.
            log.debug("Keine Blaupausendaten für Charakter {}: {}", characterId, e.getMessage());
        }

        return new BlueprintContext(new IndustryContext(
                bp.activityId(), me, te,
                BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE,
                0, 0, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO), owned);
    }

    /**
     * Rechnet die Stueckliste auf den tatsaechlichen Bedarf hoch.
     *
     * <p>Die erste Ebene laeuft ueber die Jobrechnung, damit Rundung je Job und
     * Mindestmenge je Lauf greifen. Tiefere Ebenen werden proportional
     * hochgerechnet und aufgerundet - dort ist noch gar nicht entschieden, ob
     * ueberhaupt gebaut wird, und eine Jobrechnung wuerde eine Genauigkeit
     * vortaeuschen, die es an der Stelle nicht gibt.</p>
     */
    private Map<Long, Long> aggregateNeeds(List<BomNode> bom, JobPlan plan, IndustryContext ctx) {
        Map<Long, Long> needs = new LinkedHashMap<>();
        for (BomNode node : bom) {
            long menge;
            if (node.depth() == 1) {
                // Die Menge je Lauf, nicht die je Stueck: bei einer Blaupause,
                // die mehrere Stueck liefert, unterscheiden sich beide um genau
                // diesen Faktor.
                menge = IndustryMath.materialForOrder(plan.split(), node.quantityPerRun(), ctx);
            } else {
                menge = (long) Math.ceil(node.quantityPerUnit() * plan.split().totalRuns());
            }
            needs.merge(node.typeId(), menge, Long::sum);
        }
        return needs;
    }

    /** Haengt Bestand, Herkunft und Volumen an die Bedarfszeilen. */
    private List<IndustryDtos.RequirementDto> enrich(Long characterId, List<BomNode> bom,
                                                     Map<Long, Long> needed, Long buildSystemId) {
        if (bom.isEmpty()) {
            return List.of();
        }
        Map<Long, Holding> bestand = holdingsFor(characterId, needed.keySet(), buildSystemId);
        Map<Long, Double> volumen = volumesFor(needed.keySet());

        List<IndustryDtos.RequirementDto> rows = new ArrayList<>(bom.size());
        for (BomNode node : bom) {
            long braucht = needed.getOrDefault(node.typeId(), 0L);
            Holding hat = bestand.get(node.typeId());
            long vorhanden = hat == null ? 0 : hat.quantity();

            rows.add(new IndustryDtos.RequirementDto(
                    node.typeId(), node.typeName(), braucht, vorhanden,
                    Math.max(0, braucht - vorhanden),
                    node.sourceKind(),
                    BUILDABLE_KINDS.contains(node.sourceKind()),
                    "BUY",
                    node.depth(), node.parentTypeId(),
                    null, true,
                    volumen.getOrDefault(node.typeId(), 0.0),
                    hat == null ? 0 : hat.onCharacters(),
                    hat == null ? 0 : hat.elsewhere()));
        }
        return rows;
    }

    /**
     * Der Bestand des Kontos zu den genannten Typen, getrennt nach Bausystem.
     *
     * @param buildSystemId wo gebaut wird; {@code null} zaehlt ganz EVE als "vor Ort"
     */
    @Transactional(readOnly = true)
    public Map<Long, Holding> holdingsFor(Long characterId, Set<Long> typeIds,
                                         Long buildSystemId) {
        try {
            Long mainId = assetService.resolveMainId(characterId);
            Set<Long> chars = assetService.ownCharacterIds(mainId);
            Map<Long, Holding> map = new HashMap<>();
            for (Holding h : queryRepo.holdings(chars, typeIds, buildSystemId)) {
                map.put(h.typeId(), h);
            }
            return map;
        } catch (IllegalStateException e) {
            log.debug("Kein Bestand für Charakter {}: {}", characterId, e.getMessage());
            return Map.of();
        }
    }

    private Map<Long, Double> volumesFor(Set<Long> typeIds) {
        return queryRepo.packagedVolumes(typeIds);
    }

    private double packagedVolumeOf(long typeId) {
        return volumesFor(Set.of(typeId)).getOrDefault(typeId, 0.0);
    }

    /** Fuer die Anzeige: der lesbare Name einer Aktivitaet. */
    public String activityLabel(Integer sdeActivityId) {
        return IndustryActivity.label(sdeActivityId);
    }
}
