package com.eve.own.auth.backend.domain.industry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.assets.service.MyAssetService;
import com.eve.own.auth.backend.domain.industry.dto.IndustryDtos;
import com.eve.own.auth.backend.domain.industry.entity.IndustryOrder;
import com.eve.own.auth.backend.domain.industry.entity.IndustryOrderRequirement;
import com.eve.own.auth.backend.domain.industry.repository.IndustryJobRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryOrderBaselineRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryOrderJobRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryOrderRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryOrderRequirementRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository.BomNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Die Kaufen/Bauen-Entscheidung und der Neuaufbau der Ebenen.
 *
 * <p>Anlass ist ein gemeldeter Fehler: wer von "Bauen" zurueck auf "Kaufen"
 * stellte, wurde die aufgeklappte Ebene nie wieder los.</p>
 */
class IndustryOrderDecisionTest {

    private static final long ORDER = 1L;
    private static final long KOMPONENTE = 100L;
    private static final long ZWEITE_KOMPONENTE = 200L;
    private static final long TRITANIUM = 34L;

    private IndustryOrderRepository orderRepo;
    private IndustryOrderRequirementRepository requirementRepo;
    private IndustryQueryRepository queryRepo;
    private MyAssetService assetService;
    private IndustryPlanningService planning;
    private IndustryOrderBaselineRepository baselineRepo;
    private IndustryOrderService service;

    /** Steht fuer die Datenbank: was gespeichert wurde, ist danach auch abrufbar. */
    private List<IndustryOrderRequirement> tabelle;

    @BeforeEach
    void setUp() {
        orderRepo = Mockito.mock(IndustryOrderRepository.class);
        requirementRepo = Mockito.mock(IndustryOrderRequirementRepository.class);
        queryRepo = Mockito.mock(IndustryQueryRepository.class);
        assetService = Mockito.mock(MyAssetService.class);
        planning = Mockito.mock(IndustryPlanningService.class);
        baselineRepo = Mockito.mock(IndustryOrderBaselineRepository.class);

        service = new IndustryOrderService(
                orderRepo, requirementRepo, baselineRepo,
                Mockito.mock(IndustryOrderJobRepository.class),
                Mockito.mock(IndustryJobRepository.class),
                queryRepo, planning, assetService,
                Mockito.mock(ProcurementService.class),
                Mockito.mock(BlueprintCheckService.class),
                Mockito.mock(BuildVsBuyService.class));

        tabelle = new ArrayList<>();
        tabelle.add(zeile(KOMPONENTE, "Auto-Integrity Preservation Seal", 150, 1, "BUILDABLE", "BUY"));

        when(assetService.resolveMainId(anyLong())).thenReturn(7L);
        when(orderRepo.findByIdAndAccountId(ORDER, 7L)).thenReturn(Optional.of(auftrag()));
        when(requirementRepo.findByOrderIdOrderByDepthAscQuantityNeededDesc(ORDER))
                .thenAnswer(a -> new ArrayList<>(tabelle));
        when(requirementRepo.findByOrderIdAndTypeId(anyLong(), anyLong()))
                .thenAnswer(a -> tabelle.stream()
                        .filter(r -> r.getTypeId().equals(a.getArgument(1)))
                        .findFirst());
        when(requirementRepo.findByOrderIdAndDepth(anyLong(), anyInt())).thenReturn(List.of());
        when(planning.holdingsFor(any(), any(), any())).thenReturn(java.util.Map.of());
        when(queryRepo.billOfMaterials(anyLong(), anyInt())).thenReturn(List.of());
        when(queryRepo.blueprintFor(anyLong())).thenReturn(null);
    }

    private static IndustryOrder auftrag() {
        IndustryOrder o = new IndustryOrder();
        o.setId(ORDER);
        o.setAccountId(7L);
        o.setProductTypeId(638L);
        o.setProductName("Raven");
        o.setTargetQuantity(1L);
        o.setStatus("ACTIVE");
        o.setCreatedAt(Instant.parse("2026-08-10T10:00:00Z"));
        return o;
    }

    private static IndustryOrderRequirement zeile(long typeId, String name, long menge,
                                                  int tiefe, String herkunft, String entscheidung) {
        IndustryOrderRequirement r = new IndustryOrderRequirement();
        r.setOrderId(ORDER);
        r.setTypeId(typeId);
        r.setTypeName(name);
        r.setQuantityNeeded(menge);
        r.setDepth(tiefe);
        r.setSourceKind(herkunft);
        r.setDecision(entscheidung);
        return r;
    }

