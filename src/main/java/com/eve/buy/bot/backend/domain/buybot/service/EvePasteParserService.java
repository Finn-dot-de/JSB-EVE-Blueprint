package com.eve.buy.bot.backend.domain.buybot.service;

import com.eve.buy.bot.backend.domain.buybot.dto.ParsedItemDto;
import com.eve.buy.bot.backend.domain.buybot.dto.TypeDetailsProjection;
import com.eve.buy.bot.backend.domain.eve.repository.InvTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest die aus EVE kopierte Item-Liste ein.
 *
 * <p>Der Client liefert je nach Fenster unterschiedliche Formate: mit Tabulatoren, mit
 * Leerzeichen ausgerichtet, Menge vor oder hinter dem Namen, "x" als Multiplikator. Alle
 * Varianten landen hier in derselben Struktur, Doppelungen werden zusammengezaehlt.
 */
@Service
@RequiredArgsConstructor
public class EvePasteParserService {

    private final InvTypeRepository invTypeRepository;

    // Erkennt Zahlen mit optionalen Tausendertrennern (z.B. 28.000 oder 5,400 oder 1000)
    private static final String NUM_SRC = "(?:\\d{1,3}(?:[.,]\\d{3})+|\\d+)";
    private static final Pattern QTY_PATTERN = Pattern.compile("^" + NUM_SRC + "$");
    private static final Pattern LEAD_PATTERN = Pattern.compile("^(" + NUM_SRC + ")\\s+(.+)$");
    private static final Pattern TRAIL_PATTERN = Pattern.compile("^(.+?)\\s+(" + NUM_SRC + ")$");
    // Fitting-/Multibuy-Formate: "Antimatter Charge M x1000" bzw. "1000x Tritanium"
    private static final Pattern X_TRAIL_PATTERN = Pattern.compile("^(.+?)\\s*[xX]\\s*(" + NUM_SRC + ")$");
    private static final Pattern X_LEAD_PATTERN = Pattern.compile("^(" + NUM_SRC + ")\\s*[xX]\\s+(.+)$");

    // Kopf- und Summenzeilen, die beim Kopieren mitkommen und keine Items sind
    private static final Pattern NOISE_PATTERN = Pattern.compile(
            "^(?:total|gesamt|summe|item|items|name|anzahl|quantity|menge|volumen|volume|preis|price|value|wert)\\b.*",
            Pattern.CASE_INSENSITIVE);

    /**
     * Zerlegt die eingefuegte Liste und gleicht sie mit der Statikdatenbank ab.
     *
     * <p>Nicht erkannte Namen werden nicht verworfen, sondern als ungeloest
     * zurueckgegeben - der Nutzer soll sehen, welche Zeile nicht gepasst hat.
     *
     * @param rawInput der eingefuegte Text
     * @return die erkannten Positionen mit zusammengezaehlten Mengen
     */
    public List<ParsedItemDto> parseAndResolveInput(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return new ArrayList<>();
        }

        Map<String, ParsedItemDto> aggregatedItems = new HashMap<>();
        String[] lines = rawInput.split("\\r?\\n");

