package com.eve.own.auth.backend.domain.fleet.service;

import com.eve.own.auth.backend.common.EveImageUrls;
import com.eve.own.auth.backend.domain.fleet.dto.ReadinessDtos;
import com.eve.own.auth.backend.domain.fleet.repository.ReadinessQueryRepository;
import jakarta.persistence.Tuple;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zerlegt einen EFT-Textblock in Huelle und Module und reichert ihn aus der SDE an.
 *
 * <p>Die Slot-Zuordnung kommt nicht aus der Reihenfolge der Leerzeilen-Bloecke,
 * sondern aus {@code dgmTypeEffects}. Das ist wichtig, weil Pyfa, der
 * Ingame-Export und aus Foren kopierte Fits die Bloecke unterschiedlich anordnen
 * und Leerzeilen beim Kopieren regelmaessig verloren gehen - eine positionsbasierte
 * Zuordnung waere dann stillschweigend falsch.</p>
 */
@Slf4j
@Service
public class EftParserService {

    /** Kopfzeile eines Fits. Bewusst nur am <em>ersten</em> Komma getrennt: Fit-Namen duerfen Kommata enthalten. */
    private static final Pattern HEADER = Pattern.compile("^\\[\\s*([^,\\]]+?)\\s*,\\s*(.+?)\\s*]$");

    /** Mengenangabe am Zeilenende, z.B. "Hobgoblin II x5" oder "Nanite Repair Paste x50". */
    private static final Pattern QUANTITY_SUFFIX = Pattern.compile("\\s+[xX](\\d+)\\s*$");

    /** Platzhalter fuer leere Slots, die Pyfa optional mit ausgibt. */
    private static final Pattern EMPTY_SLOT = Pattern.compile("^\\[\\s*empty .*]$", Pattern.CASE_INSENSITIVE);

    private static final long CATEGORY_DRONE = 18L;
    private static final long CATEGORY_SHIP = 6L;

    /** Slot-Effekte aus der SDE, in der Reihenfolge, in der sie im Fitting-Fenster stehen. */
    private static final long EFFECT_HIGH = 12L;
    private static final long EFFECT_MID = 13L;
    private static final long EFFECT_LOW = 11L;
    private static final long EFFECT_RIG = 2663L;
    private static final long EFFECT_SUBSYSTEM = 3772L;

    private final ReadinessQueryRepository queryRepo;

    public EftParserService(ReadinessQueryRepository queryRepo) {
        this.queryRepo = queryRepo;
    }

    // ==================================================================
    // Oeffentliche API
    // ==================================================================

    /** Rohergebnis des reinen Textparsings, noch ohne SDE-Bezug. */
    public record RawLine(String name, String chargeName, int quantity) {}

    public record RawFit(String shipName, String fitName, List<RawLine> lines) {}

