package com.eve.own.auth.backend.domain.industry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Eine Stueckliste ist ein Netz, kein Baum.
 *
 * <p>Anlass ist ein gemeldeter Fehler mit zwei Gesichtern. Auf dem Bildschirm
 * stand Life Support Backup Unit unter "Vorprodukte", waehrend Reinforced Carbon
 * Fiber - das die Unit erst moeglich macht - eine Gruppe <em>weiter unten</em>
 * stand. Unsichtbar dahinter lag der schwerere Teil: Die Mengen unterhalb eines
 * mehrfach gebrauchten Teils waren zu klein.</p>
 *
 * <p>Beides hat dieselbe Ursache. Der alte Aufbau loeste einen Knoten auf,
 * sobald er ihm das erste Mal begegnete, und addierte bei jedem weiteren Treffer
 * nur noch die Menge. Wessen Kinder da schon gerechnet waren, blieb auf der
 * ersten Teilsumme sitzen. Im gemessenen Phoenix-Auftrag: Reinforced Carbon
 * Fiber hatte siebzehn Verbraucher, stand mit 23.097 in der Tabelle - und seine
 * Kinder mit 7.200 statt 23.200.</p>
 *
 * <p>Die Zahlen hier sind kleiner und nachrechenbar, der Bau ist derselbe:
 * zwei Teile brauchen dasselbe Vorprodukt.</p>
 */
class IndustryStuecklisteNetzTest {

    private static final long ORDER = 1L;

    // Die Typen aus dem gemeldeten Auftrag, damit der Fall benennbar bleibt.
    private static final long SEAL = 57478L;      // Auto-Integrity Preservation Seal
    private static final long UNIT = 57486L;      // Life Support Backup Unit
    private static final long RCF = 57457L;       // Reinforced Carbon Fiber
    private static final long CARBON = 57453L;    // Carbon Fiber
    private static final long POLYMER = 57455L;   // Thermosetting Polymer

    private IndustryOrderRequirementRepository requirementRepo;
    private IndustryQueryRepository queryRepo;
    private IndustryPlanningService planning;
    private IndustryOrderService service;
    private List<IndustryOrderRequirement> tabelle;

    @BeforeEach
    void setUp() {
        IndustryOrderRepository orderRepo = Mockito.mock(IndustryOrderRepository.class);
        requirementRepo = Mockito.mock(IndustryOrderRequirementRepository.class);
        queryRepo = Mockito.mock(IndustryQueryRepository.class);
        MyAssetService assetService = Mockito.mock(MyAssetService.class);
        planning = Mockito.mock(IndustryPlanningService.class);

        service = new IndustryOrderService(
                orderRepo, requirementRepo,
                Mockito.mock(IndustryOrderBaselineRepository.class),
                Mockito.mock(IndustryOrderJobRepository.class),
                Mockito.mock(IndustryJobRepository.class),
                queryRepo, planning, assetService,
                Mockito.mock(ProcurementService.class),
                Mockito.mock(BlueprintCheckService.class),
                Mockito.mock(BuildVsBuyService.class));

        // Ebene eins: beide Bauteile haengen unmittelbar am Endprodukt.
        tabelle = new ArrayList<>();
        tabelle.add(zeile(SEAL, "Auto-Integrity Preservation Seal", 368, 1, "BUILD"));
        tabelle.add(zeile(UNIT, "Life Support Backup Unit", 184, 1, "BUY"));
        // Tiefere Zeilen werden beim Neuaufbau geloescht, ihre Entscheidung aber
        // gemerkt. Ohne diese Zeile stuende Reinforced Carbon Fiber nach der
        // Neuanlage auf "Kaufen" und der Baum endete dort - der Fall waere gar
        // nicht abgebildet.
        tabelle.add(zeile(RCF, "Reinforced Carbon Fiber", 0, 2, "BUILD"));

        Mockito.doAnswer(a -> {
            a.<Iterable<IndustryOrderRequirement>>getArgument(0).forEach(neu -> {
                tabelle.removeIf(alt -> alt.getTypeId().equals(neu.getTypeId()));
                tabelle.add(neu);
            });
            return null;
        }).when(requirementRepo).saveAll(any());
        Mockito.doAnswer(a -> {
            a.<Iterable<IndustryOrderRequirement>>getArgument(0)
                    .forEach(weg -> tabelle.removeIf(r -> r.getTypeId().equals(weg.getTypeId())));
            return null;
        }).when(requirementRepo).deleteAll(any());

        when(assetService.resolveMainId(anyLong())).thenReturn(7L);
        when(orderRepo.findByIdAndAccountId(ORDER, 7L)).thenReturn(Optional.of(auftrag()));
        when(requirementRepo.findByOrderIdOrderByDepthAscQuantityNeededDesc(ORDER))
                .thenAnswer(a -> new ArrayList<>(tabelle));
        when(requirementRepo.findByOrderIdAndTypeId(anyLong(), anyLong()))
                .thenAnswer(a -> tabelle.stream()
                        .filter(r -> r.getTypeId().equals(a.getArgument(1)))
                        .findFirst());
        when(requirementRepo.findByOrderIdAndDepth(anyLong(), anyInt())).thenReturn(List.of());
        when(planning.holdingsFor(any(), any(), any())).thenReturn(Map.of());
        when(queryRepo.blueprintFor(anyLong())).thenReturn(null);
        when(queryRepo.billOfMaterials(anyLong(), anyInt())).thenReturn(List.of());

        // Beide Bauteile brauchen je zehn Reinforced Carbon Fiber je Stueck -
        // das ist der Knoten mit zwei Verbrauchern.
        when(queryRepo.billOfMaterials(SEAL, 1))
                .thenReturn(List.of(kind(RCF, "Reinforced Carbon Fiber", 10)));
        when(queryRepo.billOfMaterials(UNIT, 1))
                .thenReturn(List.of(kind(RCF, "Reinforced Carbon Fiber", 10)));
        when(queryRepo.billOfMaterials(RCF, 1)).thenReturn(List.of(
                kind(CARBON, "Carbon Fiber", 1),
                kind(POLYMER, "Thermosetting Polymer", 1)));
    }

