package com.eve.own.auth.backend.domain.industry.service;

import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository.BlueprintInfo;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
     * Die guenstigsten Kosten einer Einheit, <b>rekursiv</b> und samt Transport.
     *
     * <p>Das ist der Kern der Voreinstellung "moeglichst guenstig", und er war
     * lange falsch. Zuvor wurde nur eine Ebene tief verglichen: die Zutaten eines
     * Bauteils gingen mit ihrem <em>Kaufpreis</em> in die Rechnung, die Frage
     * "was, wenn ich die Zutat auch baue" wurde nie gestellt. Ein Bauteil, dessen
     * Zutaten teuer zu kaufen und billig zu bauen sind, landete damit auf
     * "Kaufen" - und "alles selbst bauen" kam guenstiger heraus als "moeglichst
     * guenstig". Genau so wurde es gemeldet.</p>
     *
     * <p>Der zweite Teil ist die <b>Fracht</b>. Ein fertiges Capital-Bauteil ist
     * gewaltig, seine Zutaten sind es nicht: bei einem echten Auftrag standen
     * 1.461.555 Kubikmeter gegen 90.160, also 672 Millionen Transportkosten
     * gegen 41. Ein Vergleich, der nur die Ware ansieht, uebersieht mehr, als er
     * entscheidet.</p>
     *
     * @param memo   Zwischenspeicher je Typ - ohne ihn waechst die Rekursion
     *               ueber einen Titan ins Unermessliche
     * @param pfad   Zyklusschutz: zwei Blaupausen, die einander als Material
     *               fuehren, kommen in fremden Stammdaten vor
     * @return Kosten je Stueck einschliesslich Fracht, oder {@code null} wenn
     *         sich weder kaufen noch bauen beziffern laesst
     */
    private Double unitCost(Long characterId, long typeId, double freightPerCubicMeter,
                            Map<Long, Double> memo, Set<Long> pfad) {
        Double gemerkt = memo.get(typeId);
        if (gemerkt != null) {
            return gemerkt;
        }
        Double ergebnis = kaufenJeStueck(typeId, freightPerCubicMeter);

        BlueprintInfo bp = queryRepo.blueprintFor(typeId);
        if (bp != null && pfad.add(typeId)) {
            Double bauen = bauenJeStueck(characterId, bp, freightPerCubicMeter, memo, pfad);
            pfad.remove(typeId);
            if (bauen != null && (ergebnis == null || bauen < ergebnis)) {
                ergebnis = bauen;
            }
        }
        if (ergebnis != null) {
            memo.put(typeId, ergebnis);
        }
        return ergebnis;
    }

    /** Was ein Stueck fertig gekauft kostet, an den Bauort geliefert. */
    private Double kaufenJeStueck(long typeId, double freightPerCubicMeter) {
        Double preis = queryRepo.jitaSell(typeId);
        if (preis == null) {
            return null;
        }
        double volumen = queryRepo.packagedVolumes(Set.of(typeId)).getOrDefault(typeId, 0.0);
        return preis + volumen * freightPerCubicMeter;
    }

    /**
     * Was ein Stueck kostet, wenn man es selbst baut - mit dem jeweils
     * guenstigsten Weg fuer jede Zutat.
     *
     * <p>Gerechnet wird je Lauf und danach auf das Stueck heruntergebrochen. Die
     * Rundung der Materialmengen geschieht je Job, nicht je Stueck; bei den hier
     * ueblichen Laufzahlen ist der Unterschied klein gegen den Preisabstand, um
     * den es geht.</p>
     */
    private Double bauenJeStueck(Long characterId, BlueprintInfo bp,
                                 double freightPerCubicMeter,
                                 Map<Long, Double> memo, Set<Long> pfad) {
        IndustryContext ctx = planning.contextFor(characterId, bp);
        long jeLauf = Math.max(1, bp.unitsPerRun());

        double material = 0;
        double grundwert = 0;
        for (var kind : queryRepo.billOfMaterials(bp.productTypeId(), 1)) {
            Double kindKosten = unitCost(characterId, kind.typeId(), freightPerCubicMeter,
                    memo, pfad);
            if (kindKosten == null) {
                // Ohne Preis laesst sich der Weg nicht beziffern. Ihn mit null zu
                // bewerten liesse Bauen kuenstlich guenstig aussehen - genau die
                // Richtung, in der ein Fehler teuer wird.
                return null;
            }
            long menge = IndustryMath.materialForJob(1, kind.quantityPerRun(), ctx);
            material += menge * kindKosten;
            grundwert += kind.quantityPerRun() * kindKosten;
        }

        double gebuehr = IndustryMath
                .jobCost(java.math.BigDecimal.valueOf(grundwert), ctx)
                .doubleValue();
        return (material + gebuehr) / jeLauf;
    }

    /**
     * Rechnet fuer ein Bauteil beide Wege durch - eine Ebene tief.
     *
     * <p>Fuer die Anzeige einer einzelnen Zeile gedacht, nicht fuer die
     * Entscheidung: dort zaehlt {@link #buildIsCheaper}, das rekursiv rechnet
     * und den Transport mitnimmt.</p>
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
        return shouldBuild(characterId, typeId, quantity, sourceKind, strategy,
                LogisticsMath.Transport.JUMP_FREIGHTER.perCubicMeter().doubleValue());
    }

    /**
     * Ob ein Bauteil nach der gewaehlten Voreinstellung gebaut werden soll.
     *
     * <p>Bei {@link BuildStrategy#COST_EFFICIENT} entscheidet die Rechnung, und
     * zwar die <em>rekursive</em>: verglichen wird der Fertigkaufpreis mit dem,
     * was das Bauteil kostet, wenn fuer jede Zutat wiederum der guenstigere Weg
     * gewaehlt wird. Die alte einstufige Rechnung bewertete die Zutaten mit ihrem
     * Kaufpreis und stellte deshalb Dinge auf "Kaufen", die sich zwei Ebenen
     * tiefer billig herstellen lassen - mit dem Ergebnis, dass "alles selbst
     * bauen" guenstiger herauskam als "moeglichst guenstig".</p>
     *
     * <p>Die Fracht geht mit ein. Sie ist bei Capital-Bauteilen nicht der
     * Nebenposten, fuer den man sie haelt: fertig gekauft fuellen sie fuenf
     * Sprungfrachterladungen, ihre Zutaten eine.</p>
     *
     * <p>Laesst sich die Rechnung nicht anstellen - etwa weil ein Preis fehlt -
     * wird gekauft: die sichere Wahl, denn sie braucht weder Blaupause noch
     * Jobslot.</p>
     *
     * @param freightPerCubicMeter Frachtsatz zum Bauort in ISK je Kubikmeter
     */
    @Transactional(readOnly = true)
    public boolean shouldBuild(Long characterId, long typeId, long quantity,
                               String sourceKind, BuildStrategy strategy,
                               double freightPerCubicMeter) {
        return shouldBuild(characterId, typeId, quantity, sourceKind, strategy,
                freightPerCubicMeter, new HashMap<>());
    }

    /** Wie oben, mit einem Zwischenspeicher, der ueber mehrere Zeilen haelt. */
    @Transactional(readOnly = true)
    public boolean shouldBuild(Long characterId, long typeId, long quantity,
                               String sourceKind, BuildStrategy strategy,
                               double freightPerCubicMeter, Map<Long, Double> memo) {
        boolean herstellbar = "BUILDABLE".equals(sourceKind) || "REACTION".equals(sourceKind);
        if (!herstellbar) {
            return false;
        }
        return switch (strategy) {
            case BUY_ALL -> false;
            case BUILD_ALL -> true;
            case COST_EFFICIENT ->
                    buildIsCheaper(characterId, typeId, freightPerCubicMeter, memo);
        };
    }

    /**
     * Ob Selbstbauen billiger ist als Fertigkaufen - beides an den Bauort
     * geliefert und beides mit dem jeweils guenstigsten Weg fuer jede Zutat.
     */
    @Transactional(readOnly = true)
    public boolean buildIsCheaper(Long characterId, long typeId, double freightPerCubicMeter) {
        return buildIsCheaper(characterId, typeId, freightPerCubicMeter, new HashMap<>());
    }

    /**
     * Wie oben, aber mit einem Zwischenspeicher, der ueber mehrere Zeilen haelt.
     *
     * <p>Der Unterschied ist kein Feinschliff. Die Stuecklisten eines Auftrags
     * ueberlappen sich stark - dieselben Reaktionsprodukte und Mineralien tauchen
     * unter Dutzenden Bauteilen wieder auf. Wer den Speicher je Zeile wegwirft,
     * rechnet denselben Teilbaum immer wieder: bei einem Auftrag mit 515 Zeilen
     * waren das ueber fuenftausend Datenbankabfragen fuer einen einzigen Klick
     * auf "Moeglichst guenstig".</p>
     */
    @Transactional(readOnly = true)
    public boolean buildIsCheaper(Long characterId, long typeId, double freightPerCubicMeter,
                                  Map<Long, Double> memo) {
        BlueprintInfo bp = queryRepo.blueprintFor(typeId);
        if (bp == null) {
            return false;
        }
        Double bauen = bauenJeStueck(characterId, bp, freightPerCubicMeter, memo,
                new HashSet<>(Set.of(typeId)));
        Double kaufen = kaufenJeStueck(typeId, freightPerCubicMeter);

        if (bauen == null) {
            return false;
        }
        // Ohne Marktpreis fuer das fertige Teil ist Bauen der einzige bezifferte Weg.
        return kaufen == null || bauen < kaufen;
    }
}
