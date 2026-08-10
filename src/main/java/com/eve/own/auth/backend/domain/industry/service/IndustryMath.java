package com.eve.own.auth.backend.domain.industry.service;

import com.eve.own.auth.backend.domain.industry.IndustryActivity;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Die Fertigungsrechnung: Materialbedarf, Dauer, Jobzerlegung, Gebuehr.
 *
 * <p>Ohne Datenbank, ohne Netz, ohne Zustand - alles kommt aus einem
 * {@link IndustryContext}. Diese Klasse ist der Kern des Assistenten; jede
 * Zahl, die ein Mitglied zu sehen bekommt, entsteht hier.</p>
 *
 * <p>Durchgaengig {@link BigDecimal} und nicht {@code double}. Der Materialbedarf
 * wird auf zwei Stellen gerundet und danach aufgerundet - bei einem Zwischenwert
 * von {@code 915.9999999999999} statt {@code 916.00} kippt das Ergebnis um eine
 * ganze Einheit, und der Job laesst sich ingame nicht starten.</p>
 *
 * <h2>Vier Regeln, die erfahrungsgemaess brechen</h2>
 * <ol>
 *   <li><b>Multiplikativ, nie additiv.</b> ME-, Struktur- und Rig-Faktoren werden
 *       miteinander multipliziert. Wer die Prozente addiert, unterschaetzt den
 *       Bedarf immer - bei fuenfzig Raven um rund 290.000 Tritanium, und der
 *       Fehler faellt erst beim Startversuch auf.</li>
 *   <li><b>Gerundet wird je Job, nicht je Lauf.</b> Die Laufzahl steht innerhalb
 *       der Rundung. Zehn Einzeljobs brauchen mehr Material als ein Job mit zehn
 *       Laeufen; das ist kein Rundungsfehler, sondern Spielmechanik.</li>
 *   <li><b>Mindestens ein Stueck je Lauf.</b> Sonst verlangt die Rechnung neun
 *       Core Temperature Regulator fuer zehn Raven.</li>
 *   <li><b>Der geschaetzte Wert kennt keine Boni.</b> In die Gebuehr gehen immer
 *       die ME-0-Mengen ein. Bessere Forschung senkt den Materialbedarf, aber
 *       nicht die Gebuehr.</li>
 * </ol>
 */
public final class IndustryMath {

    /** Genauigkeit der Zwischenschritte - reichlich ueber allem, was gebraucht wird. */
    private static final MathContext MC = new MathContext(24, RoundingMode.HALF_UP);

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** Ein Job laeuft ingame hoechstens 30 Tage. */
    public static final long MAX_JOB_SECONDS = 30L * 24 * 3600;

    /** Zuschlag der SCC je Aktivitaet - nicht ein globaler Wert. */
    private static final BigDecimal SCC_PRODUCTION = new BigDecimal("0.04");
    private static final BigDecimal SCC_RESEARCH = new BigDecimal("0.02");

    private IndustryMath() {
        throw new AssertionError("Rechenhilfe, nicht instanziierbar.");
    }

    // ===========================================================
    //  Material
    // ===========================================================

    /**
     * Der Materialfaktor, mit dem jede Grundmenge multipliziert wird.
     *
     * <p>{@code (1 - ME/100) * strukturME * (1 - rigME * secMult / 100)}</p>
     */
    public static BigDecimal materialModifier(IndustryContext ctx) {
        BigDecimal fromResearch = BigDecimal.ONE.subtract(
                BigDecimal.valueOf(ctx.materialEfficiency()).divide(HUNDRED, MC));
        BigDecimal fromRig = BigDecimal.ONE.subtract(
                ctx.rigMaterialPercent().multiply(ctx.securityMultiplier(), MC).divide(HUNDRED, MC));
        return fromResearch.multiply(ctx.structureMaterial(), MC).multiply(fromRig, MC);
    }