    /**
     * Parst den Text und loest alle Namen gegen die SDE auf.
     *
     * @throws IllegalArgumentException wenn Kopfzeile oder Schiffstyp unbrauchbar sind
     */
    /**
     * Eigene Transaktion, weil der Aufrufer die Ausnahme absichtlich faengt.
     *
     * <p>Der Vorsatz lautet: ein unlesbares Fitting darf ein Schiff nicht aus
     * der Doktrin verschwinden lassen. Ohne {@code REQUIRES_NEW} kehrte er sich
     * ins Gegenteil - die geworfene {@code IllegalArgumentException} markierte
     * die Transaktion des Readiness-Boards als rollback-only, das Board wurde
     * vollstaendig aufgebaut, und erst der Commit scheiterte. <b>Ein einziger</b>
     * Doktrin-Eintrag mit unbrauchbarem Text liess damit das gesamte Board und
     * jede Selbstauskunft mit 500 antworten, waehrend die Warnung im Log so
     * aussah, als betreffe sie nur dieses eine Fitting.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ReadinessDtos.ParsedFitDto parseAndResolve(String eftString) {
        RawFit raw = parseText(eftString);

        // Schiff und Module in einem Rutsch aufloesen - ein Query statt N.
        Set<String> lookups = new LinkedHashSet<>();
        lookups.add(raw.shipName().toLowerCase(Locale.ROOT));
        for (RawLine line : raw.lines()) {
            lookups.add(line.name().toLowerCase(Locale.ROOT));
            if (line.chargeName() != null) {
                lookups.add(line.chargeName().toLowerCase(Locale.ROOT));
            }
        }

        Map<String, SdeType> resolved = new HashMap<>();
        for (Tuple t : queryRepo.resolveTypesByName(new ArrayList<>(lookups))) {
            resolved.put(str(t, "lookup"), new SdeType(
                    lng(t, "typeId"), str(t, "typeName"),
                    lng(t, "categoryId"), str(t, "groupName"),
                    lngOrNull(t, "slotEffectId")));
        }

        SdeType ship = resolved.get(raw.shipName().toLowerCase(Locale.ROOT));
        if (ship == null) {
            throw new IllegalArgumentException("Unbekannter Schiffstyp: \"" + raw.shipName() + "\"");
        }
        if (ship.categoryId() != null && ship.categoryId() != CATEGORY_SHIP) {
            throw new IllegalArgumentException("\"" + ship.typeName() + "\" ist kein Schiff.");
        }

        // Gleiche Module zusammenfassen, damit fuenf Launcher nicht fuenf Zeilen erzeugen.
        Map<SlotGroup, Map<String, ModuleAcc>> bySlot = new EnumMap<>(SlotGroup.class);
        List<String> unresolved = new ArrayList<>();

        for (RawLine line : raw.lines()) {
            SdeType module = resolved.get(line.name().toLowerCase(Locale.ROOT));
            if (module == null) {
                if (!unresolved.contains(line.name())) unresolved.add(line.name());
                continue;
            }

            SdeType charge = line.chargeName() == null
                    ? null
                    : resolved.get(line.chargeName().toLowerCase(Locale.ROOT));
            if (line.chargeName() != null && charge == null && !unresolved.contains(line.chargeName())) {
                unresolved.add(line.chargeName());
            }

            SlotGroup slot = slotOf(module);
            String key = module.typeId() + "|" + (charge != null ? charge.typeId() : "-");

            bySlot.computeIfAbsent(slot, k -> new LinkedHashMap<>())
                    .computeIfAbsent(key, k -> new ModuleAcc(module, charge))
                    .quantity += line.quantity();
        }

        List<ReadinessDtos.FitSlotGroupDto> groups = new ArrayList<>();
        int moduleCount = 0;
        for (SlotGroup slot : SlotGroup.values()) {
            Map<String, ModuleAcc> modules = bySlot.get(slot);
            if (modules == null || modules.isEmpty()) continue;

            List<ReadinessDtos.FitModuleDto> dtos = modules.values().stream()
                    .map(m -> new ReadinessDtos.FitModuleDto(
                            m.type.typeId(), m.type.typeName(), icon(m.type.typeId()), m.quantity,
                            m.charge != null ? m.charge.typeName() : null,
                            m.charge != null ? m.charge.typeId() : null))
                    .toList();

            int count = dtos.stream().mapToInt(ReadinessDtos.FitModuleDto::quantity).sum();
            moduleCount += count;
            groups.add(new ReadinessDtos.FitSlotGroupDto(slot.label, slot.icon, count, dtos));
        }

        return new ReadinessDtos.ParsedFitDto(
                ship.typeId(), ship.typeName(), raw.fitName(),
                icon(ship.typeId()), render(ship.typeId()),
                moduleCount, groups, unresolved);
    }

    /**
     * Reines Textparsing ohne Datenbankzugriff.
     *
     * <p>Leerzeilen und Kommentare werden ignoriert - die Blockstruktur des EFT-Formats
     * wird bewusst <em>nicht</em> ausgewertet, siehe Klassenkommentar.</p>
     */
    public RawFit parseText(String eftString) {
        if (eftString == null || eftString.isBlank()) {
            throw new IllegalArgumentException("Kein EFT-Text übergeben.");
        }

        String[] rawLines = eftString.replace("\r\n", "\n").replace('\r', '\n').split("\n");

        String shipName = null;
        String fitName = null;
        List<RawLine> lines = new ArrayList<>();

        for (String rawLine : rawLines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;

            if (shipName == null) {
                Matcher header = HEADER.matcher(line);
                if (!header.matches()) {
                    throw new IllegalArgumentException(
                            "Ungültiges EFT-Format: die erste Zeile muss [Schiffstyp, Fitting-Name] lauten.");
                }
                shipName = header.group(1).trim();
                fitName = header.group(2).trim();
                continue;
            }

            if (EMPTY_SLOT.matcher(line).matches()) continue;

            int quantity = 1;
            Matcher qty = QUANTITY_SUFFIX.matcher(line);
            if (qty.find()) {
                try {
                    quantity = Math.max(1, Integer.parseInt(qty.group(1)));
                } catch (NumberFormatException ignored) {
                    // Unlesbare Menge: als einzelnes Item werten statt die Zeile zu verlieren.
                }
                line = line.substring(0, qty.start()).trim();
            }

            // Modul und geladene Munition sind durch das erste Komma getrennt.
            String name = line;
            String charge = null;
            int comma = line.indexOf(',');
            if (comma > 0) {
                name = line.substring(0, comma).trim();
                String tail = line.substring(comma + 1).trim();
                if (!tail.isEmpty()) charge = tail;
            }

            if (!name.isEmpty()) {
                lines.add(new RawLine(name, charge, quantity));
            }
        }

        if (shipName == null) {
            throw new IllegalArgumentException(
                    "Ungültiges EFT-Format: die erste Zeile muss [Schiffstyp, Fitting-Name] lauten.");
        }
        return new RawFit(shipName, fitName, lines);
    }