    @Test
    @DisplayName("zaehlt beide Verbraucher, bevor es die Zutaten rechnet")
    void mengeUnterhalbEinesGeteiltenKnotens() {
        // Der Kern des Fehlers. Frueher wurde Reinforced Carbon Fiber
        // aufgeloest, sobald der erste Verbraucher es anforderte; der Beitrag
        // des zweiten kam danach und erreichte die Zutaten nie mehr.
        service.setDecision(1L, ORDER, new IndustryDtos.DecisionRequest(UNIT, "BUILD"));

        // 368 x 10 + 184 x 10 = 5.520
        assertThat(menge(RCF)).isEqualTo(5_520);
        // und die Zutaten aus der VOLLEN Menge, nicht aus dem ersten Beitrag
        assertThat(menge(CARBON)).isEqualTo(5_520);
        assertThat(menge(POLYMER)).isEqualTo(5_520);
    }

    @Test
    @DisplayName("stellt jedes Material auf eine kleinere Stufe als sein Produkt")
    void stufeStehtNieUeberDemVerbraucher() {
        // Die Zusicherung, auf die sich die Anzeige verlaesst. Genau sie war
        // verletzt: Life Support Backup Unit stand auf derselben Stufe wie das
        // Vorprodukt, das es braucht.
        service.setDecision(1L, ORDER, new IndustryDtos.DecisionRequest(UNIT, "BUILD"));

        // Beschafft wird auf Stufe null, gebaut darueber.
        assertThat(stufe(CARBON)).isZero();
        assertThat(stufe(POLYMER)).isZero();
        assertThat(stufe(RCF)).isEqualTo(1);
        assertThat(stufe(SEAL)).isEqualTo(2);
        // Die eine Zusicherung, die vorher fehlschlug: die Unit stand auf
        // derselben Stufe wie Carbon Fiber, also UNTER ihrem eigenen Vorprodukt.
        assertThat(stufe(UNIT)).isEqualTo(2);

        assertThat(stufe(UNIT)).isGreaterThan(stufe(RCF));
        assertThat(stufe(SEAL)).isGreaterThan(stufe(RCF));
        assertThat(stufe(RCF)).isGreaterThan(stufe(CARBON));
    }

    @Test
    @DisplayName("laesst die Menge beim zweiten Neurechnen nicht weiterwachsen")
    void keineAnhaeufungBeiWiederholung() {
        // Ebene eins wird nicht geloescht, sondern behaelt ihre Zeile. Ohne
        // Rueckfallmarke addiert jede Runde die Beitraege der Unterzweige
        // erneut auf denselben Stand - und Tritanium, das sowohl unmittelbares
        // Material als auch Bestandteil mehrerer Bauteile ist, waechst mit
        // jedem Klick.
        service.setDecision(1L, ORDER, new IndustryDtos.DecisionRequest(UNIT, "BUILD"));
        long nachEinmal = menge(RCF);
        service.setDecision(1L, ORDER, new IndustryDtos.DecisionRequest(UNIT, "BUILD"));

        assertThat(menge(RCF)).isEqualTo(nachEinmal);
        assertThat(menge(CARBON)).isEqualTo(nachEinmal);
    }