    /**
     * Der Materialbedarf eines <em>einzelnen Jobs</em>.
     *
     * <p>{@code max(runs, ceil(round(runs * baseQuantity * modifier, 2)))}</p>
     *
     * @param runs          Laeufe dieses einen Jobs
     * @param baseQuantity  Grundmenge je Lauf aus {@code industryActivityMaterials}
     */
    public static long materialForJob(long runs, long baseQuantity, IndustryContext ctx) {
        if (runs <= 0 || baseQuantity <= 0) {
            return 0;
        }
        BigDecimal raw = BigDecimal.valueOf(runs)
                .multiply(BigDecimal.valueOf(baseQuantity), MC)
                .multiply(materialModifier(ctx), MC);
        long rounded = raw.setScale(2, RoundingMode.HALF_UP)
                .setScale(0, RoundingMode.CEILING)
                .longValueExact();
        // Ein Lauf verbraucht nie weniger als ein Stueck.
        return Math.max(runs, rounded);
    }

    /**
     * Der Materialbedarf ueber mehrere Jobs hinweg.
     *
     * <p>Bewusst je Job gerechnet und dann summiert. Ein Auftrag ueber fuenfzig
     * Raven besteht aus fuenf Jobs zu zehn Laeufen - das ist etwas anderes als
     * ein gedachter Job mit fuenfzig Laeufen.</p>
     */
    public static long materialForOrder(JobSplit split, long baseQuantity, IndustryContext ctx) {
        long total = materialForJob(split.runsPerJob(), baseQuantity, ctx) * split.fullJobs();
        if (split.remainderRuns() > 0) {
            total += materialForJob(split.remainderRuns(), baseQuantity, ctx);
        }
        return total;
    }

    // ===========================================================
    //  Jobzerlegung
    // ===========================================================

    /**
     * Wie ein Auftrag in Jobs zerfaellt.
     *
     * @param runsPerJob    Laeufe je vollem Job
     * @param fullJobs      Anzahl voller Jobs
     * @param remainderRuns Laeufe eines abschliessenden Restjobs, sonst 0
     */
    public record JobSplit(long runsPerJob, long fullJobs, long remainderRuns) {

        /** Die Gesamtzahl der Jobs, Restjob eingerechnet. */
        public long jobCount() {
            return fullJobs + (remainderRuns > 0 ? 1 : 0);
        }

        /** Die Gesamtzahl der Laeufe - muss der Zielmenge entsprechen. */
        public long totalRuns() {
            return runsPerJob * fullJobs + remainderRuns;
        }
    }

    /**
     * Zerlegt eine Zielmenge in Jobs.
     *
     * <p>Zwei Grenzen wirken: die Blaupause erlaubt hoechstens
     * {@code maxProductionLimit} Laeufe, und ein Job darf nicht laenger als
     * dreissig Tage laufen. Von beiden gilt die kleinere.</p>
     *
     * @param targetRuns         benoetigte Laeufe insgesamt
     * @param maxProductionLimit aus {@code industryBlueprints}, je Blaupause verschieden
     * @param secondsPerRun      die <em>gerechnete</em> Dauer eines Laufs
     */
    public static JobSplit splitIntoJobs(long targetRuns, long maxProductionLimit, long secondsPerRun) {
        if (targetRuns <= 0) {
            return new JobSplit(0, 0, 0);
        }
        long byBlueprint = Math.max(1, maxProductionLimit);
        long byDuration = secondsPerRun <= 0
                ? byBlueprint
                // Ohne das max(1, ...) liefert die Rechnung bei sehr langen Bauten
                // null Laeufe je Job - und der Auftrag zerfiele in unendlich viele Jobs.
                : Math.max(1, MAX_JOB_SECONDS / secondsPerRun);
        long runsPerJob = Math.min(byBlueprint, byDuration);

        long fullJobs = targetRuns / runsPerJob;
        long remainder = targetRuns % runsPerJob;
        return new JobSplit(runsPerJob, fullJobs, remainder);
    }

    /**
     * Wie viele Laeufe noetig sind, um eine Stueckzahl zu erreichen.
     *
     * <p>Aufgerundet, und zwar auf <em>Laeufe</em>: liefert ein Lauf drei Stueck
     * und werden vier gebraucht, sind das zwei Laeufe und sechs Stueck, nicht
     * 1,33 Laeufe. Ein Bruchteil eines Laufs existiert nicht.</p>
     */
    public static long runsForQuantity(long wantedQuantity, long unitsPerRun) {
        if (wantedQuantity <= 0) {
            return 0;
        }
        long perRun = Math.max(1, unitsPerRun);
        return (wantedQuantity + perRun - 1) / perRun;
    }