    // ==================================================================
    // Interna
    // ==================================================================

    /** Slot-Gruppen in der Reihenfolge, in der sie im Frontend erscheinen sollen. */
    private enum SlotGroup {
        HIGH("High Slots", "fa-solid fa-bolt"),
        MID("Mid Slots", "fa-solid fa-shield-halved"),
        LOW("Low Slots", "fa-solid fa-gear"),
        RIG("Rigs", "fa-solid fa-screwdriver-wrench"),
        SUBSYSTEM("Subsystems", "fa-solid fa-microchip"),
        DRONE("Drohnen", "fa-solid fa-robot"),
        CARGO("Cargo / Ladung", "fa-solid fa-box");

        final String label;
        final String icon;

        SlotGroup(String label, String icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    private SlotGroup slotOf(SdeType type) {
        Long effect = type.slotEffectId();
        if (effect != null) {
            if (effect == EFFECT_HIGH) return SlotGroup.HIGH;
            if (effect == EFFECT_MID) return SlotGroup.MID;
            if (effect == EFFECT_LOW) return SlotGroup.LOW;
            if (effect == EFFECT_RIG) return SlotGroup.RIG;
            if (effect == EFFECT_SUBSYSTEM) return SlotGroup.SUBSYSTEM;
        }
        if (type.categoryId() != null && type.categoryId() == CATEGORY_DRONE) return SlotGroup.DRONE;
        return SlotGroup.CARGO;
    }

    private record SdeType(Long typeId, String typeName, Long categoryId, String groupName, Long slotEffectId) {}

    private static final class ModuleAcc {
        final SdeType type;
        final SdeType charge;
        int quantity;

        ModuleAcc(SdeType type, SdeType charge) {
            this.type = type;
            this.charge = charge;
        }
    }

    static String icon(Long typeId) {
        return EveImageUrls.typeIcon(typeId);
    }

    static String render(Long typeId) {
        return EveImageUrls.typeRender(typeId);
    }

    private static Long lng(Tuple t, String alias) {
        Object v = t.get(alias);
        return v == null ? null : ((Number) v).longValue();
    }

    private static Long lngOrNull(Tuple t, String alias) {
        try {
            return lng(t, alias);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String str(Tuple t, String alias) {
        Object v = t.get(alias);
        return v == null ? null : String.valueOf(v);
    }
}
