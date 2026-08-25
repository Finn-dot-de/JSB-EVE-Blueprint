package com.eve.own.auth.backend.domain.industry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository.BlueprintInfo;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository.BomNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Der Vergleich "selbst bauen oder fertig kaufen".
 *
 * <p>Die Zahlen stammen aus der echten Datenbank, Capital Core Temperature
 * Regulator: fertig 193,8 Millionen ISK; die drei Materialien kosten bei ME 0
 * zusammen 201,9 Millionen und bei ME 10 noch 183,3. Dieselbe Komponente,
 * entgegengesetzte Antwort - genau deshalb muss gerechnet werden.</p>
 */
class BuildVsBuyServiceTest {

    private static final long KOMPONENTE = 21_009L;
    private static final long BAUPLAN = 21_010L;

    private static final long DROHNEN = 2867L;
    private static final long POWER_CORE = 2876L;
    private static final long REGULATOR = 11_399L;

    private IndustryQueryRepository queryRepo;
    private IndustryPlanningService planning;
    private BuildVsBuyService service;

    @BeforeEach
    void setUp() {
        queryRepo = Mockito.mock(IndustryQueryRepository.class);
        planning = Mockito.mock(IndustryPlanningService.class);
        service = new BuildVsBuyService(queryRepo, planning);

        when(queryRepo.blueprintFor(KOMPONENTE)).thenReturn(new BlueprintInfo(
                BAUPLAN, "Capital Core Temperature Regulator Blueprint",
                KOMPONENTE, "Capital Core Temperature Regulator", 1, 1, 10, 3600));
        when(queryRepo.jitaSell(KOMPONENTE)).thenReturn(193_800_000.0);
        when(queryRepo.billOfMaterials(KOMPONENTE, 1)).thenReturn(List.of(
                // Die Blaupause liefert ein Stueck je Lauf, also sind Menge je
                // Stueck und Menge je Lauf hier gleich.
                new BomNode(1, DROHNEN, "Integrity Response Drones", null, 20, "PI", 1, 20),
                new BomNode(1, POWER_CORE, "Self-Harmonizing Power Core", null, 20, "PI", 1, 20),
                new BomNode(1, REGULATOR, "Core Temperature Regulator", null, 35,
                        "BUILDABLE", 1, 35)));
        when(queryRepo.jitaSell(DROHNEN)).thenReturn(47_780_000.0 / 20);
        when(queryRepo.jitaSell(POWER_CORE)).thenReturn(42_540_000.0 / 20);
        when(queryRepo.jitaSell(REGULATOR)).thenReturn(111_580_000.0 / 35);
    }