    private static BomNode kind(long typeId, String name, double mengeJeStueck, String herkunft) {
        // Ein Stueck je Lauf: dann sind beide Mengen gleich.
        return new BomNode(1, typeId, name, null, mengeJeStueck, herkunft, 1,
                (long) Math.ceil(mengeJeStueck));
    }

    /** Fängt ab, was der Dienst speichert, und legt es in die Tabelle zurück. */
    @SuppressWarnings("unchecked")
    private List<IndustryOrderRequirement> gespeicherteZeilen() {
        ArgumentCaptor<Iterable<IndustryOrderRequirement>> captor = ArgumentCaptor.captor();
        verify(requirementRepo, Mockito.atLeastOnce()).saveAll(captor.capture());
        List<IndustryOrderRequirement> letzte = new ArrayList<>();
        captor.getValue().forEach(letzte::add);
        return letzte;
    }

    @Test
    @DisplayName("klappt bei Bauen genau eine Ebene auf")
    void bauenKlapptEineEbeneAuf() {
        when(queryRepo.billOfMaterials(KOMPONENTE, 1)).thenReturn(List.of(
                kind(TRITANIUM, "Tritanium", 10, "MINERAL")));

        service.setDecision(1L, ORDER, new IndustryDtos.DecisionRequest(KOMPONENTE, "BUILD"));

        List<IndustryOrderRequirement> gespeichert = gespeicherteZeilen();
        assertThat(gespeichert).extracting(IndustryOrderRequirement::getTypeId)
                .contains(TRITANIUM);
        assertThat(gespeichert).filteredOn(r -> r.getTypeId() == TRITANIUM)
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.getDepth()).isEqualTo(2);
                    assertThat(r.getQuantityNeeded()).isEqualTo(1500);
                });
    }

    @Test
    @DisplayName("entfernt die Ebene wieder, wenn man zurück auf Kaufen stellt")
    void kaufenEntferntDieEbeneWieder() {
        // Genau der gemeldete Fehler: die aufgeklappten Zeilen blieben stehen.
        tabelle.add(zeile(TRITANIUM, "Tritanium", 1500, 2, "MINERAL", "BUY"));
        tabelle.getFirst().setDecision("BUILD");

        service.setDecision(1L, ORDER, new IndustryDtos.DecisionRequest(KOMPONENTE, "BUY"));

        ArgumentCaptor<Iterable<IndustryOrderRequirement>> geloescht = ArgumentCaptor.captor();
        verify(requirementRepo).deleteAll(geloescht.capture());
        List<IndustryOrderRequirement> weg = new ArrayList<>();
        geloescht.getValue().forEach(weg::add);

        assertThat(weg).extracting(IndustryOrderRequirement::getTypeId).containsExactly(TRITANIUM);
        assertThat(gespeicherteZeilen()).extracting(IndustryOrderRequirement::getTypeId)
                .doesNotContain(TRITANIUM);
    }

    @Test
    @DisplayName("addiert die Menge, wenn ein Material aus zwei gebauten Zweigen kommt")
    void addiertMengenAusMehrerenZweigen() {
        // Der zweite Zweig wurde zuvor stillschweigend übersprungen - die
        // ausgewiesene Menge war dann zu niedrig.
        tabelle.getFirst().setDecision("BUILD");
        tabelle.add(zeile(ZWEITE_KOMPONENTE, "Life Support Backup Unit", 75, 1, "BUILDABLE", "BUILD"));

        when(queryRepo.billOfMaterials(KOMPONENTE, 1)).thenReturn(List.of(
                kind(TRITANIUM, "Tritanium", 10, "MINERAL")));
        when(queryRepo.billOfMaterials(ZWEITE_KOMPONENTE, 1)).thenReturn(List.of(
                kind(TRITANIUM, "Tritanium", 4, "MINERAL")));

        service.setDecision(1L, ORDER, new IndustryDtos.DecisionRequest(KOMPONENTE, "BUILD"));

        assertThat(gespeicherteZeilen()).filteredOn(r -> r.getTypeId() == TRITANIUM)
                .singleElement()
                // 150 * 10 aus dem einen Zweig, 75 * 4 aus dem anderen.
                .satisfies(r -> assertThat(r.getQuantityNeeded()).isEqualTo(1500 + 300));
    }

    @Test
    @DisplayName("behält eine tiefere Bauen-Entscheidung bei")
    void behaeltTiefereEntscheidungen() {
        tabelle.getFirst().setDecision("BUILD");
        tabelle.add(zeile(ZWEITE_KOMPONENTE, "Untermodul", 10, 2, "BUILDABLE", "BUILD"));

        when(queryRepo.billOfMaterials(KOMPONENTE, 1)).thenReturn(List.of(
                kind(ZWEITE_KOMPONENTE, "Untermodul", 1, "BUILDABLE")));
        when(queryRepo.billOfMaterials(ZWEITE_KOMPONENTE, 1)).thenReturn(List.of(
                kind(TRITANIUM, "Tritanium", 5, "MINERAL")));

        service.setDecision(1L, ORDER, new IndustryDtos.DecisionRequest(KOMPONENTE, "BUILD"));

        List<IndustryOrderRequirement> gespeichert = gespeicherteZeilen();
        // Die Entscheidung eine Ebene tiefer darf nicht verloren gehen, sonst
        // nimmt das Werkzeug dem Nutzer bei jedem Klick weiter oben seine Arbeit weg.
        assertThat(gespeichert).filteredOn(r -> r.getTypeId() == ZWEITE_KOMPONENTE)
                .singleElement()
                .satisfies(r -> assertThat(r.getDecision()).isEqualTo("BUILD"));
        assertThat(gespeichert).extracting(IndustryOrderRequirement::getTypeId)
                .contains(TRITANIUM);
    }

    @Test
    @DisplayName("rechnet die Materialeffizienz der Bauteil-Blaupause ein")
    void materialeffizienzDerKomponenteGiltAuch() {
        // Ohne diese Rechnung stand in der Tabelle immer die Menge fuer ME 0.
        // Bei einem Capital Core Temperature Regulator entscheidet genau dieser
        // Unterschied darueber, ob Eigenbau acht Millionen kostet oder zehn spart.
        var bpInfo = new IndustryQueryRepository.BlueprintInfo(
                101L, "Komponente Blueprint", KOMPONENTE, "Komponente", 1, 1, 10, 600);
        when(queryRepo.blueprintFor(KOMPONENTE)).thenReturn(bpInfo);
        when(planning.contextFor(any(), any())).thenReturn(
                new IndustryContext(1, 10, 0,
                        java.math.BigDecimal.ONE, java.math.BigDecimal.ONE,
                        java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                        java.math.BigDecimal.ONE, 0, 0, java.math.BigDecimal.ONE,
                        java.math.BigDecimal.ZERO, java.math.BigDecimal.ONE,
                        java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO));
        when(queryRepo.billOfMaterials(KOMPONENTE, 1)).thenReturn(List.of(
                kind(TRITANIUM, "Tritanium", 35, "MINERAL")));

        service.setDecision(1L, ORDER, new IndustryDtos.DecisionRequest(KOMPONENTE, "BUILD"));

        assertThat(gespeicherteZeilen()).filteredOn(r -> r.getTypeId() == TRITANIUM)
                .singleElement()
                // 150 Laeufe * 35 * 0,9 = 4725 statt 5250 bei ME 0.
                .satisfies(r -> assertThat(r.getQuantityNeeded()).isEqualTo(4725));
    }

    // ===========================================================
    //  Neu berechnen
    // ===========================================================

    /** Was die Vorschau beim Neuberechnen liefert - mit frischer Menge auf Ebene eins. */
    private void vorschauLiefert(long mengeEbeneEins) {
        var zusammenfassung = new IndustryDtos.PlanSummaryDto(
                1, 1, 1, 3600, 1, 0, 10, 20, true, true);
        var zeile = new IndustryDtos.RequirementDto(
                KOMPONENTE, "Auto-Integrity Preservation Seal", mengeEbeneEins, 0,
                mengeEbeneEins, "BUILDABLE", true, "BUY", 1, null, null, false, 0, 0, 0);
        when(planning.preview(anyLong(), anyLong(), anyLong(), anyInt(), any())).thenReturn(
                new IndustryDtos.PlanPreviewDto(638L, "Raven", 1,
                        zusammenfassung, List.of(zeile)));
        // Die Vorschau schreibt ueber saveAll; danach muss dieselbe Zeile
        // wieder aus der Tabelle kommen, sonst prueft der Test an der Luft vorbei.
        Mockito.doAnswer(a -> {
            tabelle.clear();
            a.<Iterable<IndustryOrderRequirement>>getArgument(0).forEach(tabelle::add);
            return null;
        }).when(requirementRepo).saveAll(any());
    }

    @Test
    @DisplayName("holt beim Neuberechnen die Menge auf Ebene eins neu")
    void neuberechnenErneuertEbeneEins() {
        // Der Bedarf ist eingefroren, damit der Fortschrittsbalken nicht bei
        // jedem Neuladen springt. Der Preis dafuer: eine korrigierte Rechnung
        // erreicht einen bestehenden Auftrag nie - es sei denn, es gibt diesen
        // Weg zurueck. rebuildExpansions allein genuegt nicht, es ruehrt Ebene
        // eins ueberhaupt nicht an.
        vorschauLiefert(9_999);

        service.recalculate(1L, ORDER);

        assertThat(tabelle).filteredOn(r -> r.getTypeId() == KOMPONENTE)
                .singleElement()
                .satisfies(r -> assertThat(r.getQuantityNeeded()).isEqualTo(9_999));
        verify(requirementRepo).deleteByOrderId(ORDER);
    }

    @Test
    @DisplayName("behält beim Neuberechnen die eigenen Kaufen/Bauen-Entscheidungen")
    void neuberechnenBehaeltEntscheidungen() {
        // Die Entscheidungen sind die Arbeit des Nutzers, nicht das Ergebnis
        // einer Rechnung. Wer sie beim Neuberechnen verliert, klickt einen
        // Titan-Auftrag von Hand neu zusammen.
        tabelle.getFirst().setDecision("BUILD");
        tabelle.add(zeile(ZWEITE_KOMPONENTE, "Untermodul", 10, 2, "BUILDABLE", "BUILD"));
        vorschauLiefert(150);

        when(queryRepo.billOfMaterials(KOMPONENTE, 1)).thenReturn(List.of(
                kind(ZWEITE_KOMPONENTE, "Untermodul", 1, "BUILDABLE")));
        when(queryRepo.billOfMaterials(ZWEITE_KOMPONENTE, 1)).thenReturn(List.of(
                kind(TRITANIUM, "Tritanium", 5, "MINERAL")));

        service.recalculate(1L, ORDER);

        assertThat(tabelle).filteredOn(r -> r.getTypeId() == KOMPONENTE)
                .singleElement()
                .satisfies(r -> assertThat(r.getDecision()).isEqualTo("BUILD"));
        // Auch die Entscheidung auf Ebene zwei - obwohl die Zeile zwischendurch
        // geloescht war und die Vorschau sie gar nicht kennt.
        assertThat(tabelle).filteredOn(r -> r.getTypeId() == ZWEITE_KOMPONENTE)
                .singleElement()
                .satisfies(r -> assertThat(r.getDecision()).isEqualTo("BUILD"));
        assertThat(tabelle).extracting(IndustryOrderRequirement::getTypeId)
                .contains(TRITANIUM);
    }

    @Test
    @DisplayName("übernimmt beim Neuberechnen die inzwischen erforschte Blaupause")
    void neuberechnenErneuertDieBlaupausendaten() {
        // Wer seine Blaupause erforscht, will das im Auftrag sehen - sonst
        // rechnet der Assistent bis in alle Ewigkeit mit ME 0.
        vorschauLiefert(150);

        service.recalculate(1L, ORDER);

        ArgumentCaptor<IndustryOrder> gespeichert = ArgumentCaptor.captor();
        verify(orderRepo).save(gespeichert.capture());
        assertThat(gespeichert.getValue().getMaterialEfficiency()).isEqualTo(10);
        assertThat(gespeichert.getValue().getTimeEfficiency()).isEqualTo(20);
        assertThat(gespeichert.getValue().getBlueprintOwned()).isTrue();
    }

    @Test
    @DisplayName("rührt beim Neuberechnen die Nullmessung nicht an")
    void neuberechnenLaesstDieNullmessungStehen() {
        // Die Nullmessung haelt fest, was beim Anlegen schon im Hangar lag.
        // Sie jetzt neu zu erfassen wuerde jeden bisherigen Fortschritt
        // stillschweigend auf null zuruecksetzen.
        vorschauLiefert(150);

        service.recalculate(1L, ORDER);

        Mockito.verifyNoInteractions(baselineRepo);
    }

    @Test
    @DisplayName("weist Bauen bei einem PI-Gut ab")
    void piLaesstSichNichtBauen() {
        tabelle.add(zeile(999L, "Nanites", 200, 2, "PI", "BUY"));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                service.setDecision(1L, ORDER, new IndustryDtos.DecisionRequest(999L, "BUILD"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nanites");
    }
}
