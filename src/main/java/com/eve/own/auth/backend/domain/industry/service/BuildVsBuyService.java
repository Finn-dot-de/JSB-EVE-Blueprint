package com.eve.own.auth.backend.domain.industry.service;

import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository.BlueprintInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vergleicht je Bauteil, ob Selbstbauen oder Fertigkaufen guenstiger ist.
 *
 * <p>Die Frage laesst sich nicht pauschal beantworten, und das ist der ganze
 * Grund fuer diese Klasse. Ein Capital Core Temperature Regulator kostet fertig
 * 193,8 Millionen ISK. Selbst gebaut kostet er mit einer unerforschten Blaupause
 * 201,9 Millionen - ein Verlust von acht Millionen. Mit ME 10 kostet er 183,3
 * Millionen und spart zehn. Dieselbe Komponente, entgegengesetzte Antwort,
 * abhaengig allein von der Blaupause im Hangar.</p>
 *
 * <p>Deshalb wird gerechnet und nicht geraten - und deshalb geht die
 * Materialeffizienz der Blaupause <em>dieses</em> Bauteils ein, nicht die des
 * Endprodukts.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuildVsBuyService {

    private final IndustryQueryRepository queryRepo;
    private final IndustryPlanningService planning;

    /**
     * Das Ergebnis des Vergleichs.
     *
     * @param buildCost  Materialkosten samt Jobgebuehr, {@code null} wenn nicht bezifferbar
     * @param buyCost    Fertigkaufpreis, {@code null} wenn kein Marktpreis vorliegt
     * @param buildable  ob es sich ueberhaupt herstellen laesst
     * @param reason     warum kein Vergleich moeglich war, sonst {@code null}
     */
    public record Verdict(long typeId, Double buildCost, Double buyCost,
                          boolean buildable, boolean buildCheaper, String reason) {

        /** Wie viel die guenstigere Wahl spart; 0, wenn sich nichts vergleichen liess. */
        public double saving() {
            if (buildCost == null || buyCost == null) {
                return 0;
            }
            return Math.abs(buyCost - buildCost);
        }
    }

    /**
     * Rechnet fuer ein Bauteil beide Wege durch.
     *
     * @param characterId wessen Blaupausen gelten
     * @param typeId      das Bauteil
     * @param quantity    wie viele Stueck gebraucht werden
     */
    @Transactional(readOnly = true)
    public Verdict compare(Long characterId, long typeId, long quantity) {
        Double kaufen = queryRepo.jitaSell(typeId);
        Double kaufenGesamt = kaufen == null ? null : kaufen * quantity;

        BlueprintInfo bp = queryRepo.blueprintFor(typeId);
        if (bp == null) {
            return new Verdict(typeId, null, kaufenGesamt, false, false,
                    "Lässt sich nicht per Industriejob herstellen.");
        }

        IndustryContext ctx = planning.contextFor(characterId, bp);
        long laeufe = IndustryMath.runsForQuantity(quantity, bp.unitsPerRun());

        double material = 0;
        boolean preisFehlt = false;
        double grundwert = 0;

        for (var kind : queryRepo.billOfMaterials(typeId, 1)) {
            long grundmenge = kind.quantityPerRun();
            long menge = IndustryMath.materialForJob(laeufe, grundmenge, ctx);

            Double preis = queryRepo.jitaSell(kind.typeId());
            if (preis == null) {
                // Ein Bauteil mit unbekanntem Materialpreis laesst sich nicht
                // vergleichen. Es mit null zu bewerten liesse Bauen kuenstlich
                // guenstig aussehen - genau die Richtung, in der ein Fehler teuer wird.
                preisFehlt = true;
                continue;
            }
            material += menge * preis;
            // Die Jobgebuehr haengt an den ME-0-Grundmengen, nicht an den gesparten.
            grundwert += grundmenge * laeufe * preis;
        }

        if (preisFehlt) {
            return new Verdict(typeId, null, kaufenGesamt, true, false,
                    "Für mindestens ein Material fehlt der Marktpreis.");
        }

        double gebuehr = IndustryMath
                .jobCost(java.math.BigDecimal.valueOf(grundwert), ctx)
                .doubleValue();
        double bauen = material + gebuehr;

        if (kaufenGesamt == null) {
            return new Verdict(typeId, bauen, null, true, true,
                    "Kein Marktpreis für das fertige Teil - Bauen ist der einzige bezifferte Weg.");
        }
        return new Verdict(typeId, bauen, kaufenGesamt, true, bauen < kaufenGesamt, null);
    }

    /**
     * Ob ein Bauteil nach der gewaehlten Voreinstellung gebaut werden soll.
     *
     * <p>Bei {@link BuildStrategy#COST_EFFICIENT} entscheidet die Rechnung. Laesst
     * sie sich nicht anstellen - etwa weil ein Materialpreis fehlt - wird
     * gekauft: die sichere Wahl, denn sie braucht weder Blaupause noch Jobslot.</p>
     */
    @Transactional(readOnly = true)
    public boolean shouldBuild(Long characterId, long typeId, long quantity,
                               String sourceKind, BuildStrategy strategy) {
        boolean herstellbar = "BUILDABLE".equals(sourceKind) || "REACTION".equals(sourceKind);
        if (!herstellbar) {
            // Mineralien, PI-Gueter und Gas lassen sich per Industriejob nicht
            // herstellen - dort gibt es nichts zu entscheiden.
            return false;
        }
        return switch (strategy) {
            case BUY_ALL -> false;
            case BUILD_ALL -> true;
            case COST_EFFICIENT -> {
                Verdict urteil = compare(characterId, typeId, quantity);
                yield urteil.buildCost() != null && urteil.buyCost() != null
                        && urteil.buildCheaper();
            }
        };
    }
}
