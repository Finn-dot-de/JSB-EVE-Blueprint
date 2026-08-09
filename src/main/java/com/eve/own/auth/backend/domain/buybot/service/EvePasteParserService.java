package com.eve.own.auth.backend.domain.buybot.service;

import com.eve.own.auth.backend.domain.buybot.dto.ParsedItemDto;
import com.eve.own.auth.backend.domain.buybot.dto.TypeDetailsProjection;
import com.eve.own.auth.backend.domain.eve.repository.InvTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class EvePasteParserService {

    private final InvTypeRepository invTypeRepository;

    // Erkennt Zahlen mit optionalen Tausendertrennern (z.B. 28.000 oder 5,400 oder 1000)
    private static final String NUM_SRC = "(?:\\d{1,3}(?:[.,]\\d{3})+|\\d+)";
    private static final Pattern QTY_PATTERN = Pattern.compile("^" + NUM_SRC + "$");
    private static final Pattern LEAD_PATTERN = Pattern.compile("^(" + NUM_SRC + ")\\s+(.+)$");
    private static final Pattern TRAIL_PATTERN = Pattern.compile("^(.+?)\\s+(" + NUM_SRC + ")$");

    public List<ParsedItemDto> parseAndResolveInput(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return new ArrayList<>();
        }

        Map<String, ParsedItemDto> aggregatedItems = new HashMap<>();
        String[] lines = rawInput.split("\\r?\\n");

        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty()) continue;

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
                    name = cleanItemName(cols[0]);
                    for (int i = 1; i < cols.length; i++) {
                        String c = cols[i].trim();
                        if (c.toLowerCase().endsWith("m3") || c.toLowerCase().endsWith("isk")) continue;
                        if (QTY_PATTERN.matcher(c).matches()) {
                            qty = parseQuantity(c);
                            break;
                        }
                    }
                } else {
                    // "1000 Tritanium" oder "Tritanium 1000"
                    Matcher leadMatch = LEAD_PATTERN.matcher(trimmedLine);
                    Matcher trailMatch = TRAIL_PATTERN.matcher(trimmedLine);

                    if (leadMatch.matches()) {
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