    @Test
    @DisplayName("erfindet bei einem Kreisbezug keine Reihenfolge")
    void kreisbezugBeendetSichSelbst() {
        // Die Kanten stammen aus fremden Stammdaten. Zwei Blaupausen, die
        // einander als Material fuehren, duerfen weder die Schleife noch den
        // Auftrag zerstoeren.
        when(queryRepo.billOfMaterials(CARBON, 1))
                .thenReturn(List.of(kind(RCF, "Reinforced Carbon Fiber", 1)));
        tabelle.add(zeile(CARBON, "Carbon Fiber", 0, 3, "BUILD"));

        service.setDecision(1L, ORDER, new IndustryDtos.DecisionRequest(UNIT, "BUILD"));

        // Terminiert und liefert jede Zeile - mehr wird bei einem Kreis nicht
        // zugesichert, und mehr behauptet die Anzeige dann auch nicht.
        assertThat(tabelle).extracting(IndustryOrderRequirement::getTypeId)
                .contains(SEAL, UNIT, RCF, CARBON, POLYMER);
    }

    @Test
    @DisplayName("haelt Material fuer erledigt, das im fertigen Bauteil steckt")
    void verbautesMaterialFaelltNichtZurueckAufDieEinkaufsliste() {
        // Der gemeldete Fehler: "Wenn man etwas gebaut hatte und die
        // Materialien gibt es nicht mehr, weil sie verbraucht wurden, geht die
        // Beschaffung von vorn los." Die Zutaten wurden aus der VOLLEN Menge
        // des Bauteils gerechnet, ohne den Bestand je anzusehen.
        //
        // Hier liegen beide Bauteile fertig im Hangar. Ihre Zutaten muessen
        // damit erledigt sein - und zwar ohne dass ein einziger Job betrachtet
        // wird: Ein fertiges Bauteil im Hangar ist eine Messung.
        mitBlaupausen();
        bestandLiefert(Map.of(
                SEAL, new IndustryQueryRepository.Holding(SEAL, 368, 0, 1),
                UNIT, new IndustryQueryRepository.Holding(UNIT, 184, 0, 1)));

        service.setDecision(1L, ORDER, new IndustryDtos.DecisionRequest(UNIT, "BUILD"));
        var detail = service.detail(1L, ORDER);

        var rcf = zeileAus(detail, RCF);
        assertThat(rcf.needed()).isEqualTo(5_520);
        // Nichts davon liegt greifbar herum ...
        assertThat(rcf.have()).isZero();
        // ... aber alles steckt in den fertigen Bauteilen.
        assertThat(rcf.alreadyBuilt()).isEqualTo(5_520);
        assertThat(rcf.missing()).isZero();
    }

    @Test
    @DisplayName("haelt beide Zahlen auseinander: im Hangar und schon verbaut")
    void hangarUndVerbautBleibenGetrennt() {
        // Eine Zahl, die beides vermischt, waere eine Verschlechterung: Dann
        // liesse sich nicht mehr sagen, ob noch etwas zu holen ist.
        mitBlaupausen();
        bestandLiefert(Map.of(
                SEAL, new IndustryQueryRepository.Holding(SEAL, 368, 0, 1),
                RCF, new IndustryQueryRepository.Holding(RCF, 500, 0, 1)));

        service.setDecision(1L, ORDER, new IndustryDtos.DecisionRequest(UNIT, "BUILD"));
        var detail = service.detail(1L, ORDER);

        var rcf = zeileAus(detail, RCF);
        assertThat(rcf.have()).isEqualTo(500);
        assertThat(rcf.alreadyBuilt()).isEqualTo(3_680);
        assertThat(rcf.missing()).isEqualTo(5_520 - 500 - 3_680);
    }

    @Test
    @DisplayName("schreibt nichts gut, solange nichts fertig ist")
    void ohneFertigeBauteileAendertSichNichts() {
        // Der Gegenfall. Ohne ihn koennte die Rechnung pauschal gutschreiben
        // und beide Tests darueber waeren trotzdem gruen.
        mitBlaupausen();
        bestandLiefert(Map.of());

        service.setDecision(1L, ORDER, new IndustryDtos.DecisionRequest(UNIT, "BUILD"));
        var detail = service.detail(1L, ORDER);

        var rcf = zeileAus(detail, RCF);
        assertThat(rcf.alreadyBuilt()).isZero();
        assertThat(rcf.missing()).isEqualTo(5_520);
    }