    // ===========================================================
    //  Zeit
    // ===========================================================

    /**
     * Der Zeitfaktor.
     *
     * <p>{@code (1 - TE/100) * strukturTE * (1 - rigTE * secMult / 100)
     * * (1 - 0.04 * Industry) * (1 - 0.03 * AdvancedIndustry) * Implantat}</p>
     */
    public static BigDecimal timeModifier(IndustryContext ctx) {
        BigDecimal fromResearch = BigDecimal.ONE.subtract(
                BigDecimal.valueOf(ctx.timeEfficiency()).divide(HUNDRED, MC));
        BigDecimal fromRig = BigDecimal.ONE.subtract(
                ctx.rigTimePercent().multiply(ctx.securityMultiplier(), MC).divide(HUNDRED, MC));
        BigDecimal fromIndustry = BigDecimal.ONE.subtract(
                new BigDecimal("0.04").multiply(BigDecimal.valueOf(ctx.industrySkill()), MC));
        BigDecimal fromAdvanced = BigDecimal.ONE.subtract(
                new BigDecimal("0.03").multiply(BigDecimal.valueOf(ctx.advancedIndustry()), MC));

        return fromResearch
                .multiply(ctx.structureTime(), MC)
                .multiply(fromRig, MC)
                .multiply(fromIndustry, MC)
                .multiply(fromAdvanced, MC)
                .multiply(ctx.implantTimeFactor(), MC);
    }

    /**
     * Die gerechnete Dauer in Sekunden.
     *
     * <p>Eine Prognose, keine Zusage: massgeblich ist immer, was ESI nach dem
     * Start als {@code duration} meldet.</p>
     */
    public static long productionSeconds(long runs, long baseTimeSeconds, IndustryContext ctx) {
        if (runs <= 0 || baseTimeSeconds <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(baseTimeSeconds)
                .multiply(BigDecimal.valueOf(runs), MC)
                .multiply(timeModifier(ctx), MC)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    // ===========================================================
    //  Kosten
    // ===========================================================

    /**
     * Der Zuschlag der SCC fuer eine Aktivitaet.
     *
     * <p>Vier Prozent auf Fertigung und Reaktionen, zwei auf Forschung. Ein
     * einziger globaler Wert waere fuer die Haelfte der Faelle falsch.</p>
     */
    public static BigDecimal sccSurcharge(int sdeActivityId) {
        return switch (sdeActivityId) {
            case IndustryActivity.MANUFACTURING, IndustryActivity.REACTION_SDE -> SCC_PRODUCTION;
            case IndustryActivity.TIME_EFFICIENCY_RESEARCH,
                 IndustryActivity.MATERIAL_EFFICIENCY_RESEARCH -> SCC_RESEARCH;
            default -> BigDecimal.ZERO;
        };
    }

    /**
     * Der geschaetzte Warenwert, auf dem die Gebuehr beruht.
     *
     * <p>{@code runs * SUMME(grundmenge * adjustedPrice)} - <em>ohne</em> jeden
     * Materialbonus. Der Wert haengt allein an der Blaupause und am Referenzpreis
     * von CCP, nicht daran, wie gut geforscht oder wo gebaut wird.</p>
     *
     * @param baseMaterialValue Summe aus Grundmenge mal Referenzpreis je Material
     */
    public static BigDecimal estimatedItemValue(long runs, BigDecimal baseMaterialValue) {
        if (runs <= 0 || baseMaterialValue == null) {
            return BigDecimal.ZERO;
        }
        return baseMaterialValue.multiply(BigDecimal.valueOf(runs), MC);
    }

    /**
     * Die Jobgebuehr in ISK.
     *
     * <p>{@code EIV * (systemIndex * strukturBonus + anlagenSteuer + sccZuschlag + alphaZuschlag)}</p>
     */
    public static BigDecimal jobCost(BigDecimal estimatedItemValue, IndustryContext ctx) {
        if (estimatedItemValue == null || estimatedItemValue.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal rate = ctx.systemCostIndex().multiply(ctx.structureCostBonus(), MC)
                .add(ctx.facilityTax())
                .add(sccSurcharge(ctx.activityId()))
                .add(ctx.alphaCloneTax());
        return estimatedItemValue.multiply(rate, MC).setScale(2, RoundingMode.HALF_UP);
    }
}
