package com.eve.buy.bot.backend.domain.buybot.service;

import com.eve.buy.bot.backend.domain.buybot.dto.ParsedItemDto;
import com.eve.buy.bot.backend.domain.buybot.dto.TypeDetailsProjection;
import com.eve.buy.bot.backend.domain.eve.repository.InvTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Tests für den Listen-Parser.
 *
 * <p>Der EVE-Client liefert je nach Fenster ein anderes Format. Diese Tests halten die
 * bekannten Varianten fest, damit eine Änderung am Parser nicht stillschweigend eine davon
 * kaputt macht - eine falsch gelesene Menge kostet unmittelbar ISK.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EvePasteParserService")
class EvePasteParserServiceTest {

    /** Kleiner Ausschnitt der Statikdatenbank für die Tests. */
    private static final Map<String, TypeDetails> SDE = Map.of(
            "tritanium", new TypeDetails(34L, "Tritanium", 0.01, 4L),
            "pyerite", new TypeDetails(35L, "Pyerite", 0.01, 4L),
            "mexallon", new TypeDetails(36L, "Mexallon", 0.01, 4L),
            "veldspar", new TypeDetails(1230L, "Veldspar", 0.1, 25L),
            "antimatter charge m", new TypeDetails(222L, "Antimatter Charge M", 0.0125, 8L),
            "rifter", new TypeDetails(587L, "Rifter", 27289.0, 6L));

    @Mock
    private InvTypeRepository invTypeRepository;

    private EvePasteParserService parser;

    @BeforeEach
    void setUp() {
        parser = new EvePasteParserService(invTypeRepository);
        // lenient: der Test fuer leere Eingaben fragt die Statikdatenbank gar nicht erst ab
        lenient().when(invTypeRepository.findTypeDetailsByName(anyString()))
                .thenAnswer(call -> SDE.get(call.getArgument(0, String.class).toLowerCase()));
    }

    @Test
    @DisplayName("zählt dieselbe Position aus mehreren Zeilen zusammen")
    void aggregatesDuplicateLines() {
        List<ParsedItemDto> items = parser.parseAndResolveInput("Tritanium 1000\nTritanium 1000\nTritanium 500");

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().getQuantity()).isEqualTo(2500);
        assertThat(items.getFirst().getTypeId()).isEqualTo(34L);
    }

    @ParameterizedTest(name = "{0} -> {1} x {2}")
    @DisplayName("erkennt die Mengenangabe in allen bekannten Kopierformaten")
    @CsvSource({
            "'1000 Tritanium',        Tritanium,           1000",
            "'Tritanium 1000',        Tritanium,           1000",
            "'Tritanium x1000',       Tritanium,           1000",
            "'Tritanium x 1000',      Tritanium,           1000",
            "'1000x Tritanium',       Tritanium,           1000",
            "'1000 x Tritanium',      Tritanium,           1000",
            "'Tritanium 28.000',      Tritanium,           28000",
            "'Antimatter Charge M x1000', Antimatter Charge M, 1000",
            "'Tritanium',             Tritanium,           1"
    })
    void readsQuantityFromEveryKnownFormat(String line, String expectedName, long expectedQuantity) {
        List<ParsedItemDto> items = parser.parseAndResolveInput(line);

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().getRawName()).isEqualTo(expectedName);
        assertThat(items.getFirst().getQuantity()).isEqualTo(expectedQuantity);
    }

    @Test
    @DisplayName("liest die tabgetrennte Kopie aus dem Inventar")
    void readsTabSeparatedInventoryPaste() {
        String paste = "Tritanium\t28.000\tMineral\tAsteroid\t280,00 m3\n"
                + "Pyerite\t5.400\tMineral\tAsteroid\t54,00 m3";

        List<ParsedItemDto> items = parser.parseAndResolveInput(paste);

        assertThat(items).hasSize(2);
        assertThat(items).extracting(ParsedItemDto::getRawName, ParsedItemDto::getQuantity)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Tritanium", 28000L),
                        org.assertj.core.groups.Tuple.tuple("Pyerite", 5400L));
    }

    @Test
    @DisplayName("entfernt das Sternchen unverpackter Items")
    void stripsAsteriskFromUnpackagedItems() {
        List<ParsedItemDto> items = parser.parseAndResolveInput("Rifter*\t2");

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().getRawName()).isEqualTo("Rifter");
        assertThat(items.getFirst().getQuantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("erkennt die Menge auch, wenn sie in der ersten Spalte steht")
    void readsQuantityFromLeadingColumn() {
        List<ParsedItemDto> items = parser.parseAndResolveInput("1000    Tritanium");

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().getRawName()).isEqualTo("Tritanium");
        assertThat(items.getFirst().getQuantity()).isEqualTo(1000);
    }

    @Test
    @DisplayName("behandelt geschützte Leerzeichen wie normale")
    void normalizesNonBreakingSpaces() {
        // Bewusst ueber den Codepoint, damit das Zeichen beim Formatieren nicht verloren geht
        String withNbsp = "Tritanium" + (char) 0x00A0 + "1000";

        List<ParsedItemDto> items = parser.parseAndResolveInput(withNbsp);

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().getRawName()).isEqualTo("Tritanium");
        assertThat(items.getFirst().getQuantity()).isEqualTo(1000);
    }

    @Test
    @DisplayName("überspringt Kopf- und Summenzeilen")
    void skipsHeaderAndTotalLines() {
        List<ParsedItemDto> items = parser.parseAndResolveInput("Total: 1.234 ISK\nTritanium 100\nGesamt 5");

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().getRawName()).isEqualTo("Tritanium");
    }

    @Test
    @DisplayName("gibt unbekannte Namen als ungelöst zurück statt sie zu verwerfen")
    void keepsUnknownNamesUnresolved() {
        List<ParsedItemDto> items = parser.parseAndResolveInput("Trittanium 100");

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().isResolved()).isFalse();
        assertThat(items.getFirst().getRawName()).isEqualTo("Trittanium");
    }

    @Test
    @DisplayName("übernimmt die Schreibweise aus der Statikdatenbank")
    void usesCanonicalNameFromSde() {
        List<ParsedItemDto> items = parser.parseAndResolveInput("tRiTaNiUm 10");

        assertThat(items.getFirst().getRawName()).isEqualTo("Tritanium");
        assertThat(items.getFirst().getVolumeEach()).isEqualTo(0.01);
        assertThat(items.getFirst().getCategoryId()).isEqualTo(4L);
    }

    @Test
    @DisplayName("liefert für leere Eingaben eine leere Liste")
    void returnsEmptyListForBlankInput() {
        assertThat(parser.parseAndResolveInput(null)).isEmpty();
        assertThat(parser.parseAndResolveInput("   \n  ")).isEmpty();
    }

    /**
     * Testdoppel für die Projektion aus der Statikdatenbank.
     *
     * @param typeId     Type-ID
     * @param typeName   Name in der Schreibweise der Statikdatenbank
     * @param volume     Volumen je Einheit
     * @param categoryId Kategorie des Items
     */
    private record TypeDetails(Long typeId, String typeName, Double volume, Long categoryId)
            implements TypeDetailsProjection {

        @Override
        public Long getTypeId() {
            return typeId;
        }

        @Override
        public String getTypeName() {
            return typeName;
        }

        @Override
        public Double getVolume() {
            return volume;
        }

        @Override
        public Long getCategoryId() {
            return categoryId;
        }
    }
}
