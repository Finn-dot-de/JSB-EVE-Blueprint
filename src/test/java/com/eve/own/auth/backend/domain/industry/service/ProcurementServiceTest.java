package com.eve.own.auth.backend.domain.industry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.industry.dto.IndustryDtos;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository.OreSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Die Beschaffungsrechnung.
 *
 * <p>Die Zahlen stammen aus der echten Datenbank: Tritanium (34) kostet 3,97 ISK
 * bei 0,01 m3, komprimiertes Veldspar liefert 400 Tritanium je 100 Einheiten bei
 * 0,001 m3.</p>
 */
class ProcurementServiceTest {

    private static final long TRITANIUM = 34L;
    private static final long COMP_VELDSPAR = 28430L;

    /** Perimeter - ein Sprung von Jita, Highsec. */
    private static final long PERIMETER = 30000144L;

    /** Ein Nullsec-System, weit weg. */
    private static final long NULLSEC = 30004759L;

    private IndustryQueryRepository queryRepo;
    private ProcurementService service;

    @BeforeEach
    void setUp() {
        queryRepo = Mockito.mock(IndustryQueryRepository.class);
        service = new ProcurementService(queryRepo);

        when(queryRepo.allJumpsFromJita()).thenReturn(Map.of(
                IndustryQueryRepository.JITA_SYSTEM_ID, 0,
                PERIMETER, 1,
                NULLSEC, 34));
        when(queryRepo.isMineral(TRITANIUM)).thenReturn(true);
        when(queryRepo.jitaSell(TRITANIUM)).thenReturn(3.97);
        when(queryRepo.compressedOreSourcesFor(TRITANIUM)).thenReturn(List.of(
                new OreSource(COMP_VELDSPAR, "Compressed Veldspar", 100, 400, 0.001, 10.42, 1)));
    }

    private static IndustryDtos.RequirementDto tritanium(long fehlt) {
        return new IndustryDtos.RequirementDto(
                TRITANIUM, "Tritanium", 5_200_000, 5_200_000 - fehlt, 0, fehlt,
                "MINERAL", false, "BUY", 1, 0, null, null, false, 0.01, 1, 0);
    }

    private static final long PYERITE = 35L;
    private static final long COMP_ZEOLITES = 62_516L;

    private static IndustryDtos.RequirementDto pyerite(long fehlt) {
        return new IndustryDtos.RequirementDto(
                PYERITE, "Pyerite", fehlt, 0, 0, fehlt,
                "MINERAL", false, "BUY", 1, 0, null, null, false, 0.01, 1, 0);
    }

    @Test
    @DisplayName("sagt, warum kein Erz auf der Liste steht")
    void begruendetDasFehlenVonErz() {
        // Ohne diesen Satz trifft der Assistent die Entscheidung unsichtbar, und
        // wer Erz erwartet, hält das Fehlen für einen Fehler statt für ein
        // Ergebnis. Genau so wurde es gemeldet.
        when(queryRepo.isMineral(PYERITE)).thenReturn(true);
        when(queryRepo.jitaSell(PYERITE)).thenReturn(17.50);
        // Ein Erz, das viel zu teuer ist: 8000 Pyerite je 100 Einheiten zu 9000 ISK.
        when(queryRepo.compressedOreSourcesFor(PYERITE)).thenReturn(List.of(
                new OreSource(COMP_ZEOLITES, "Compressed Zeolites", 100, 8000, 0.1, 9000.0, 3)));
        when(queryRepo.materialsPerPortion(COMP_ZEOLITES)).thenReturn(Map.of(PYERITE, 8000L));

        var plan = service.plan(List.of(pyerite(1_000_000)), NULLSEC, -0.3);

        assertThat(plan.lines()).singleElement()
                .satisfies(z -> assertThat(z.source()).isEqualTo("DIRECT"));
        assertThat(plan.oreVerdict()).contains("Compressed Zeolites");
        assertThat(plan.oreFactor()).isNotNull().isLessThan(1.0);
    }