    @Test
    @DisplayName("ignoriert auf Wunsch den gesamten eigenen Bestand")
    void ohneEigeneAssetsStehtDerVolleBedarf() {
        // "Was kostet mich das komplett von null" - ohne dafuer einen zweiten
        // Auftrag anlegen zu muessen.
        mitBlaupausen();
        bestandLiefert(Map.of(
                SEAL, new IndustryQueryRepository.Holding(SEAL, 368, 0, 1),
                UNIT, new IndustryQueryRepository.Holding(UNIT, 184, 0, 1),
                RCF, new IndustryQueryRepository.Holding(RCF, 500, 0, 1)));

        service.setDecision(1L, ORDER, new IndustryDtos.DecisionRequest(UNIT, "BUILD"));
        var detail = service.detail(1L, ORDER, true);

        var rcf = zeileAus(detail, RCF);
        assertThat(rcf.have()).isZero();
        // Die eigentliche Falle: Die Gutschrift fuer fertige Bauteile stammt
        // ebenfalls aus dem Bestand. Bliebe sie stehen, waehrend have auf null
        // faellt, waere das Ergebnis weder "von null" noch "mit allem".
        assertThat(rcf.alreadyBuilt()).isZero();
        assertThat(rcf.missing()).isEqualTo(rcf.needed());
    }

    @Test
    @DisplayName("laesst den Auftrag dabei unangetastet")
    void derBlickAendertNichtsAmAuftrag() {
        // Der Schalter ist ein Blick, keine Eigenschaft. Wer ihn setzt und
        // wieder wegnimmt, muss dieselben Zahlen wie vorher sehen.
        mitBlaupausen();
        bestandLiefert(Map.of(SEAL, new IndustryQueryRepository.Holding(SEAL, 368, 0, 1)));
        service.setDecision(1L, ORDER, new IndustryDtos.DecisionRequest(UNIT, "BUILD"));

        long vorher = zeileAus(service.detail(1L, ORDER), RCF).alreadyBuilt();
        service.detail(1L, ORDER, true);
        long nachher = zeileAus(service.detail(1L, ORDER), RCF).alreadyBuilt();

        assertThat(vorher).isEqualTo(3_680);
        assertThat(nachher).isEqualTo(vorher);
    }

    // ===========================================================
    //  Gerüst
    // ===========================================================

    private long menge(long typeId) {
        return zeileVon(typeId).getQuantityNeeded();
    }

    private int stufe(long typeId) {
        Integer s = zeileVon(typeId).getBuildLevel();
        assertThat(s).as("Stufe von %d ist nicht gesetzt", typeId).isNotNull();
        return s;
    }

    private IndustryOrderRequirement zeileVon(long typeId) {
        return tabelle.stream()
                .filter(r -> r.getTypeId() == typeId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Zeile fehlt: " + typeId));
    }

    /** Jede Blaupause liefert ein Stueck je Lauf - dann rechnet sich alles glatt. */
    private void mitBlaupausen() {
        when(queryRepo.blueprintFor(anyLong())).thenAnswer(a -> new IndustryQueryRepository
                .BlueprintInfo(9000L, "BP", a.getArgument(0), "X", 1, 1, 1000, 60));
        when(planning.contextFor(any(), any())).thenReturn(neutralerKontext());
    }

    private void bestandLiefert(Map<Long, IndustryQueryRepository.Holding> bestand) {
        when(planning.holdingsFor(any(), any(), any())).thenReturn(bestand);
    }

    private static IndustryDtos.RequirementDto zeileAus(
            IndustryDtos.OrderDetailDto detail, long typeId) {
        return detail.requirements().stream()
                .filter(r -> r.typeId() == typeId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Zeile fehlt: " + typeId));
    }

    /** Ohne Boni: dann ist die Materialmenge genau die der Stammdaten. */
    private static IndustryContext neutralerKontext() {
        return new IndustryContext(1, 0, 0,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ONE, 0, 0, BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static IndustryOrder auftrag() {
        IndustryOrder o = new IndustryOrder();
        o.setId(ORDER);
        o.setAccountId(7L);
        o.setProductTypeId(19726L);
        o.setProductName("Phoenix");
        o.setTargetQuantity(1L);
        o.setStatus("ACTIVE");
        o.setCreatedAt(Instant.parse("2026-08-12T10:00:00Z"));
        return o;
    }

    private static IndustryOrderRequirement zeile(long typeId, String name, long menge,
                                                  int tiefe, String entscheidung) {
        IndustryOrderRequirement r = new IndustryOrderRequirement();
        r.setOrderId(ORDER);
        r.setTypeId(typeId);
        r.setTypeName(name);
        r.setQuantityNeeded(menge);
        r.setBaseQuantity(menge);
        r.setDepth(tiefe);
        r.setSourceKind("BUILDABLE");
        r.setDecision(entscheidung);
        return r;
    }

    /** Ein Stueck je Lauf: dann sind Menge je Stueck und je Lauf gleich. */
    private static BomNode kind(long typeId, String name, double mengeJeStueck) {
        return new BomNode(1, typeId, name, null, mengeJeStueck, "BUILDABLE", 1,
                (long) Math.ceil(mengeJeStueck));
    }

}