        for (String line : lines) {
            String trimmedLine = normalizeSpaces(line);
            if (trimmedLine.isEmpty()) continue;
            if (!trimmedLine.contains("\t") && NOISE_PATTERN.matcher(trimmedLine).matches()) continue;

            String name = null;
            long qty = 1;

            if (trimmedLine.contains("\t")) {
                // Tab-getrennte EVE-Kopie (Inventar/Vertrag)
                String[] cols = trimmedLine.split("\t");
                name = cleanItemName(cols[0]);

                // Wir suchen die Spalte mit der Menge
                for (int i = 1; i < cols.length; i++) {
                    String c = cols[i].trim();
                    if (c.isEmpty() || c.toLowerCase().endsWith("m3") || c.toLowerCase().endsWith("isk")) {
                        continue;
                    }
                    if (QTY_PATTERN.matcher(c).matches()) {
                        qty = parseQuantity(c);
                        break;
                    }
                }
            } else {
                // Mit Leerzeichen ausgerichtete Liste oder Freitext
                String[] cols = trimmedLine.split("\\s{2,}");
                if (cols.length >= 2) {
                    int nameCol = 0;
                    // Manche Fenster stellen die Menge voran: "1000    Tritanium"
                    if (QTY_PATTERN.matcher(cols[0].trim()).matches()) {
                        qty = parseQuantity(cols[0].trim());
                        nameCol = 1;
                    }
                    name = cleanItemName(cols[nameCol]);

                    if (nameCol == 0) {
                        for (int i = 1; i < cols.length; i++) {
                            String c = cols[i].trim();
                            if (c.toLowerCase().endsWith("m3") || c.toLowerCase().endsWith("isk")) continue;
                            if (QTY_PATTERN.matcher(c).matches()) {
                                qty = parseQuantity(c);
                                break;
                            }
                        }
                    }
                } else {
                    // "1000 x Tritanium", "Tritanium x1000", "1000 Tritanium" oder "Tritanium 1000"
                    Matcher xLeadMatch = X_LEAD_PATTERN.matcher(trimmedLine);
                    Matcher xTrailMatch = X_TRAIL_PATTERN.matcher(trimmedLine);
                    Matcher leadMatch = LEAD_PATTERN.matcher(trimmedLine);
                    Matcher trailMatch = TRAIL_PATTERN.matcher(trimmedLine);

                    if (xLeadMatch.matches()) {
                        qty = parseQuantity(xLeadMatch.group(1));
                        name = cleanItemName(xLeadMatch.group(2));
                    } else if (xTrailMatch.matches()) {
                        name = cleanItemName(xTrailMatch.group(1));
                        qty = parseQuantity(xTrailMatch.group(2));
                    } else if (leadMatch.matches()) {
                        qty = parseQuantity(leadMatch.group(1));
                        name = cleanItemName(leadMatch.group(2));
                    } else if (trailMatch.matches()) {
                        name = cleanItemName(trailMatch.group(1));
                        qty = parseQuantity(trailMatch.group(2));
                    } else {
                        name = cleanItemName(trimmedLine);
                        qty = 1;
                    }
                }
            }

            if (name != null && !name.isBlank()) {
                String lookupKey = name.toLowerCase();
                ParsedItemDto dto = aggregatedItems.computeIfAbsent(lookupKey, k -> {
                    ParsedItemDto newDto = new ParsedItemDto();
                    newDto.setRawName(lookupKey); // Speichern erstmal den Namen
                    return newDto;
                });
                // Originalnamen behalten für die Anzeige
                dto.setRawName(name);
                dto.addQuantity(qty);
            }
        }

        // DB-Lookup für alle aggregierten Items
        for (ParsedItemDto dto : aggregatedItems.values()) {
            TypeDetailsProjection details = invTypeRepository.findTypeDetailsByName(dto.getRawName());
            if (details != null) {
                dto.setTypeId(details.getTypeId());
                dto.setRawName(details.getTypeName()); // Exakter Name aus der DB
                dto.setVolumeEach(details.getVolume() != null ? details.getVolume() : 0.0);
                dto.setCategoryId(details.getCategoryId());
                dto.setResolved(true);
            }
        }

        return new ArrayList<>(aggregatedItems.values());
    }

    /** Der EVE-Client liefert je nach Fenster geschützte Leerzeichen mit. */
    private String normalizeSpaces(String line) {
        return line.replace((char) 0x00A0, ' ')  // NO-BREAK SPACE
                .replace((char) 0x202F, ' ')     // NARROW NO-BREAK SPACE
                .replace((char) 0x2007, ' ')     // FIGURE SPACE
                .trim();
    }

    private String cleanItemName(String name) {
        return name.replaceAll("\\*+$", "").trim();
    }

    private long parseQuantity(String qtyString) {
        try {
            return Long.parseLong(qtyString.replaceAll("[.,]", ""));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