    @Test
    @DisplayName("rechnet die Nebenprodukte eines Erzes mit, aber nur bis zum Bedarf")
    void nebenprodukteZaehlenNurBisZumBedarf() {
        // Compressed Zeolites liefert neben Pyerite auch Mexallon. Wer das
        // unterschlägt, hält das Erz für teurer als es ist - der Code hat das
        // vorher selbst zugegeben und trotzdem nicht getan.
        when(queryRepo.isMineral(PYERITE)).thenReturn(true);
        when(queryRepo.isMineral(TRITANIUM)).thenReturn(true);
        when(queryRepo.jitaSell(PYERITE)).thenReturn(17.50);
        when(queryRepo.compressedOreSourcesFor(PYERITE)).thenReturn(List.of(
                new OreSource(COMP_ZEOLITES, "Compressed Zeolites", 100, 8000, 0.1, 9000.0, 3)));
        when(queryRepo.compressedOreSourcesFor(TRITANIUM)).thenReturn(List.of());
        when(queryRepo.materialsPerPortion(COMP_ZEOLITES))
                .thenReturn(Map.of(PYERITE, 8000L, TRITANIUM, 400L));

        double ohneNebenprodukt = service
                .plan(List.of(pyerite(1_000_000)), NULLSEC, -0.3).oreFactor();
        double mitNebenprodukt = service
                .plan(List.of(pyerite(1_000_000), tritanium(5_200_000)), NULLSEC, -0.3)
                .oreFactor();

        // Das Tritanium wird jetzt gebraucht und damit gutgeschrieben.
        assertThat(mitNebenprodukt).isGreaterThan(ohneNebenprodukt);
    }

    @Test
    @DisplayName("schreibt keinen Überschuss gut, den niemand braucht")
    void ueberschussIstNullWert() {
        // Der Deckel ist nicht kosmetisch: ohne ihn gewinnt das Erz mit dem
        // größten Ausstoß statt dem nützlichsten. Ein Erz, das das Tausendfache
        // des Bedarfs liefert, wäre sonst unschlagbar - und der Rat falsch.
        when(queryRepo.isMineral(PYERITE)).thenReturn(true);
        when(queryRepo.isMineral(TRITANIUM)).thenReturn(true);
        when(queryRepo.jitaSell(PYERITE)).thenReturn(17.50);
        when(queryRepo.compressedOreSourcesFor(PYERITE)).thenReturn(List.of(
                new OreSource(COMP_ZEOLITES, "Compressed Zeolites", 100, 8000, 0.1, 9000.0, 3)));
        when(queryRepo.compressedOreSourcesFor(TRITANIUM)).thenReturn(List.of());
        when(queryRepo.materialsPerPortion(COMP_ZEOLITES))
                .thenReturn(Map.of(PYERITE, 8000L, TRITANIUM, 400L));

        // Einmal mit winzigem, einmal mit riesigem Tritanium-Bedarf.
        double knapp = service
                .plan(List.of(pyerite(1_000_000), tritanium(10)), NULLSEC, -0.3).oreFactor();
        double reichlich = service
                .plan(List.of(pyerite(1_000_000), tritanium(5_200_000)), NULLSEC, -0.3).oreFactor();

        // Bei zehn Stück Bedarf darf nur zehn Stück gutgeschrieben werden, nicht
        // der ganze Anfall - der Faktor muss deutlich kleiner bleiben.
        assertThat(knapp).isLessThan(reichlich);
    }

    @Test
    @DisplayName("empfiehlt komprimiertes Erz, wenn der Sprungfrachter fährt")
    void erzGewinntBeimSprungfrachter() {
        var plan = service.plan(List.of(tritanium(5_200_000)), NULLSEC, -0.3);

        assertThat(plan.transport()).isEqualTo("JUMP_FREIGHTER");
        assertThat(plan.jumpsFromJita()).isEqualTo(34);
        assertThat(plan.lines()).singleElement().satisfies(z -> {
            assertThat(z.source()).isEqualTo("ORE");
            assertThat(z.buyTypeName()).isEqualTo("Compressed Veldspar");
            // Ohne bekannte Skills gilt die blosse Grundrate von 50 %:
            // 5,2 Mio / (400 * 0,5) = 26.000 Portionen zu je 100 Einheiten.
            assertThat(z.buyQuantity()).isEqualTo(2_600_000);
            assertThat(z.saving()).isPositive();
        });
    }

    @Test
    @DisplayName("vergleicht Gesamtkosten und nicht Einkaufspreise")
    void transportEntscheidetMit() {
        var plan = service.plan(List.of(tritanium(5_200_000)), NULLSEC, -0.3);
        var zeile = plan.lines().getFirst();

        double mineralWare = 5_200_000 * 3.97;
        double mineralFracht = 5_200_000 * 0.01 * 460;

        // Das Erz ist bei diesen Preisen schon beim Warenwert günstiger - und
        // beim Transport um ein Vielfaches. Entscheidend ist, dass der Dienst
        // beides addiert: bei Mineralien liegt die Fracht regelmäßig über dem
        // Warenwert, ein Vergleich reiner Einkaufspreise ginge daran vorbei.
        assertThat(mineralFracht).isGreaterThan(mineralWare);
        assertThat(zeile.totalCost()).isLessThan(zeile.alternative());
        assertThat(zeile.alternative()).isCloseTo(mineralWare + mineralFracht,
                org.assertj.core.data.Offset.offset(1.0));

        // Der Löwenanteil der Ersparnis kommt aus dem Volumen.
        double frachtErz = zeile.totalCost() - zeile.purchaseCost();
        assertThat(frachtErz).isLessThan(mineralFracht / 10);
    }

