package com.eve.own.auth.backend.domain.industry.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.eve.own.auth.backend.domain.industry.IndustryActivity;
import com.eve.own.auth.backend.domain.industry.service.IndustryMath.JobSplit;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Die Rechenregeln der Fertigung.
 *
 * <p>Die Zahlen stammen aus nachgerechneten Ingame-Werten. Sie stehen hier
 * bewusst als feste Erwartungen und nicht als neu berechnete Formeln - ein Test,
 * der dieselbe Formel noch einmal aufschreibt, prueft nichts.</p>
 */
class IndustryMathTest {

    /** Raitaru: ein Prozent Material, fuenfzehn Prozent Zeit. */
    private static final BigDecimal RAITARU_MATERIAL = new BigDecimal("0.99");
    private static final BigDecimal RAITARU_TIME = new BigDecimal("0.85");

    /**
     * Die beiden Rig-Arten liegen in voellig verschiedenen Groessenordnungen:
     * ein Material-Rig spart rund zwei Prozent, ein Zeit-Rig rund zwanzig.
     * Wer denselben Wert fuer beide einsetzt, rechnet die Dauer um zwei Drittel
     * daneben - und merkt es nicht, weil die Formel formal stimmt.
     */
    private static final BigDecimal T1_MATERIAL_RIG = new BigDecimal("2.0");
    private static final BigDecimal T1_TIME_RIG = new BigDecimal("20.0");

    /** Sicherheitsfaktor des Rigs: 1,0 Highsec, 1,9 Lowsec, 2,1 Nullsec. */
    private static final BigDecimal NULLSEC = new BigDecimal("2.1");

