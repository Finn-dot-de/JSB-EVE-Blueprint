package com.eve.own.auth.backend.domain.industry.service;

import com.eve.own.auth.backend.domain.industry.dto.IndustryDtos;
import com.eve.own.auth.backend.domain.industry.entity.IndustryStructure;
import com.eve.own.auth.backend.domain.assets.service.MyAssetService;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository.SystemInfo;
import com.eve.own.auth.backend.domain.industry.repository.IndustryStructureRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Findet Bauorte und sagt, was sich dort anfangen laesst.
 *
 * <p>Die Empfehlungen kommen aus den Stammdaten und nicht aus einer gepflegten
 * Liste: der Strukturtyp steht in {@code invTypes}, seine Gruppe in
 * {@code invGroups}. Eine Tatara ist dort eine <em>Refinery</em>, eine Raitaru
 * ein <em>Engineering Complex</em> - daraus laesst sich ableiten, wofuer sie
 * taugt, ohne dass jemand eine Tabelle pflegen muss.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndustryStructureService {

    /** Gruppen aus dem SDE, die eine Fertigungsstruktur kennzeichnen. */
    private static final String GROUP_ENGINEERING = "Engineering Complex";

    /** Gruppen, die eine Aufbereitungs- und Reaktionsstruktur kennzeichnen. */
    private static final String GROUP_REFINERY = "Refinery";

    private final IndustryStructureRepository structureRepo;
    private final IndustryQueryRepository queryRepo;
    private final MyAssetService assetService;

    /**
     * Sucht Bauorte nach Name oder System.
     *
     * <p>Sortiert nach Brauchbarkeit: erst was der Corporation gehoert und wo die
     * Dienste bekannt sind, dann alles Uebrige. Wer einen Bauort sucht, will
     * zuerst die sehen, bei denen er sicher andocken kann.</p>
     */
    @Transactional(readOnly = true)
    public List<IndustryDtos.LocationDto> search(Long characterId, String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        int schranke = Math.clamp(limit, 1, 50);

        List<IndustryStructure> treffer = structureRepo.search(needle, Limit.of(schranke));
        List<IndustryDtos.LocationDto> rows = new ArrayList<>(schranke);
        Set<Long> gesehen = new HashSet<>();
        for (IndustryStructure s : treffer) {
            rows.add(toDto(s));
            gesehen.add(s.getStructureId());
        }

        // Und dann die Sonnensysteme selbst. Ohne sie waere die Suche auf die
        // Strukturtabelle angewiesen, und die ist leer, solange niemand mit
        // Direktorenrechten die Corp-Strukturen eingelesen hat. Ein Auftrag ohne
        // Bausystem kann aber weder Transport noch vorhandenes Material richtig
        // rechnen - dann lieber das System von Hand waehlbar machen.
        Map<Long, Integer> eigeneSysteme = assetSystems(characterId);
        for (SystemInfo sys : queryRepo.searchSystems(needle, schranke)) {
            if (!gesehen.add(sys.id())) {
                continue;
            }
            rows.add(toDto(sys, eigeneSysteme.get(sys.id())));
        }
        return rows;
    }

    /** In welchen Systemen der Anfragende Material liegen hat. Leer, wenn unbekannt. */
    private Map<Long, Integer> assetSystems(Long characterId) {
        if (characterId == null) {
            return Map.of();
        }
        try {
            Long mainId = assetService.resolveMainId(characterId);
            return queryRepo.assetSystemsOf(assetService.ownCharacterIds(mainId));
        } catch (IllegalStateException e) {
            log.debug("Keine Standorte für Charakter {}: {}", characterId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Ein Sonnensystem als waehlbarer Bauort.
     *
     * <p>Von einem System allein laesst sich nicht sagen, ob dort gefertigt
     * werden kann - das haengt an einer Struktur, die wir nicht kennen. Also wird
     * es auch nicht behauptet: {@code servicesKnown} bleibt falsch, und alle drei
     * Dienste stehen auf {@code false}. Geraten wird hier nicht.</p>
     *
     * @param eigeneTypen wie viele verschiedene Materialtypen dort schon liegen,
     *                    {@code null} wenn keine
     */
    private IndustryDtos.LocationDto toDto(SystemInfo sys, Integer eigeneTypen) {
        List<String> hinweise = new ArrayList<>(2);
        if (eigeneTypen != null && eigeneTypen > 0) {
            hinweise.add("Hier liegen bereits " + eigeneTypen
                    + (eigeneTypen == 1 ? " Materialtyp" : " Materialtypen") + " von dir.");
        }
        hinweise.add("Sonnensystem, keine bestimmte Struktur - "
                + "Boni einer Struktur gehen damit nicht in die Rechnung ein.");

        return new IndustryDtos.LocationDto(
                sys.id(), sys.name(), sys.name(), sys.id(),
                sys.security(), sys.region(), "SYSTEM",
                false, false, false, false,
                hinweise);
    }

    /** Wandelt eine gespeicherte Struktur in die Form der Oberflaeche. */
    private IndustryDtos.LocationDto toDto(IndustryStructure s) {
        boolean bekannt = Boolean.TRUE.equals(s.getServicesKnown());
        return new IndustryDtos.LocationDto(
                s.getStructureId(), s.getName(), s.getSystemName(), s.getSolarSystemId(),
                s.getSecurityStatus(), s.getTypeName(), s.getSource(),
                bekannt,
                bekannt && Boolean.TRUE.equals(s.getManufacturingOnline()),
                bekannt && Boolean.TRUE.equals(s.getReprocessingOnline()),
                bekannt && Boolean.TRUE.equals(s.getReactionsOnline()),
                hints(s));
    }

    /**
     * Was sich an diesem Ort anfangen laesst.
     *
     * <p>Genau der Punkt aus dem Wunsch: "Empfehlungen raushauen, wenn man eine
     * Tatara im System hat". Die Hinweise sind bewusst knapp und nur dort, wo sie
     * etwas beitragen - eine Liste, die an jedem Ort dasselbe sagt, liest bald
     * niemand mehr.</p>
     */
    private List<String> hints(IndustryStructure s) {
        List<String> hinweise = new ArrayList<>(3);
        String typ = s.getTypeName() == null ? "" : s.getTypeName();

        switch (typ) {
            case "Tatara" -> {
                hinweise.add("Reaktionen laufen hier mit dem stärksten Zeitbonus aller Refineries.");
                hinweise.add("Wiederaufbereitung mit Rig deutlich über NPC-Niveau - "
                        + "Erz lohnt sich hier zu verarbeiten statt zu verkaufen.");
                hinweise.add("Fertigung geht hier NICHT - dafür braucht es einen "
                        + "Engineering Complex.");
            }
            case "Athanor" -> {
                hinweise.add("Wiederaufbereitung und Reaktionen möglich, aber ohne "
                        + "die Boni einer Tatara.");
                hinweise.add("Fertigung geht hier nicht.");
            }
            case "Raitaru" -> hinweise.add("Fertigung mit ein Prozent Materialbonus "
                    + "und fünfzehn Prozent Zeitbonus.");
            case "Azbel" -> hinweise.add("Fertigung mit stärkerem Bonus als eine Raitaru, "
                    + "auch für große Rümpfe.");
            case "Sotiyo" -> hinweise.add("Der stärkste Fertigungsbonus - "
                    + "und der einzige Ort für Supercarrier und Titanen.");
            default -> {
                if (!Boolean.TRUE.equals(s.getServicesKnown())) {
                    hinweise.add("Dienste unbekannt - ESI verrät sie für fremde "
                            + "Strukturen nicht.");
                }
            }
        }
        return hinweise;
    }

    /**
     * Schreibt die Strukturen der eigenen Corporation fort.
     *
     * <p>Die Dienste kommen als Namensliste ("manufacturing", "reprocessing",
     * "reactions") und werden hier auf drei Flaggen abgebildet. Nur bei diesen
     * Strukturen sind sie ueberhaupt bekannt.</p>
     */
    @Transactional
    public void upsertCorpStructure(Long structureId, Long typeId, Long systemId,
                                    Long corporationId, List<String> onlineServices,
                                    Instant fuelExpires) {
        IndustryStructure s = structureRepo.findById(structureId)
                .orElseGet(IndustryStructure::new);
        s.setStructureId(structureId);
        s.setTypeId(typeId);
        s.setSolarSystemId(systemId);
        s.setOwnerCorporationId(corporationId);
        s.setSource("CORP");
        s.setFuelExpires(fuelExpires);
        s.setUpdatedAt(Instant.now());

        // Typ- und Systemname aus den Stammdaten - ESI liefert beides nicht mit.
        queryRepo.typeName(typeId).ifPresent(s::setTypeName);
        queryRepo.systemInfo(systemId).ifPresent(info -> {
            s.setSystemName(info.name());
            s.setSecurityStatus(info.security());
        });

        s.setServicesKnown(true);
        List<String> dienste = onlineServices == null ? List.of() : onlineServices;
        s.setManufacturingOnline(containsService(dienste, "manufacturing"));
        s.setReprocessingOnline(containsService(dienste, "reprocessing"));
        s.setReactionsOnline(containsService(dienste, "reaction"));

        structureRepo.save(s);
    }

    /**
     * Ob ein Dienst laeuft.
     *
     * <p>Auf Teilzeichenketten geprueft, weil CCP die Reaktionsdienste nach Art
     * benennt - "Composite Reactions", "Hybrid Reactions", "Biochemical
     * Reactions". Ein Vergleich auf Gleichheit fände keinen davon.</p>
     */
    private static boolean containsService(List<String> services, String needle) {
        return services.stream()
                .filter(s -> s != null)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .anyMatch(s -> s.contains(needle));
    }

    /** Ob an einem Ort ueberhaupt gefertigt werden kann, soweit bekannt. */
    public static boolean canManufacture(IndustryStructure s) {
        if (!Boolean.TRUE.equals(s.getServicesKnown())) {
            return false;
        }
        return Boolean.TRUE.equals(s.getManufacturingOnline());
    }

    /** Die Gruppe im SDE, an der eine Fertigungsstruktur erkennbar ist. */
    public static String manufacturingGroup() {
        return GROUP_ENGINEERING;
    }

    /** Die Gruppe im SDE, an der eine Aufbereitungsstruktur erkennbar ist. */
    public static String refineryGroup() {
        return GROUP_REFINERY;
    }
}