    @Test
    @DisplayName("rechnet mit den echten Skills des Kontos statt mit einer Annahme")
    void echteSkillsSenkenDenErzbedarf() {
        // Gemessen an einem echten Konto: Reprocessing V, Efficiency V,
        // Simple Ore Processing IV. Das ergibt 0,50 * 1,15 * 1,10 * 1,08 = 68,3 %.
        when(queryRepo.reprocessingSkills(any(), anyLong()))
                .thenReturn(new IndustryQueryRepository.ReprocessingSkills(5, 5, 4));

        var mitSkills = service.plan(List.of(tritanium(5_200_000)), NULLSEC, -0.3, Set.of(1L));
        var ohneSkills = service.plan(List.of(tritanium(5_200_000)), NULLSEC, -0.3);

        long mit = mitSkills.lines().getFirst().buyQuantity();
        long ohne = ohneSkills.lines().getFirst().buyQuantity();

        // Bessere Skills heissen weniger Erz - wer pauschal rechnet, kauft daneben.
        assertThat(mit).isLessThan(ohne);
        // 400 * 0,6831 = 273,24 Tritanium je Portion, also 19.031 Portionen.
        assertThat(mit).isEqualTo(1_903_100);
    }

    @Test
    @DisplayName("nimmt ohne bekannte Skills die vorsichtige Grundrate")
    void ohneSkillsWirdVorsichtigGerechnet() {
        var plan = service.plan(List.of(tritanium(5_200_000)), NULLSEC, -0.3, Set.of());

        // Lieber zu viel Erz einkaufen als zu wenig: zu wenig faellt erst am
        // leeren Ofen auf, und dann steht die Produktion.
        assertThat(plan.lines().getFirst().buyQuantity()).isEqualTo(2_600_000);
    }

    @Test
    @DisplayName("lässt ein Erz ohne Marktpreis weg, statt es mit null zu bewerten")
    void erzOhnePreisWirdUebergangen() {
        when(queryRepo.compressedOreSourcesFor(TRITANIUM)).thenReturn(List.of(
                new OreSource(COMP_VELDSPAR, "Compressed Veldspar", 100, 400, 0.001, null, 1)));

        var plan = service.plan(List.of(tritanium(5_200_000)), NULLSEC, -0.3);

        assertThat(plan.lines()).singleElement()
                .satisfies(z -> assertThat(z.source()).isEqualTo("DIRECT"));
    }