    private static IndustryContext raitaruNullsec(int me, int te) {
        return new IndustryContext(
                IndustryActivity.MANUFACTURING, me, te,
                RAITARU_MATERIAL, RAITARU_TIME,
                T1_MATERIAL_RIG, T1_TIME_RIG, NULLSEC,
                0, 0, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Nested
    @DisplayName("Materialbedarf")
    class Material {

        @Test
        void verkettetDieFaktorenMultiplikativUndNichtAdditiv() {
            // Golem, Morphite: Grundmenge 975, ein Lauf, ME 1, Raitaru, T1-Rig Nullsec.
            // Ingame kommen 916 heraus. Additiv gerechnet waeren es 915 - dieser
            // Test haelt genau diesen Unterschied fest.
            IndustryContext ctx = raitaruNullsec(1, 0);

            long benoetigt = IndustryMath.materialForJob(1, 975, ctx);

            assertThat(benoetigt).isEqualTo(916);

            // Gegenprobe: die additive Verkettung ergibt nachweislich einen anderen Wert.
            BigDecimal additiv = BigDecimal.ONE.subtract(
                    new BigDecimal("0.01").add(new BigDecimal("0.01")).add(new BigDecimal("0.042")));
            long additivGerundet = new BigDecimal("975").multiply(additiv)
                    .setScale(2, java.math.RoundingMode.HALF_UP)
                    .setScale(0, java.math.RoundingMode.CEILING)
                    .longValueExact();
            assertThat(additivGerundet).isEqualTo(915).isNotEqualTo(benoetigt);
        }

        @Test
        void rundetJeJobUndNichtJeLauf() {
            // Raven, Tritanium: Grundmenge 5.200.000, ME 10, Raitaru, T1-Rig Nullsec.
            IndustryContext ctx = raitaruNullsec(10, 0);

            long einJobMitZehnLaeufen = IndustryMath.materialForJob(10, 5_200_000, ctx);
            long zehnEinzeljobs = 10 * IndustryMath.materialForJob(1, 5_200_000, ctx);

            assertThat(einJobMitZehnLaeufen).isEqualTo(44_386_056);
            // Der Unterschied ist keine Ungenauigkeit, sondern Spielmechanik:
            // wer zehnmal einzeln baut, braucht mehr.
            assertThat(zehnEinzeljobs).isGreaterThan(einJobMitZehnLaeufen);
        }

        @Test
        void haeltDieMindestmengeVonEinemStueckJeLaufEin() {
            // Core Temperature Regulator: Grundmenge 1 je Lauf. Mit ME 10 und
            // Strukturboni ergibt die reine Rechnung 8,54 -> aufgerundet 9.
            // Ingame braucht ein Job mit zehn Laeufen aber zehn Stueck.
            IndustryContext ctx = raitaruNullsec(10, 0);

            long benoetigt = IndustryMath.materialForJob(10, 1, ctx);

            assertThat(benoetigt).isEqualTo(10);
        }

        @Test
        void ohneJedenBonusBleibtDieGrundmengeStehen() {
            IndustryContext ctx = IndustryContext.plain(IndustryActivity.MANUFACTURING);

            assertThat(IndustryMath.materialForJob(10, 5_200_000, ctx)).isEqualTo(52_000_000);
        }

        @Test
        void summiertUeberDieJobsEinesAuftrags() {
            // Fuenfzig Raven: fuenf Jobs zu zehn Laeufen, kein Rest.
            IndustryContext ctx = raitaruNullsec(10, 0);
            JobSplit split = new JobSplit(10, 5, 0);

            long gesamt = IndustryMath.materialForOrder(split, 5_200_000, ctx);

            assertThat(gesamt).isEqualTo(5 * 44_386_056L);
        }

        @Test
        void rechnetDenRestjobEigenstaendig() {
            IndustryContext ctx = raitaruNullsec(10, 0);
            JobSplit split = new JobSplit(10, 2, 3);

            long gesamt = IndustryMath.materialForOrder(split, 5_200_000, ctx);

            assertThat(gesamt).isEqualTo(2 * IndustryMath.materialForJob(10, 5_200_000, ctx)
                    + IndustryMath.materialForJob(3, 5_200_000, ctx));
        }
    }

    @Nested
    @DisplayName("Jobzerlegung")
    class Zerlegung {

        @Test
        void teiltFuenfzigRavenInFuenfJobs() {
            // maxProductionLimit 10, fuenf Stunden je Lauf.
            JobSplit split = IndustryMath.splitIntoJobs(50, 10, 18_000);

            assertThat(split.runsPerJob()).isEqualTo(10);
            assertThat(split.fullJobs()).isEqualTo(5);
            assertThat(split.remainderRuns()).isZero();
            assertThat(split.jobCount()).isEqualTo(5);
            assertThat(split.totalRuns()).isEqualTo(50);
        }

        @Test
        void begrenztAufDreissigTageJeJob() {
            // Zehn Tage je Lauf: mehr als drei Laeufe passen nicht in einen Job,
            // auch wenn die Blaupause hundert erlaubte.
            long zehnTage = 10L * 24 * 3600;

            JobSplit split = IndustryMath.splitIntoJobs(10, 100, zehnTage);

            assertThat(split.runsPerJob()).isEqualTo(3);
            assertThat(split.jobCount()).isEqualTo(4);
            assertThat(split.totalRuns()).isEqualTo(10);
        }

        @Test
        void laesstNieNullLaeufeJeJobZu() {
            // Ein Lauf dauert laenger als dreissig Tage. Ohne Untergrenze kaeme
            // hier null heraus und der Auftrag zerfiele in unendlich viele Jobs.
            long vierzigTage = 40L * 24 * 3600;

            JobSplit split = IndustryMath.splitIntoJobs(3, 100, vierzigTage);

            assertThat(split.runsPerJob()).isEqualTo(1);
            assertThat(split.jobCount()).isEqualTo(3);
        }

        @Test
        void bildetEinenRestjob() {
            JobSplit split = IndustryMath.splitIntoJobs(53, 10, 18_000);

            assertThat(split.fullJobs()).isEqualTo(5);
            assertThat(split.remainderRuns()).isEqualTo(3);
            assertThat(split.jobCount()).isEqualTo(6);
            assertThat(split.totalRuns()).isEqualTo(53);
        }

        @Test
        void rundetLaeufeAufUndNichtMaterialien() {
            // Ein Lauf liefert drei Stueck, gebraucht werden vier: das sind zwei
            // Laeufe. Ein Drittel Lauf gibt es nicht.
            assertThat(IndustryMath.runsForQuantity(4, 3)).isEqualTo(2);
            assertThat(IndustryMath.runsForQuantity(3, 3)).isEqualTo(1);
            assertThat(IndustryMath.runsForQuantity(1, 3)).isEqualTo(1);
            assertThat(IndustryMath.runsForQuantity(0, 3)).isZero();
        }
    }

    @Nested
    @DisplayName("Dauer")
    class Dauer {

        @Test
        void rechnetDieRavenAufKnappDreizehnStunden() {
            // Raven, zehn Laeufe, TE 20, Industry V, Advanced Industry V,
            // Raitaru mit T1-Rig in Nullsec, Implantat BX-804.
            IndustryContext ctx = new IndustryContext(
                    IndustryActivity.MANUFACTURING, 0, 20,
                    RAITARU_MATERIAL, RAITARU_TIME,
                    BigDecimal.ZERO, T1_TIME_RIG, NULLSEC,
                    5, 5, new BigDecimal("0.96"),
                    BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO);

            long sekunden = IndustryMath.productionSeconds(10, 18_000, ctx);

            // 50 Stunden ohne alles, hier 12,87 Stunden.
            assertThat(sekunden).isEqualTo(46_344);
            assertThat(sekunden).isLessThan(10 * 18_000);
        }

        @Test
        void ohneBoniBleibtDieGrunddauer() {
            IndustryContext ctx = IndustryContext.plain(IndustryActivity.MANUFACTURING);

            assertThat(IndustryMath.productionSeconds(10, 18_000, ctx)).isEqualTo(180_000);
        }
    }

    @Nested
    @DisplayName("Gebuehr")
    class Gebuehr {

        @Test
        void nimmtDenZuschlagJeAktivitaet() {
            assertThat(IndustryMath.sccSurcharge(IndustryActivity.MANUFACTURING))
                    .isEqualByComparingTo("0.04");
            assertThat(IndustryMath.sccSurcharge(IndustryActivity.REACTION_SDE))
                    .isEqualByComparingTo("0.04");
            assertThat(IndustryMath.sccSurcharge(IndustryActivity.MATERIAL_EFFICIENCY_RESEARCH))
                    .isEqualByComparingTo("0.02");
            assertThat(IndustryMath.sccSurcharge(IndustryActivity.TIME_EFFICIENCY_RESEARCH))
                    .isEqualByComparingTo("0.02");
        }

        @Test
        void rechnetDieRavenGebuehr() {
            // Raven, zehn Laeufe, Raitaru, ein Prozent Anlagensteuer, Omega,
            // Systemkostenindex 0,0512. Warenwert 1.438.650.000 ISK.
            IndustryContext ctx = new IndustryContext(
                    IndustryActivity.MANUFACTURING, 10, 20,
                    RAITARU_MATERIAL, RAITARU_TIME,
                    T1_MATERIAL_RIG, T1_TIME_RIG, NULLSEC,
                    5, 5, BigDecimal.ONE,
                    new BigDecimal("0.0512"), new BigDecimal("0.97"),
                    new BigDecimal("0.01"), BigDecimal.ZERO);

            BigDecimal gebuehr = IndustryMath.jobCost(new BigDecimal("1438650000"), ctx);

            assertThat(gebuehr).isEqualByComparingTo("143381613.60");
        }

        @Test
        void deckeltDieAnlagensteuerBeiZehnProzent() {
            IndustryContext zuHoch = new IndustryContext(
                    IndustryActivity.MANUFACTURING, 0, 0,
                    BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE,
                    0, 0, BigDecimal.ONE,
                    BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("0.99"), BigDecimal.ZERO);

            assertThat(zuHoch.facilityTax()).isEqualByComparingTo("0.10");
        }

        @Test
        void derWarenwertKenntKeineMaterialboni() {
            // Zwei Auftraege mit voellig verschiedener Forschung, aber gleicher
            // Grundmenge, haben denselben geschaetzten Wert - und damit dieselbe
            // Gebuehr. Bessere ME senkt den Materialbedarf, nicht die Gebuehr.
            BigDecimal wert = IndustryMath.estimatedItemValue(10, new BigDecimal("143865000"));

            assertThat(wert).isEqualByComparingTo("1438650000");
        }

        @Test
        void ohneWertKeineGebuehr() {
            IndustryContext ctx = IndustryContext.plain(IndustryActivity.MANUFACTURING);

            assertThat(IndustryMath.jobCost(BigDecimal.ZERO, ctx)).isEqualByComparingTo("0");
            assertThat(IndustryMath.jobCost(null, ctx)).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("Aktivitaets-Uebersetzung")
    class Uebersetzung {

        @Test
        void bildetReaktionenZwischenEsiUndSdeAb() {
            // Der gefaehrlichste stille Fehler: ESI zaehlt Reaktionen als 9,
            // die SDE als 11. Wer eins zu eins abbildet, verliert jeden Reaktionsjob.
            assertThat(IndustryActivity.sdeFromEsi(9)).isEqualTo(11);
            assertThat(IndustryActivity.esiFromSde(11)).isEqualTo(9);
        }

        @Test
        void laesstDieUebrigenNummernUnveraendert() {
            for (int id : new int[] {1, 3, 4, 5, 8}) {
                assertThat(IndustryActivity.sdeFromEsi(id)).isEqualTo(id);
                assertThat(IndustryActivity.esiFromSde(id)).isEqualTo(id);
            }
        }

        @Test
        void gibtBeiUnbekanntenNummernNullStattZuWerfen() {
            // CCP kann jederzeit eine Aktivitaet hinzufuegen. Dann soll der Job
            // angezeigt werden, nicht der ganze Abgleich scheitern.
            assertThat(IndustryActivity.sdeFromEsi(99)).isNull();
            assertThat(IndustryActivity.sdeFromEsi(null)).isNull();
            assertThat(IndustryActivity.esiFromSde(99)).isNull();
            assertThat(IndustryActivity.label(99)).isEqualTo("Unbekannte Aktivität");
        }

        @Test
        void kenntNurFertigungUndReaktionAlsErzeugend() {
            assertThat(IndustryActivity.producesItems(IndustryActivity.MANUFACTURING)).isTrue();
            assertThat(IndustryActivity.producesItems(IndustryActivity.REACTION_SDE)).isTrue();
            assertThat(IndustryActivity.producesItems(IndustryActivity.INVENTION)).isFalse();
            assertThat(IndustryActivity.producesItems(IndustryActivity.COPYING)).isFalse();
        }
    }
}
