package com.eve.own.auth.backend.domain.industry.service;

import com.eve.own.auth.backend.domain.assets.service.MyAssetService;
import com.eve.own.auth.backend.domain.industry.IndustryActivity;
import com.eve.own.auth.backend.domain.industry.dto.IndustryDtos;
import com.eve.own.auth.backend.domain.industry.entity.IndustryOrder;
import com.eve.own.auth.backend.domain.industry.entity.IndustryJob;
import com.eve.own.auth.backend.domain.industry.entity.IndustryOrderBaseline;
import com.eve.own.auth.backend.domain.industry.entity.IndustryOrderJob;
import com.eve.own.auth.backend.domain.industry.entity.IndustryOrderRequirement;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository.Holding;
import com.eve.own.auth.backend.domain.industry.repository.IndustryOrderBaselineRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryJobRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryOrderJobRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryOrderRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryOrderRequirementRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Legt Bauauftraege an und verfolgt sie.
 *
 * <p>Drei Entscheidungen halten die Zahlen ehrlich:</p>
 * <ol>
 *   <li><b>Nullmessung.</b> Was bei Anlage schon im Hangar lag, zaehlt nicht als
 *       gebaut. Ohne sie stuende beim allerersten Aufruf ein Fortschritt da, den
 *       niemand erarbeitet hat.</li>
 *   <li><b>Eingefrorener Bedarf.</b> Die Bedarfstabelle wird einmal gerechnet und
 *       gespeichert. Sonst springt der Balken beim blossen Neuladen, weil sich
 *       Preise oder Blaupausenforschung inzwischen geaendert haben.</li>
 *   <li><b>Fortschritt aus dem Jobbuch, Deckung aus den Hangars.</b> Wer ein
 *       fertiges Schiff verkauft, hat es trotzdem gebaut - ein an Bestaenden
 *       gemessener Fortschritt liefe rueckwaerts. Die Materialdeckung dagegen
 *       <em>darf</em> sinken, sie ist eine Bestandsaussage.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndustryOrderService {

    /** Ein frisch angelegter Auftrag laeuft sofort - ein Entwurfszustand hilft niemandem. */
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DONE = "DONE";
    private static final String STATUS_CANCELLED = "CANCELLED";

    /** Wie tief die Stueckliste beim Anlegen aufgeloest wird. */
    private static final int INITIAL_DEPTH = 1;

    /**
     * Obergrenze beim Neuaufbau der Ebenen.
     *
     * <p>Der tiefste echte Baum in EVE hat vier Ebenen. Acht ist reichlich und
     * schuetzt zugleich davor, dass ein Zyklus in den Stammdaten die Schleife
     * nicht enden laesst.</p>
     */
    private static final int MAX_EXPANSION_DEPTH = 8;

    private final IndustryOrderRepository orderRepo;
    private final IndustryOrderRequirementRepository requirementRepo;
    private final IndustryOrderBaselineRepository baselineRepo;
    private final IndustryOrderJobRepository orderJobRepo;
    private final IndustryJobRepository jobRepo;
    private final IndustryQueryRepository queryRepo;
    private final IndustryPlanningService planning;
    private final MyAssetService assetService;
    private final ProcurementService procurement;
    private final BlueprintCheckService blueprintCheck;
    private final BuildVsBuyService buildVsBuy;

    // ===========================================================
    //  Anlegen
    // ===========================================================

    /**
     * Legt einen Auftrag an und friert Bedarf und Nullmessung ein.
     *
     * @param characterId der angemeldete Charakter; der Auftrag gehoert seinem Konto
     */
    @Transactional
    public IndustryDtos.OrderDetailDto create(Long characterId,
                                              IndustryDtos.CreateOrderRequest request) {
        long productTypeId = require(request.productTypeId(), "Es fehlt das Produkt.");
        long quantity = Math.max(1, request.quantity() == null ? 1 : request.quantity());

        IndustryDtos.PlanPreviewDto preview = planning.preview(
                characterId, productTypeId, quantity, INITIAL_DEPTH, request.buildSystemId());
        if (!preview.summary().blueprintFound()) {
            throw new IllegalArgumentException(
                    "Für dieses Produkt gibt es keine Blaupause - es lässt sich nicht bauen.");
        }

        IndustryOrder order = new IndustryOrder();
        order.setAccountId(assetService.resolveMainId(characterId));
        order.setCreatedByCharacterId(characterId);
        order.setProductTypeId(productTypeId);
        order.setProductName(preview.productName());
        order.setBlueprintTypeId(queryRepo.blueprintFor(productTypeId).blueprintTypeId());
        order.setTargetQuantity(quantity);
        order.setStatus(STATUS_ACTIVE);
        order.setBuildLocationId(request.buildLocationId());
        order.setBuildLocationName(request.buildLocationName());
        order.setBuildSystemId(request.buildSystemId());
        order.setMaterialEfficiency(preview.summary().materialEfficiency());
        order.setTimeEfficiency(preview.summary().timeEfficiency());
        order.setBlueprintOwned(preview.summary().blueprintOwned());
        order.setRunsPerJob(preview.summary().runsPerJob());
        order.setJobCount(preview.summary().jobCount());
        order.setEstimatedSeconds(preview.summary().jobSeconds());
        order.setCreatedAt(Instant.now());
        order.setUpdatedAt(order.getCreatedAt());
        orderRepo.save(order);

        freezeRequirements(order.getId(), preview.requirements());
        captureBaseline(characterId, order, preview.requirements());

        return detail(characterId, order.getId());
    }

    /** Schreibt die gerechnete Bedarfstabelle fest. */
    private void freezeRequirements(Long orderId, List<IndustryDtos.RequirementDto> rows) {
        List<IndustryOrderRequirement> zeilen = new ArrayList<>(rows.size());
        for (IndustryDtos.RequirementDto row : rows) {
            IndustryOrderRequirement r = new IndustryOrderRequirement();
            r.setOrderId(orderId);
            r.setTypeId(row.typeId());
            r.setTypeName(row.typeName());
            r.setQuantityNeeded(row.needed());
            r.setSourceKind(row.sourceKind());
            r.setDecision(row.decision());
            r.setDepth(row.depth());
            r.setParentTypeId(row.parentTypeId());
            r.setUnitPrice(row.unitPrice());
            r.setPriceMissing(row.priceMissing());
            r.setPackagedVolume(row.packagedVolume());
            zeilen.add(r);
        }
        requirementRepo.saveAll(zeilen);
    }

    /**
     * Haelt fest, was jetzt schon da ist.
     *
     * <p>Erfasst wird das Endprodukt <em>und</em> jedes Material. Beim Endprodukt
     * verhindert es einen erfundenen Fortschritt, bei den Materialien verhindert
     * es, dass spaeter zugekauftes Material von schon vorher vorhandenem nicht
     * mehr zu unterscheiden ist.</p>
     */
    private void captureBaseline(Long characterId, IndustryOrder order,
                                 List<IndustryDtos.RequirementDto> rows) {
        Set<Long> typen = new HashSet<>();
        typen.add(order.getProductTypeId());
        rows.forEach(r -> typen.add(r.typeId()));

        // Ausdruecklich OHNE Bausystem, also EVE-weit. Die Nullmessung haelt fest,
        // was beim Anlegen schon da war, und wird spaeter vom jetzigen Bestand
        // abgezogen. Misst man den Startwert ortsbezogen und den Vergleichswert
        // spaeter anders herum - oder umgekehrt -, klemmt die Differenz dauerhaft
        // auf null und der Fortschritt bliebe fuer immer stehen. Beide Seiten
        // muessen dasselbe meinen; hier ist das die Gesamtmenge.
        Map<Long, Holding> bestand = planning.holdingsFor(characterId, typen, null);
        Instant jetzt = Instant.now();

        List<IndustryOrderBaseline> zeilen = new ArrayList<>(typen.size());
        for (Long typeId : typen) {
            IndustryOrderBaseline b = new IndustryOrderBaseline();
            b.setOrderId(order.getId());
            b.setTypeId(typeId);
            Holding h = bestand.get(typeId);
            b.setQuantityAtStart(h == null ? 0L : h.quantity());
            b.setCapturedAt(jetzt);
            zeilen.add(b);
        }
        baselineRepo.saveAll(zeilen);
    }

    // ===========================================================
    //  Lesen
    // ===========================================================

    /** Die Auftraege des Kontos. */
    @Transactional(readOnly = true)
    public List<IndustryDtos.OrderSummaryDto> list(Long characterId) {
        Long accountId = assetService.resolveMainId(characterId);
        return orderRepo.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(o -> summary(characterId, o))
                .toList();
    }

    /** Ein Auftrag mit Bedarfstabelle und Jobs. */
    @Transactional(readOnly = true)
    public IndustryDtos.OrderDetailDto detail(Long characterId, Long orderId) {
        IndustryOrder order = ownedOrder(characterId, orderId);
        List<IndustryOrderRequirement> gespeichert =
                requirementRepo.findByOrderIdOrderByDepthAscQuantityNeededDesc(orderId);

        Set<Long> typen = new HashSet<>();
        gespeichert.forEach(r -> typen.add(r.getTypeId()));
        Map<Long, Holding> bestand =
                planning.holdingsFor(characterId, typen, order.getBuildSystemId());

        List<IndustryDtos.RequirementDto> rows = new ArrayList<>(gespeichert.size());
        for (IndustryOrderRequirement r : gespeichert) {
            Holding h = bestand.get(r.getTypeId());
            long vorhanden = h == null ? 0 : h.quantity();
            rows.add(new IndustryDtos.RequirementDto(
                    r.getTypeId(), r.getTypeName(), r.getQuantityNeeded(), vorhanden,
                    Math.max(0, r.getQuantityNeeded() - vorhanden),
                    r.getSourceKind(),
                    "BUILDABLE".equals(r.getSourceKind()) || "REACTION".equals(r.getSourceKind()),
                    r.getDecision(), r.getDepth(), r.getParentTypeId(),
                    r.getUnitPrice(), Boolean.TRUE.equals(r.getPriceMissing()),
                    r.getPackagedVolume() == null ? 0.0 : r.getPackagedVolume(),
                    h == null ? 0 : h.onCharacters(),
                    h == null ? 0 : h.elsewhere()));
        }

        IndustryDtos.PlanSummaryDto summary = new IndustryDtos.PlanSummaryDto(
                nz(order.getJobCount()), nz(order.getRunsPerJob()),
                nz(order.getJobCount()) * nz(order.getRunsPerJob()),
                nz(order.getEstimatedSeconds()),
                (int) rows.stream().filter(r -> r.depth() == 1).count(),
                0, order.getMaterialEfficiency(), order.getTimeEfficiency(),
                true, order.getBlueprintOwned() != null && order.getBlueprintOwned());

        return new IndustryDtos.OrderDetailDto(
                summary(characterId, order), summary, rows, jobsOf(order));
    }

    /**
     * Die Jobs, die diesem Auftrag zugerechnet sind.
     *
     * <p>Zwei Arten von Jobs, und der Unterschied ist wichtig genug fuer ein
     * eigenes Feld. <b>Gebuchte</b> Jobs stehen in {@code industry_order_jobs}
     * und zaehlen in den Fortschritt. <b>Lose Treffer</b> laufen nur auf einen
     * Typ, den dieser Auftrag ebenfalls braucht - eine Vermutung, keine Buchung.
     * Sie werden nicht gespeichert und beruehren den Fortschrittsbalken nicht.</p>
     *
     * <p>Ohne die losen Treffer koennte an einer Materialzeile ueberhaupt keine
     * Restzeit stehen: gebucht wird nur das Endprodukt, und eine Materialzeile
     * ist definitionsgemaess nicht das Endprodukt. Der Wunsch "man kann sehen
     * wenn eine Sache am produzieren ist" waere damit auf genau eine Zeile
     * beschraenkt.</p>
     */
    private List<IndustryDtos.JobDto> jobsOf(IndustryOrder order) {
        // Die Typen dieses Auftrags samt Namen - daran wird ein Job erkannt.
        Map<Long, String> namen = new HashMap<>();
        requirementRepo.findByOrderIdOrderByDepthAscQuantityNeededDesc(order.getId())
                .forEach(r -> namen.put(r.getTypeId(), r.getTypeName()));
        namen.put(order.getProductTypeId(), order.getProductName());

        List<IndustryDtos.JobDto> rows = new ArrayList<>();
        Set<Long> gebucht = new HashSet<>();
        for (IndustryOrderJob zuordnung : orderJobRepo.findByOrderId(order.getId())) {
            IndustryJob job = jobRepo.findById(zuordnung.getJobId()).orElse(null);
            if (job == null) {
                continue;
            }
            gebucht.add(job.getJobId());
            rows.add(jobDto(job, namen, true));
        }

        for (IndustryJob job : offeneJobsDesKontos(order)) {
            if (gebucht.contains(job.getJobId())
                    || job.getProductTypeId() == null
                    || !namen.containsKey(job.getProductTypeId())) {
                continue;
            }
            rows.add(jobDto(job, namen, false));
        }
        return rows;
    }

    /** Die noch nicht gelieferten Jobs des Kontos. Leer, wenn der Bestand unbekannt ist. */
    private List<IndustryJob> offeneJobsDesKontos(IndustryOrder order) {
        try {
            Set<Long> chars = assetService.ownCharacterIds(order.getAccountId());
            return jobRepo.findByOwnerCharacterIdIn(chars).stream()
                    .filter(j -> !"delivered".equalsIgnoreCase(j.getStatus()))
                    .toList();
        } catch (IllegalStateException e) {
            log.debug("Keine Jobs für Konto {}: {}", order.getAccountId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Ein Job als Zeile der Oberflaeche.
     *
     * <p>Der Name ist der des <em>Job-Produkts</em>, nicht der des Auftrags.
     * Vorher hiess hier jeder Job "Rhea" - wer sehen wollte, was gerade laeuft,
     * bekam bei jedem Eintrag dieselbe Auskunft.</p>
     */
    private IndustryDtos.JobDto jobDto(IndustryJob job, Map<Long, String> namen,
                                       boolean gebucht) {
        String name = namen.get(job.getProductTypeId());
        if (name == null) {
            name = queryRepo.typeName(job.getProductTypeId()).orElse("Unbekannt");
        }
        return new IndustryDtos.JobDto(
                job.getJobId(),
                IndustryActivity.label(job.getActivityIdSde()),
                job.getProductTypeId(), name,
                job.getRuns() == null ? 0 : job.getRuns(),
                job.getStatus(),
                job.getEndDate() == null ? null : job.getEndDate().toString(),
                gebucht);
    }

    private IndustryDtos.OrderSummaryDto summary(Long characterId, IndustryOrder order) {
        return new IndustryDtos.OrderSummaryDto(
                order.getId(), order.getProductTypeId(), order.getProductName(),
                order.getTargetQuantity(), order.getStatus(), order.getBuildLocationName(),
                progress(characterId, order),
                order.getCreatedAt() == null ? null : order.getCreatedAt().toString());
    }

    /**
     * Der Fortschritt eines Auftrags.
     *
     * <p>Die gelieferte Menge kommt aus dem Jobbuch. Die Materialdeckung wird
     * getrennt ausgewiesen und ist ausdruecklich <em>kein</em> Fortschritt: sie
     * sagt, fuer wie viele Endprodukte das vorhandene Material reicht, und darf
     * sinken, wenn jemand Tritanium verkauft.</p>
     *
     * <p>Was im Ofen steht, wird eigens gezaehlt und nicht in den Prozentwert
     * eingerechnet. Ein Job, der laeuft, hat noch nichts hergestellt - ein
     * Fortschritt, der Ofeninhalt mitzaehlt, verspricht zu viel und faellt
     * zurueck, wenn der Job scheitert.</p>
     */
    @Transactional(readOnly = true)
    public IndustryDtos.ProgressDto progress(Long characterId, IndustryOrder order) {
        long geliefert = orderJobRepo.deliveredFor(order.getId());
        long ziel = Math.max(1, nz(order.getTargetQuantity()));
        int prozent = (int) Math.min(100, Math.round(100.0 * geliefert / ziel));

        List<IndustryOrderJob> zugeordnet = orderJobRepo.findByOrderId(order.getId());
        long imOfen = 0;
        int offeneJobs = 0;
        for (IndustryOrderJob zuordnung : zugeordnet) {
            IndustryJob job = jobRepo.findById(zuordnung.getJobId()).orElse(null);
            if (job == null || IndustrySyncService.isDelivered(job)) {
                continue;
            }
            if ("active".equalsIgnoreCase(job.getStatus())
                    || "ready".equalsIgnoreCase(job.getStatus())
                    || "paused".equalsIgnoreCase(job.getStatus())) {
                offeneJobs++;
                imOfen += Math.max(0, job.getRuns());
            }
        }

        return new IndustryDtos.ProgressDto(
                nz(order.getTargetQuantity()), geliefert, imOfen, prozent,
                coveredUnits(characterId, order), offeneJobs);
    }

    /**
     * Fuer wie viele Endprodukte das vorhandene Material reicht.
     *
     * <p>Der Engpass entscheidet: das knappste Material begrenzt alles. Genau
     * diese eine Zahl beantwortet die Frage "kann ich loslegen" besser als jede
     * Materialtabelle.</p>
     *
     * <p>Gezaehlt wird nur, was <em>im Bausystem</em> liegt. "Kann ich loslegen"
     * heisst loslegen und nicht erst durch halb New Eden fliegen; Erz in Delve
     * beantwortet die Frage fuer eine Werft in Branch nicht. Die Zahl darf
     * dadurch sinken - sie ist ausdruecklich eine Bestandsaussage und kein
     * Fortschritt, der Balken selbst kommt aus dem Jobbuch.</p>
     */
    private long coveredUnits(Long characterId, IndustryOrder order) {
        List<IndustryOrderRequirement> bedarf =
                requirementRepo.findByOrderIdAndDepth(order.getId(), 1);
        if (bedarf.isEmpty()) {
            return 0;
        }
        Set<Long> typen = new HashSet<>();
        bedarf.forEach(r -> typen.add(r.getTypeId()));
        Map<Long, Holding> bestand =
                planning.holdingsFor(characterId, typen, order.getBuildSystemId());

        long ziel = Math.max(1, nz(order.getTargetQuantity()));
        long minimum = Long.MAX_VALUE;
        for (IndustryOrderRequirement r : bedarf) {
            if (r.getQuantityNeeded() <= 0) {
                continue;
            }
            Holding h = bestand.get(r.getTypeId());
            long vorhanden = h == null ? 0 : h.quantity();
            // Anteil des Bedarfs, den dieses Material deckt, auf Stueck umgerechnet.
            long deckt = vorhanden * ziel / r.getQuantityNeeded();
            minimum = Math.min(minimum, deckt);
        }
        return minimum == Long.MAX_VALUE ? 0 : Math.min(minimum, ziel);
    }

    /**
     * Die Einkaufsliste zu einem Auftrag.
     *
     * <p>Gerechnet wird auf dem <em>fehlenden</em> Bedarf: was schon im Hangar
     * liegt, muss niemand kaufen. Der Bauort bestimmt Transportmittel und
     * Frachtkosten - ohne gewaehlten Ort wird der teurere Fall angenommen,
     * damit die Summe nicht schmeichelt.</p>
     */
    @Transactional(readOnly = true)
    public IndustryDtos.ProcurementDto procurement(Long characterId, Long orderId) {
        IndustryOrder order = ownedOrder(characterId, orderId);
        IndustryDtos.OrderDetailDto detail = detail(characterId, orderId);

        Double security = null;
        if (order.getBuildSystemId() != null) {
            security = queryRepo.systemInfo(order.getBuildSystemId())
                    .map(IndustryQueryRepository.SystemInfo::security).orElse(null);
        }
        Set<Long> chars = assetService.ownCharacterIds(order.getAccountId());
        return procurement.plan(detail.requirements(), order.getBuildSystemId(),
                security, chars);
    }

    /**
     * Was der Transport zum Bauort je Kubikmeter kostet.
     *
     * <p>Ohne gewaehlten Bauort der Sprungfrachter - derselbe pessimistische
     * Ansatz wie in der Einkaufsliste. Eine zu niedrig angesetzte Fracht liesse
     * sperrige Fertigteile guenstig aussehen, und das ist die Richtung, in der
     * ein Fehler teuer wird.</p>
     */
    private double frachtsatzFuer(IndustryOrder order) {
        Double security = null;
        if (order.getBuildSystemId() != null) {
            security = queryRepo.systemInfo(order.getBuildSystemId())
                    .map(IndustryQueryRepository.SystemInfo::security).orElse(null);
        }
        Integer spruenge = procurement.jumpsFromJita(order.getBuildSystemId());
        return LogisticsMath.transportFor(security, spruenge)
                .perCubicMeter().doubleValue();
    }

    /**
     * Ob die vorhandenen Blaupausen fuer den Auftrag reichen.
     *
     * <p>Geprueft wird das Endprodukt und alles, was auf "Bauen" steht - fuer
     * Gekauftes braucht es keine Blaupause.</p>
     */
    @Transactional(readOnly = true)
    public List<IndustryDtos.BlueprintCheckDto> blueprints(Long characterId, Long orderId) {
        IndustryOrder order = ownedOrder(characterId, orderId);
        IndustryDtos.OrderDetailDto detail = detail(characterId, orderId);

        long laeufe = nz(order.getJobCount()) * nz(order.getRunsPerJob());
        return blueprintCheck.check(characterId, order.getProductTypeId(),
                Math.max(1, laeufe), detail.requirements());
    }

    /**
     * Setzt alle Kaufen/Bauen-Entscheidungen nach einer Voreinstellung.
     *
     * <p>Angewandt wird von oben nach unten und in mehreren Durchlaeufen: eine
     * Entscheidung auf Ebene eins laesst neue Zeilen auf Ebene zwei entstehen,
     * die ihrerseits entschieden werden wollen. Nach jedem Durchlauf wird der
     * Baum neu aufgebaut, bis sich nichts mehr aendert.</p>
     */
    @Transactional
    public IndustryDtos.OrderDetailDto applyStrategy(Long characterId, Long orderId,
                                                     String strategyName) {
        IndustryOrder order = ownedOrder(characterId, orderId);
        BuildStrategy strategie = BuildStrategy.fromName(strategyName);
        order.setStrategy(strategie.name());

        // Der Frachtsatz zum Bauort gehoert in die Entscheidung. Ein fertiges
        // Capital-Bauteil fuellt fuenf Sprungfrachterladungen, seine Zutaten
        // eine - wer nur die Ware vergleicht, uebersieht dabei Hunderte
        // Millionen und stellt auf "Kaufen", was in Wahrheit teurer kommt.
        double frachtsatz = frachtsatzFuer(order);

        for (int runde = 0; runde < MAX_EXPANSION_DEPTH; runde++) {
            List<IndustryOrderRequirement> zeilen =
                    requirementRepo.findByOrderIdOrderByDepthAscQuantityNeededDesc(orderId);

            boolean geaendert = false;
            for (IndustryOrderRequirement zeile : zeilen) {
                String neu = buildVsBuy.shouldBuild(characterId, zeile.getTypeId(),
                        zeile.getQuantityNeeded(), zeile.getSourceKind(), strategie, frachtsatz)
                        ? "BUILD" : "BUY";
                if (!neu.equals(zeile.getDecision())) {
                    zeile.setDecision(neu);
                    geaendert = true;
                }
            }
            if (!geaendert) {
                break;
            }
            requirementRepo.saveAll(zeilen);
            requirementRepo.flush();
            rebuildExpansions(order);
        }

        order.setUpdatedAt(Instant.now());
        orderRepo.save(order);
        return detail(characterId, orderId);
    }

    /**
     * Rechnet einen bestehenden Auftrag von Grund auf neu.
     *
     * <p>Der eingefrorene Bedarf hat einen Preis, und der wurde hier bezahlt:
     * ein Auftrag behaelt seine Zahlen fuer immer. Wird die Blaupause erforscht,
     * aendern sich die Marktpreise oder wird - wie geschehen - ein Rechenfehler
     * behoben, erreicht nichts davon einen einmal angelegten Auftrag. Es fehlte
     * schlicht ein Weg zurueck.</p>
     *
     * <p>Erneuert werden Ebene eins <em>und</em> die Blaupausendaten des
     * Endprodukts; {@link #rebuildExpansions} allein ruehrt die oberste Ebene
     * nie an. Die Kaufen/Bauen-Entscheidungen bleiben erhalten - sie sind die
     * Arbeit des Nutzers, nicht das Ergebnis einer Rechnung.</p>
     *
     * <p>Die Nullmessung bleibt ebenfalls unangetastet. Sie haelt fest, was beim
     * Anlegen schon im Hangar lag; sie jetzt neu zu erfassen wuerde jeden
     * bisherigen Fortschritt auf null zuruecksetzen.</p>
     */
    @Transactional
    public IndustryDtos.OrderDetailDto recalculate(Long characterId, Long orderId) {
        IndustryOrder order = ownedOrder(characterId, orderId);

        Map<Long, String> entscheidungen = new HashMap<>();
        requirementRepo.findByOrderIdOrderByDepthAscQuantityNeededDesc(orderId)
                .forEach(r -> entscheidungen.put(r.getTypeId(), r.getDecision()));

        IndustryDtos.PlanPreviewDto neu = planning.preview(characterId,
                order.getProductTypeId(), order.getTargetQuantity(), INITIAL_DEPTH,
                order.getBuildSystemId());
        if (!neu.summary().blueprintFound()) {
            throw new IllegalArgumentException(
                    "Für dieses Produkt gibt es keine Blaupause mehr - der Auftrag lässt sich "
                    + "nicht neu berechnen.");
        }

        requirementRepo.deleteByOrderId(orderId);
        requirementRepo.flush();
        freezeRequirements(orderId, neu.requirements());
        requirementRepo.flush();

        // Die frisch geschriebene Ebene eins traegt die Voreinstellung "Kaufen".
        // Was der Nutzer selbst gewaehlt hat, wird zurueckgesetzt - sonst kostet
        // ein Neuberechnen die gesamte bisherige Planung.
        List<IndustryOrderRequirement> ebeneEins =
                requirementRepo.findByOrderIdOrderByDepthAscQuantityNeededDesc(orderId);
        for (IndustryOrderRequirement r : ebeneEins) {
            String frueher = entscheidungen.get(r.getTypeId());
            if (frueher != null) {
                r.setDecision(frueher);
            }
        }
        requirementRepo.saveAll(ebeneEins);
        requirementRepo.flush();

        rebuildExpansions(order, entscheidungen);

        order.setMaterialEfficiency(neu.summary().materialEfficiency());
        order.setTimeEfficiency(neu.summary().timeEfficiency());
        order.setBlueprintOwned(neu.summary().blueprintOwned());
        order.setRunsPerJob(neu.summary().runsPerJob());
        order.setJobCount(neu.summary().jobCount());
        order.setEstimatedSeconds(neu.summary().jobSeconds());
        order.setUpdatedAt(Instant.now());
        orderRepo.save(order);

        return detail(characterId, orderId);
    }

    /**
     * Setzt oder aendert das Bausystem eines Auftrags.
     *
     * <p>Beim Anlegen ist der Bauort freiwillig, und ohne eingelesene
     * Corp-Strukturen war er lange gar nicht waehlbar. Ohne ihn muss der
     * Assistent beim Transport den teuersten Fall annehmen und beim Bestand ganz
     * EVE zusammenzaehlen - beides Aussagen, die niemandem nuetzen.</p>
     *
     * <p>Danach wird neu gerechnet, denn am Bausystem haengt nicht nur die
     * Fracht: die Frage, wie viel Material schon <em>da</em> ist, hat ohne einen
     * Ort keine Antwort.</p>
     */
    @Transactional
    public IndustryDtos.OrderDetailDto setBuildLocation(Long characterId, Long orderId,
                                                        IndustryDtos.BuildLocationRequest request) {
        IndustryOrder order = ownedOrder(characterId, orderId);
        order.setBuildSystemId(request == null ? null : request.buildSystemId());
        order.setBuildLocationId(request == null ? null : request.buildLocationId());
        order.setBuildLocationName(request == null ? null : request.buildLocationName());
        order.setUpdatedAt(Instant.now());
        orderRepo.save(order);
        orderRepo.flush();

        return recalculate(characterId, orderId);
    }

    // ===========================================================
    //  Aendern
    // ===========================================================

    /**
     * Stellt eine Kaufen/Bauen-Entscheidung um und loest bei "Bauen" genau eine
     * Ebene tiefer auf.
     *
     * <p>Bewusst nur eine Ebene: der ganze Baum auf einmal waeren bei einem Titan
     * ueber hundert Zeilen, von denen die meisten niemanden interessieren. Tiefe
     * waechst nur dort, wo jemand danach gefragt hat.</p>
     */
    @Transactional
    public IndustryDtos.OrderDetailDto setDecision(Long characterId, Long orderId,
                                                   IndustryDtos.DecisionRequest request) {
        IndustryOrder order = ownedOrder(characterId, orderId);
        long typeId = require(request.typeId(), "Es fehlt das Material.");
        String entscheidung = "BUILD".equalsIgnoreCase(request.decision()) ? "BUILD" : "BUY";

        IndustryOrderRequirement zeile = requirementRepo.findByOrderIdAndTypeId(orderId, typeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Dieses Material gehört nicht zu dem Auftrag."));

        if ("BUILD".equals(entscheidung)
                && !("BUILDABLE".equals(zeile.getSourceKind())
                     || "REACTION".equals(zeile.getSourceKind()))) {
            // PI-Gueter und Mineralien lassen sich per Industriejob nicht herstellen.
            throw new IllegalArgumentException(
                    zeile.getTypeName() + " lässt sich nicht per Industriejob herstellen.");
        }

        zeile.setDecision(entscheidung);
        requirementRepo.save(zeile);

        rebuildExpansions(order);

        order.setUpdatedAt(Instant.now());
        orderRepo.save(order);

        return detail(characterId, orderId);
    }

    /**
     * Baut alle tieferen Ebenen neu auf, ausgehend von den aktuellen
     * Bauen-Entscheidungen.
     *
     * <p>Bewusst vollstaendig neu statt schrittweise ergaenzt. Der Grund ist ein
     * Fehler, den das schrittweise Vorgehen hatte: beim Zurueckstellen auf
     * "Kaufen" blieben die einmal aufgeklappten Zeilen stehen, der Nutzer wurde
     * die Ebene also nie wieder los. Dasselbe gilt fuer die Mengen - ein
     * Material, das aus zwei gebauten Zweigen stammt, wurde beim zweiten Zweig
     * uebersprungen statt aufaddiert.</p>
     *
     * <p>Neu aufbauen loest beides auf einen Schlag und ist bei den hier
     * auftretenden Groessen - ein Titan hat vier Ebenen - guenstig genug.</p>
     */
    private void rebuildExpansions(IndustryOrder order) {
        rebuildExpansions(order, Map.of());
    }

    /**
     * Wie {@link #rebuildExpansions(IndustryOrder)}, nimmt aber Entscheidungen
     * entgegen, die in der Tabelle nicht mehr stehen.
     *
     * @param gemerkt Entscheidungen aus einem Stand, der bereits geloescht wurde;
     *                noch vorhandene Zeilen haben Vorrang
     */
    private void rebuildExpansions(IndustryOrder order, Map<Long, String> gemerkt) {
        List<IndustryOrderRequirement> alle =
                requirementRepo.findByOrderIdOrderByDepthAscQuantityNeededDesc(order.getId());

        // Die Entscheidungen der tieferen Zeilen merken: wer eine Ebene weiter
        // unten "Bauen" gewaehlt hat, soll das nicht dadurch verlieren, dass
        // weiter oben etwas umgestellt wird.
        Map<Long, String> frueher = new HashMap<>(gemerkt);
        List<IndustryOrderRequirement> ebeneEins = new ArrayList<>();
        List<IndustryOrderRequirement> tiefer = new ArrayList<>();
        for (IndustryOrderRequirement r : alle) {
            frueher.put(r.getTypeId(), r.getDecision());
            if (r.getDepth() == 1) {
                ebeneEins.add(r);
            } else {
                tiefer.add(r);
            }
        }
        requirementRepo.deleteAll(tiefer);
        requirementRepo.flush();

        // Breitensuche ueber die gebauten Knoten. Die Tiefe ist durch die
        // Stammdaten begrenzt; der Zaehler schuetzt zusaetzlich davor, dass ein
        // Zyklus in fremden Daten die Schleife nicht enden laesst.
        Map<Long, IndustryOrderRequirement> nachTyp = new LinkedHashMap<>();
        ebeneEins.forEach(r -> nachTyp.put(r.getTypeId(), r));

        List<IndustryOrderRequirement> welle = new ArrayList<>(ebeneEins);
        for (int ebene = 1; ebene <= MAX_EXPANSION_DEPTH && !welle.isEmpty(); ebene++) {
            List<IndustryOrderRequirement> naechste = new ArrayList<>();
            for (IndustryOrderRequirement eltern : welle) {
                if (!"BUILD".equals(eltern.getDecision())) {
                    continue;
                }
                // Die Materialeffizienz der Blaupause *dieses Bauteils* - nicht die
                // des Endprodukts. Ohne sie steht in der Tabelle die Menge fuer
                // ME 0, und das verzerrt die Frage "kaufen oder bauen" spuerbar:
                // bei einem Capital Core Temperature Regulator entscheidet genau
                // dieser Unterschied darueber, ob Eigenbau acht Millionen kostet
                // oder zehn spart.
                var bpInfo = queryRepo.blueprintFor(eltern.getTypeId());
                var ctx = bpInfo == null
                        ? null
                        : planning.contextFor(order.getCreatedByCharacterId(), bpInfo);
                long laeufe = bpInfo == null
                        ? eltern.getQuantityNeeded()
                        : IndustryMath.runsForQuantity(
                                eltern.getQuantityNeeded(), bpInfo.unitsPerRun());

                for (var kind : queryRepo.billOfMaterials(eltern.getTypeId(), 1)) {
                    // Die Menge JE LAUF, wie sie in den Stammdaten steht - nicht
                    // die je Stueck. Eine Reaktion liefert 10.000 Stueck aus 100
                    // Einheiten Material; je Stueck sind das 0,01, und wer das
                    // aufrundet, rechnet mit 1 statt 100.
                    long menge = ctx == null
                            ? (long) Math.ceil(kind.quantityPerUnit() * eltern.getQuantityNeeded())
                            : IndustryMath.materialForJob(laeufe, kind.quantityPerRun(), ctx);

                    IndustryOrderRequirement vorhanden = nachTyp.get(kind.typeId());
                    if (vorhanden != null) {
                        // Aus einem zweiten Zweig - die Mengen addieren sich.
                        vorhanden.setQuantityNeeded(vorhanden.getQuantityNeeded() + menge);
                        continue;
                    }

                    IndustryOrderRequirement r = new IndustryOrderRequirement();
                    r.setOrderId(order.getId());
                    r.setTypeId(kind.typeId());
                    r.setTypeName(kind.typeName());
                    r.setQuantityNeeded(menge);
                    r.setSourceKind(kind.sourceKind());
                    r.setDecision(frueher.getOrDefault(kind.typeId(), "BUY"));
                    r.setDepth(eltern.getDepth() + 1);
                    r.setParentTypeId(eltern.getTypeId());
                    nachTyp.put(kind.typeId(), r);
                    naechste.add(r);
                }
            }
            welle = naechste;
        }

        fillVolumesAndPrices(nachTyp.values());
        requirementRepo.saveAll(nachTyp.values());
    }

    /**
     * Traegt Volumen und Preis nach.
     *
     * <p>Anlass ist ein Fehler, der unsichtbar blieb: die beim Aufklappen neu
     * entstandenen Zeilen hatten kein Volumen. Sie zaehlten damit mit null
     * Kubikmetern in die Frachtrechnung - bei einem Titan mit fast achttausend
     * Einheiten Reinforced Carbon Fiber fehlte so ein erheblicher Teil der
     * Transportkosten, ohne dass die Summe unvollstaendig aussah.</p>
     */
    private void fillVolumesAndPrices(Collection<IndustryOrderRequirement> zeilen) {
        if (zeilen.isEmpty()) {
            return;
        }
        Set<Long> typen = new HashSet<>();
        zeilen.forEach(r -> typen.add(r.getTypeId()));

        Map<Long, Double> volumen = queryRepo.packagedVolumes(typen);
        for (IndustryOrderRequirement r : zeilen) {
            r.setPackagedVolume(volumen.getOrDefault(r.getTypeId(), 0.0));
            Double preis = queryRepo.jitaSell(r.getTypeId());
            r.setUnitPrice(preis);
            r.setPriceMissing(preis == null);
        }
    }

    /** Bricht einen Auftrag ab. Die Zeilen bleiben stehen, damit man nachsehen kann. */
    @Transactional
    public void cancel(Long characterId, Long orderId) {
        IndustryOrder order = ownedOrder(characterId, orderId);
        order.setStatus(STATUS_CANCELLED);
        order.setUpdatedAt(Instant.now());
        orderRepo.save(order);
    }

    /** Loescht einen Auftrag samt allem, was daran haengt. */
    @Transactional
    public void delete(Long characterId, Long orderId) {
        IndustryOrder order = ownedOrder(characterId, orderId);
        requirementRepo.deleteByOrderId(order.getId());
        baselineRepo.deleteByOrderId(order.getId());
        orderJobRepo.deleteByOrderId(order.getId());
        orderRepo.delete(order);
    }

    // ===========================================================
    //  Hilfen
    // ===========================================================

    /**
     * Holt einen Auftrag und stellt dabei sicher, dass er dem Konto gehoert.
     *
     * <p>Die Pruefung sitzt hier und nicht im Controller: so kann kein Endpunkt
     * sie vergessen.</p>
     */
    private IndustryOrder ownedOrder(Long characterId, Long orderId) {
        Long accountId = assetService.resolveMainId(characterId);
        return orderRepo.findByIdAndAccountId(orderId, accountId)
                .orElseThrow(() -> new IllegalArgumentException("Auftrag nicht gefunden."));
    }

    private static long require(Long value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