    @Test
    @DisplayName("kauft nicht, was gebaut werden soll")
    void gebautesWirdNichtGekauft() {
        // Der gemeldete Fehler: die Summe stieg, sobald man "Bauen" wählte.
        // Grund war, dass das fertige Teil UND seine Zutaten auf der Liste
        // standen - man hätte beides bezahlt.
        long komponente = 11399L;
        when(queryRepo.isMineral(komponente)).thenReturn(false);
        when(queryRepo.jitaSell(komponente)).thenReturn(206_700_000.0);

        var gebaut = new IndustryDtos.RequirementDto(
                komponente, "Capital Core Temperature Regulator", 1, 0, 0, 1,
                "BUILDABLE", true, "BUILD", 1, 1, null, null, false, 28_000.0, 0, 0);
        var zutat = new IndustryDtos.RequirementDto(
                11400L, "Core Temperature Regulator", 34, 0, 0, 34,
                "BUILDABLE", true, "BUY", 2, 0, komponente, null, false, 180.0, 0, 0);
        when(queryRepo.jitaSell(11400L)).thenReturn(3_270_000.0);

        var plan = service.plan(List.of(gebaut, zutat), PERIMETER, 0.9);

        assertThat(plan.lines()).extracting(IndustryDtos.ProcurementLineDto::typeName)
                .containsExactly("Core Temperature Regulator")
                .doesNotContain("Capital Core Temperature Regulator");
        // Nur die Zutat zählt in die Summe, nicht das fertige Teil.
        assertThat(plan.goodsCost()).isCloseTo(34 * 3_270_000.0,
                org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    @DisplayName("unterscheidet 'kein Bauort gewählt' von 'nicht erreichbar'")
    void bauortLageWirdUnterschieden() {
        var ohneOrt = service.plan(List.of(tritanium(1000)), null, null);
        assertThat(ohneOrt.locationChosen()).isFalse();
        assertThat(ohneOrt.jumpsFromJita()).isNull();

        // Ein Wurmloch ist gewählt, aber über Tore nicht erreichbar - das ist
        // etwas anderes als gar keine Wahl.
        var unerreichbar = service.plan(List.of(tritanium(1000)), 31000005L, null);
        assertThat(unerreichbar.locationChosen()).isTrue();
        assertThat(unerreichbar.jumpsFromJita()).isNull();
    }

    @Test
    @DisplayName("überspringt, was schon im Hangar liegt")
    void gedeckterBedarfWirdNichtGekauft() {
        var plan = service.plan(List.of(tritanium(0)), PERIMETER, 0.9);

        assertThat(plan.lines()).isEmpty();
        assertThat(plan.totalCost()).isZero();
    }

    @Test
    @DisplayName("meldet fehlende Preise, statt eine unvollständige Summe zu zeigen")
    void fehlendePreiseWerdenGezaehlt() {
        when(queryRepo.jitaSell(TRITANIUM)).thenReturn(null);
        when(queryRepo.compressedOreSourcesFor(TRITANIUM)).thenReturn(List.of());

        var plan = service.plan(List.of(tritanium(1000)), PERIMETER, 0.9);

        // Ohne diesen Zähler läse sich eine lückenhafte Summe wie eine vollständige.
        assertThat(plan.withoutPrice()).isEqualTo(1);
        assertThat(plan.lines()).singleElement()
                .satisfies(z -> assertThat(z.note()).contains("Kein Marktpreis"));
    }

    @Test
    @DisplayName("wählt in Highsec den Frachter und rechnet dessen Satz")
    void highsecFaehrtGuenstiger() {
        var plan = service.plan(List.of(tritanium(1_000_000)), PERIMETER, 0.9);

        assertThat(plan.transport()).isEqualTo("FREIGHTER");
        assertThat(plan.freightPerCubicMeter()).isEqualTo(120.0);
        assertThat(plan.loadCapacity()).isEqualTo(350_000);
    }

    @Test
    @DisplayName("berechnet die Sprungkarte nur einmal")
    void sprungkarteWirdGemerkt() {
        service.jumpsFromJita(PERIMETER);
        service.jumpsFromJita(NULLSEC);
        service.jumpsFromJita(PERIMETER);

        // Die Suche kostet rund eine Sekunde - sie je Anfrage zu wiederholen
        // wäre die teuerste Stelle des ganzen Assistenten.
        verify(queryRepo, times(1)).allJumpsFromJita();
    }

    @Test
    @DisplayName("sagt bei unerreichbarem System nichts Falsches")
    void unerreichbaresSystem() {
        // Wurmlöcher stehen in keiner Sprungliste.
        assertThat(service.jumpsFromJita(31000005L)).isNull();

        var plan = service.plan(List.of(tritanium(1000)), 31000005L, null);
        // Ohne Kenntnis des Ziels die teurere Annahme.
        assertThat(plan.transport()).isEqualTo("JUMP_FREIGHTER");
        assertThat(plan.jumpsFromJita()).isNull();
    }

    @Test
    @DisplayName("bricht das Volumen in ganze Ladungen um")
    void ladungenWerdenAufgerundet() {
        var plan = service.plan(List.of(tritanium(5_200_000)), NULLSEC, -0.3);

        assertThat(plan.loads()).isPositive();
        assertThat(plan.volume()).isPositive();
    }

    @Test
    @DisplayName("sieht bei Nicht-Mineralien gar nicht erst nach Erzen")
    void nurMineralienHabenErzquellen() {
        long komponente = 11399L;
        when(queryRepo.isMineral(komponente)).thenReturn(false);
        when(queryRepo.jitaSell(komponente)).thenReturn(1500.0);

        var bedarf = new IndustryDtos.RequirementDto(
                komponente, "Morphite", 100, 0, 0, 100, "MINERAL", false, "BUY", 1, 0, null, null, false, 0.01, 0, 0);

        var plan = service.plan(List.of(bedarf), PERIMETER, 0.9);

        assertThat(plan.lines()).singleElement()
                .satisfies(z -> assertThat(z.source()).isEqualTo("DIRECT"));
        verify(queryRepo, Mockito.never()).compressedOreSourcesFor(anyLong());
    }
}