    private void mitMaterialeffizienz(int me) {
        when(planning.contextFor(any(), any())).thenReturn(new IndustryContext(
                1, me, 0, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE,
                0, 0, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    @Test
    @DisplayName("mit unerforschter Blaupause ist Kaufen günstiger")
    void ohneForschungLohntKaufen() {
        mitMaterialeffizienz(0);

        var urteil = service.compare(1L, KOMPONENTE, 1);

        assertThat(urteil.buildCheaper()).isFalse();
        assertThat(urteil.buildCost()).isGreaterThan(urteil.buyCost());
    }

    @Test
    @DisplayName("mit ME 10 kippt die Antwort ins Gegenteil")
    void mitForschungLohntBauen() {
        mitMaterialeffizienz(10);

        var urteil = service.compare(1L, KOMPONENTE, 1);

        // Derselbe Vergleich, andere Blaupause, umgekehrtes Ergebnis. Wer das
        // pauschal beantwortet, liegt in der Hälfte der Fälle daneben.
        assertThat(urteil.buildCheaper()).isTrue();
        assertThat(urteil.buildCost()).isLessThan(urteil.buyCost());

        // Die reine Materialersparnis liegt bei 10,5 Millionen - übrig bleiben
        // 2,4. Den Rest frisst die Jobgebühr: vier Prozent SCC-Zuschlag auf den
        // geschätzten Warenwert, und der bemisst sich an den ME-0-Mengen, wird
        // durch bessere Forschung also nicht kleiner. Wer die Gebühr weglässt,
        // überschätzt den Gewinn um mehr als das Vierfache.
        assertThat(urteil.saving()).isBetween(2_000_000.0, 3_000_000.0);
    }

    @Test
    @DisplayName("lässt fehlende Materialpreise nicht als Ersparnis durchgehen")
    void fehlenderMaterialpreisVerhindertDenVergleich() {
        mitMaterialeffizienz(10);
        when(queryRepo.jitaSell(REGULATOR)).thenReturn(null);

        var urteil = service.compare(1L, KOMPONENTE, 1);

        // Mit null zu rechnen liesse Bauen künstlich günstig aussehen - genau
        // die Richtung, in der ein Fehler teuer wird.
        assertThat(urteil.buildCost()).isNull();
        assertThat(urteil.buildCheaper()).isFalse();
        assertThat(urteil.reason()).contains("Marktpreis");
    }

    @Test
    @DisplayName("sagt bei nicht herstellbaren Dingen deutlich, dass es nichts zu wählen gibt")
    void nichtHerstellbares() {
        when(queryRepo.blueprintFor(34L)).thenReturn(null);
        when(queryRepo.jitaSell(34L)).thenReturn(3.97);

        var urteil = service.compare(1L, 34L, 1000);

        assertThat(urteil.buildable()).isFalse();
        assertThat(urteil.buyCost()).isEqualTo(3970.0);
        assertThat(urteil.reason()).contains("nicht per Industriejob");
    }

    @Test
    @DisplayName("wendet die Voreinstellungen wie erwartet an")
    void voreinstellungen() {
        mitMaterialeffizienz(0);

        // Alles kaufen heisst alles kaufen, auch wo Bauen günstiger wäre.
        assertThat(service.shouldBuild(1L, KOMPONENTE, 1, "BUILDABLE", BuildStrategy.BUY_ALL))
                .isFalse();
        // Alles bauen ignoriert die Kosten - hier wäre Kaufen günstiger.
        assertThat(service.shouldBuild(1L, KOMPONENTE, 1, "BUILDABLE", BuildStrategy.BUILD_ALL))
                .isTrue();
        // Günstig rechnet nach und entscheidet sich hier gegen Bauen.
        assertThat(service.shouldBuild(1L, KOMPONENTE, 1, "BUILDABLE", BuildStrategy.COST_EFFICIENT))
                .isFalse();

        mitMaterialeffizienz(10);
        assertThat(service.shouldBuild(1L, KOMPONENTE, 1, "BUILDABLE", BuildStrategy.COST_EFFICIENT))
                .isTrue();
    }

    @Test
    @DisplayName("baut niemals ein PI-Gut oder ein Mineral")
    void nichtHerstellbaresBleibtGekauft() {
        for (BuildStrategy s : BuildStrategy.values()) {
            // Auch "alles selbst bauen" kann keinen Planeten ersetzen.
            assertThat(service.shouldBuild(1L, 9832L, 100, "PI", s)).isFalse();
            assertThat(service.shouldBuild(1L, 34L, 100, "MINERAL", s)).isFalse();
            assertThat(service.shouldBuild(1L, 16_273L, 100, "GAS", s)).isFalse();
        }
        Mockito.verify(queryRepo, Mockito.never()).billOfMaterials(anyLong(), anyInt());
    }

    @Test
    @DisplayName("rechnet mit der Menge je Lauf, nicht je Stück")
    void mengeJeLaufZaehlt() {
        // Eine Reaktionsformel liefert 10.000 Stueck aus 100 Einheiten Material.
        // Je Stueck sind das 0,01 - wer diese Zahl aufrundet, rechnet mit 1
        // statt 100 und kommt auf ein Hundertstel des echten Bedarfs.
        long reaktion = 16_671L;
        when(queryRepo.blueprintFor(reaktion)).thenReturn(new BlueprintInfo(
                46_204L, "Titanium Carbide Reaction Formula", reaktion,
                "Titanium Carbide", 11, 10_000, 100, 10_800));
        when(queryRepo.jitaSell(reaktion)).thenReturn(1_000.0);
        when(queryRepo.billOfMaterials(reaktion, 1)).thenReturn(List.of(
                new BomNode(1, 16_649L, "Titanium Chromide", null, 0.01, "REACTION", 1, 100)));
        when(queryRepo.jitaSell(16_649L)).thenReturn(500.0);
        mitMaterialeffizienz(0);

        var urteil = service.compare(1L, reaktion, 1_000_000);

        // 1.000.000 / 10.000 = 100 Laeufe, je 100 Einheiten = 10.000 Stueck
        // zu 500 ISK = 5 Millionen. Mit der Menge je Stueck waeren es 50.000
        // gewesen - ein Hundertstel.
        assertThat(urteil.buildCost()).isGreaterThan(5_000_000.0);
    }

    @Test
    @DisplayName("sieht zwei Ebenen tief: baut, wenn erst die Zutat der Zutat es lohnt")
    void rekursiveRechnungSchlaegtDieEinstufige() {
        // Der gemeldete Widerspruch: "alles selbst bauen" kam günstiger heraus
        // als "möglichst günstig". Ursache war eine einstufige Rechnung - die
        // Zutaten gingen mit ihrem KAUFPREIS ein, die Frage "was, wenn ich die
        // Zutat auch baue" wurde nie gestellt.
        //
        // Aufbau: eine Baugruppe kostet fertig 1000. Ihre einzige Zutat kostet
        // fertig 1200 - einstufig gerechnet lohnt Bauen also nicht. Diese Zutat
        // lässt sich aber aus Material für 300 herstellen.
        long baugruppe = 500L;
        long zutat = 501L;
        long rohstoff = 502L;
        mitMaterialeffizienz(0);

        when(queryRepo.jitaSell(baugruppe)).thenReturn(1_000.0);
        when(queryRepo.jitaSell(zutat)).thenReturn(1_200.0);
        when(queryRepo.jitaSell(rohstoff)).thenReturn(300.0);
        when(queryRepo.packagedVolumes(any())).thenReturn(java.util.Map.of());

        when(queryRepo.blueprintFor(baugruppe)).thenReturn(new BlueprintInfo(
                600L, "Baugruppe Blueprint", baugruppe, "Baugruppe", 1, 1, 10, 600));
        when(queryRepo.blueprintFor(zutat)).thenReturn(new BlueprintInfo(
                601L, "Zutat Blueprint", zutat, "Zutat", 1, 1, 10, 600));
        when(queryRepo.blueprintFor(rohstoff)).thenReturn(null);

        when(queryRepo.billOfMaterials(baugruppe, 1)).thenReturn(List.of(
                new BomNode(1, zutat, "Zutat", null, 1, "BUILDABLE", 1, 1)));
        when(queryRepo.billOfMaterials(zutat, 1)).thenReturn(List.of(
                new BomNode(1, rohstoff, "Rohstoff", null, 1, "MINERAL", 1, 1)));

        // Einstufig gerechnet: 1200 Material gegen 1000 Kaufpreis - Kaufen.
        // Rekursiv: die Zutat kostet selbst gebaut 300, also Bauen.
        assertThat(service.buildIsCheaper(1L, baugruppe, 0)).isTrue();
    }

    @Test
    @DisplayName("rechnet die Fracht mit - sperrige Fertigteile sind nicht billig")
    void frachtGehtInDieEntscheidungEin() {
        // Der zweite Teil des gemeldeten Falls: fertig gekaufte Capital-Bauteile
        // füllten fünf Sprungfrachterladungen, ihre Zutaten eine. 672 Millionen
        // gegen 41 - eine Entscheidung, die nur die Ware ansieht, übersieht mehr
        // als sie entscheidet.
        long bauteil = 700L;
        long zutat = 701L;
        mitMaterialeffizienz(0);

        when(queryRepo.jitaSell(bauteil)).thenReturn(1_000.0);
        when(queryRepo.jitaSell(zutat)).thenReturn(1_100.0);
        when(queryRepo.blueprintFor(bauteil)).thenReturn(new BlueprintInfo(
                800L, "Bauteil Blueprint", bauteil, "Bauteil", 1, 1, 10, 600));
        when(queryRepo.blueprintFor(zutat)).thenReturn(null);
        when(queryRepo.billOfMaterials(bauteil, 1)).thenReturn(List.of(
                new BomNode(1, zutat, "Zutat", null, 1, "MINERAL", 1, 1)));

        // Ohne Fracht ist Kaufen günstiger: 1000 gegen 1100.
        when(queryRepo.packagedVolumes(any())).thenReturn(java.util.Map.of());
        assertThat(service.buildIsCheaper(1L, bauteil, 0)).isFalse();

        // Das fertige Teil ist sperrig (500 m³), die Zutat winzig (0,01 m³).
        // Bei 460 ISK je Kubikmeter kostet der Kauf 1000 + 230.000, das
        // Selbstbauen 1100 + 4,60. Jetzt kippt die Antwort.
        when(queryRepo.packagedVolumes(java.util.Set.of(bauteil)))
                .thenReturn(java.util.Map.of(bauteil, 500.0));
        when(queryRepo.packagedVolumes(java.util.Set.of(zutat)))
                .thenReturn(java.util.Map.of(zutat, 0.01));
        assertThat(service.buildIsCheaper(1L, bauteil, 460)).isTrue();
    }

    @Test
    @DisplayName("fällt bei einem Kaufpreis von 0 nicht automatisch auf Kaufen")
    void nullpreisEntscheidetNichtFuerKaufen() {
        // Der gefährlichste Fall des gemeldeten Ausfalls. Steht der Kaufpreis
        // des fertigen Teils auf 0, kostet der Fertigkauf scheinbar nichts und
        // unterbietet jeden Bauweg - "Möglichst günstig" empfiehlt dann
        // ausnahmslos Kaufen, und zwar mit derselben Bestimmtheit wie bei einer
        // echten Rechnung. Ohne diese Regel schlüge die Zusicherung um: statt
        // "Bauen ist der einzige bezifferte Weg" käme "Kaufen ist billiger".
        mitMaterialeffizienz(10);
        when(queryRepo.jitaSell(KOMPONENTE)).thenReturn(0.0);
        when(queryRepo.packagedVolumes(any())).thenReturn(java.util.Map.of());

        assertThat(service.shouldBuild(1L, KOMPONENTE, 1, "BUILDABLE",
                BuildStrategy.COST_EFFICIENT)).isTrue();

        // Und die Einzelzeile meldet den fehlenden Preis, statt eine 0 zu zeigen.
        var urteil = service.compare(1L, KOMPONENTE, 1);
        assertThat(urteil.buyCost()).isNull();
        assertThat(urteil.reason()).contains("Kein Marktpreis");
    }

    @Test
    @DisplayName("lässt ein Material mit Preis 0 den Bauweg nicht verbilligen")
    void nullpreisBeimMaterialVerhindertDenVergleich() {
        // Die Gegenrichtung: ein Material zu 0 ISK machte das Selbstbauen
        // künstlich billig. Ohne die Regel käme hier ein Baupreis heraus, in
        // dem 111 Millionen ISK Material schlicht fehlen - und "Bauen lohnt
        // sich" wäre das Ergebnis eines Quellenausfalls, nicht einer Rechnung.
        mitMaterialeffizienz(10);
        when(queryRepo.jitaSell(REGULATOR)).thenReturn(0.0);

        var urteil = service.compare(1L, KOMPONENTE, 1);

        assertThat(urteil.buildCost()).isNull();
        assertThat(urteil.buildCheaper()).isFalse();
        assertThat(urteil.reason()).contains("Marktpreis");
    }

    @Test
    @DisplayName("fällt bei unbekannter Voreinstellung auf Kaufen zurück")
    void unbekannteVoreinstellung() {
        // Eine veraltete Oberfläche soll den Auftrag nicht unbrauchbar machen.
        assertThat(BuildStrategy.fromName("QUATSCH")).isEqualTo(BuildStrategy.BUY_ALL);
        assertThat(BuildStrategy.fromName(null)).isEqualTo(BuildStrategy.BUY_ALL);
        assertThat(BuildStrategy.fromName("cost_efficient"))
                .isEqualTo(BuildStrategy.COST_EFFICIENT);
    }
}
