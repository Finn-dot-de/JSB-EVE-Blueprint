package com.eve.own.auth.backend.domain.fleet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.fleet.dto.ReadinessDtos;
import com.eve.own.auth.backend.domain.fleet.repository.ReadinessQueryRepository;
import com.eve.own.auth.backend.testsupport.FakeTuple;
import jakarta.persistence.Tuple;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * EFT-Text kommt aus Pyfa, aus dem Spiel und aus Foren - in jeweils eigener
 * Formatierung. Genau diese Vielfalt pruefen die Tests.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EFT-Fitting einlesen")
class EftParserServiceTest {

    private static final long CATEGORY_SHIP = 6L;
    private static final long CATEGORY_MODULE = 7L;
    private static final long CATEGORY_DRONE = 18L;
    private static final long CATEGORY_CHARGE = 8L;

    private static final long EFFECT_HIGH = 12L;
    private static final long EFFECT_MID = 13L;
    private static final long EFFECT_LOW = 11L;
    private static final long EFFECT_RIG = 2663L;
    private static final long EFFECT_SUBSYSTEM = 3772L;

    @Mock private ReadinessQueryRepository queryRepo;

    private EftParserService service;

    @BeforeEach
    void setUp() {
        service = new EftParserService(queryRepo);
        when(queryRepo.resolveTypesByName(anyList())).thenReturn(List.of());
    }

    /** Beschreibt die SDE-Antwort fuer einen Namen. */
    private record Sde(String name, long typeId, Long categoryId, Long slotEffectId) {}

    private void sdeKnows(Sde... types) {
        Map<String, Sde> byLookup = new java.util.LinkedHashMap<>();
        for (Sde type : types) {
            byLookup.put(type.name().toLowerCase(Locale.ROOT), type);
        }
        when(queryRepo.resolveTypesByName(anyList())).thenAnswer(call -> {
            List<String> lookups = call.getArgument(0);
            List<Tuple> rows = new ArrayList<>();
            for (String lookup : lookups) {
                Sde type = byLookup.get(lookup);
                if (type != null) {
                    rows.add(FakeTuple.of(
                            "lookup", lookup,
                            "typeId", type.typeId(),
                            "typeName", type.name(),
                            "categoryId", type.categoryId(),
                            "groupName", "Gruppe",
                            "slotEffectId", type.slotEffectId()));
                }
            }
            return rows;
        });
    }

    @Nested
    @DisplayName("Textparsing")
    class TextParsing {

        @Test
        @DisplayName("liest Schiffstyp und Fitting-Namen aus der Kopfzeile")
        void readsHeader() {
            EftParserService.RawFit fit = service.parseText("[Nestor, Logi-Setup]");

            assertThat(fit.shipName()).isEqualTo("Nestor");
            assertThat(fit.fitName()).isEqualTo("Logi-Setup");
        }

        @Test
        @DisplayName("laesst Kommata im Fitting-Namen zu")
        void allowsCommasInFitName() {
            EftParserService.RawFit fit = service.parseText("[Nestor, Logi, Version 2]");

            assertThat(fit.shipName()).isEqualTo("Nestor");
            assertThat(fit.fitName()).isEqualTo("Logi, Version 2");
        }

        @Test
        @DisplayName("liest die Mengenangabe am Zeilenende")
        void readsQuantitySuffix() {
            EftParserService.RawFit fit = service.parseText("""
                    [Nestor, Logi]
                    Hobgoblin II x5
                    Nanite Repair Paste x50
                    """);

            assertThat(fit.lines()).extracting(EftParserService.RawLine::quantity)
                    .containsExactly(5, 50);
        }

        @Test
        @DisplayName("trennt Modul und geladene Munition am ersten Komma")
        void splitsModuleAndCharge() {
            EftParserService.RawFit fit = service.parseText("""
                    [Nestor, Logi]
                    Heavy Missile Launcher II, Scourge Fury Heavy Missile
                    """);

            assertThat(fit.lines()).singleElement().satisfies(line -> {
                assertThat(line.name()).isEqualTo("Heavy Missile Launcher II");
                assertThat(line.chargeName()).isEqualTo("Scourge Fury Heavy Missile");
            });
        }

        @Test
        @DisplayName("ueberspringt Leerzeilen, Kommentare und leere Slots")
        void skipsNoise() {
            EftParserService.RawFit fit = service.parseText("""
                    [Nestor, Logi]

                    # ein Kommentar
                    // noch einer
                    [Empty High slot]
                    [empty low slot]
                    Large Shield Extender II
                    """);

            assertThat(fit.lines()).extracting(EftParserService.RawLine::name)
                    .containsExactly("Large Shield Extender II");
        }

        @Test
        @DisplayName("kommt mit Windows- und Mac-Zeilenenden zurecht")
        void handlesForeignLineEndings() {
            EftParserService.RawFit windows = service.parseText("[Nestor, Logi]\r\nDrone Damage Amplifier II");
            EftParserService.RawFit mac = service.parseText("[Nestor, Logi]\rDrone Damage Amplifier II");

            assertThat(windows.lines()).hasSize(1);
            assertThat(mac.lines()).hasSize(1);
        }

        @Test
        @DisplayName("wertet eine unlesbare Menge als ein Stueck")
        void treatsUnreadableQuantityAsOne() {
            // Eine Zahl jenseits von int darf die Zeile nicht verschlucken.
            EftParserService.RawFit fit = service.parseText("""
                    [Nestor, Logi]
                    Hobgoblin II x99999999999999999999
                    """);

            assertThat(fit.lines()).singleElement()
                    .satisfies(line -> assertThat(line.quantity()).isEqualTo(1));
        }

        @Test
        @DisplayName("weist Text ohne gueltige Kopfzeile ab")
        void rejectsMissingHeader() {
            assertThatThrownBy(() -> service.parseText("Large Shield Extender II"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("EFT-Format");
        }

        @Test
        @DisplayName("weist leeren Text ab")
        void rejectsEmptyText() {
            assertThatThrownBy(() -> service.parseText(null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.parseText("   "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("weist Text ab, der nur aus Kommentaren besteht")
        void rejectsCommentsOnly() {
            assertThatThrownBy(() -> service.parseText("# nur ein Kommentar"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("EFT-Format");
        }
    }

    @Nested
    @DisplayName("Aufloesung gegen die SDE")
    class SdeResolution {

        @Test
        @DisplayName("ordnet die Module ihren Slots anhand der SDE-Effekte zu")
        void groupsModulesBySlotEffect() {
            sdeKnows(
                    new Sde("Nestor", 33472L, CATEGORY_SHIP, null),
                    new Sde("Large Shield Extender II", 3841L, CATEGORY_MODULE, EFFECT_MID),
                    new Sde("Heavy Missile Launcher II", 2404L, CATEGORY_MODULE, EFFECT_HIGH),
                    new Sde("Damage Control II", 2048L, CATEGORY_MODULE, EFFECT_LOW),
                    new Sde("Capital Ancillary Current Router I", 31156L, CATEGORY_MODULE, EFFECT_RIG),
                    new Sde("Legion Defensive - Adaptive Augmenter", 29983L, CATEGORY_MODULE, EFFECT_SUBSYSTEM),
                    new Sde("Hobgoblin II", 2456L, CATEGORY_DRONE, null),
                    new Sde("Nanite Repair Paste", 28668L, CATEGORY_CHARGE, null));

            ReadinessDtos.ParsedFitDto fit = service.parseAndResolve("""
                    [Nestor, Alles dabei]
                    Heavy Missile Launcher II
                    Large Shield Extender II
                    Damage Control II
                    Capital Ancillary Current Router I
                    Legion Defensive - Adaptive Augmenter
                    Hobgoblin II x5
                    Nanite Repair Paste x50
                    """);

            assertThat(fit.groups()).extracting(ReadinessDtos.FitSlotGroupDto::name)
                    .containsExactly("High Slots", "Mid Slots", "Low Slots", "Rigs",
                            "Subsystems", "Drohnen", "Cargo / Ladung");
        }

        @Test
        @DisplayName("fasst gleiche Module zu einer Zeile mit Menge zusammen")
        void mergesIdenticalModules() {
            sdeKnows(
                    new Sde("Nestor", 33472L, CATEGORY_SHIP, null),
                    new Sde("Heavy Missile Launcher II", 2404L, CATEGORY_MODULE, EFFECT_HIGH));

            ReadinessDtos.ParsedFitDto fit = service.parseAndResolve("""
                    [Nestor, Launcher]
                    Heavy Missile Launcher II
                    Heavy Missile Launcher II
                    Heavy Missile Launcher II
                    """);

            assertThat(fit.groups()).singleElement().satisfies(group -> {
                assertThat(group.modules()).hasSize(1);
                assertThat(group.modules().getFirst().quantity()).isEqualTo(3);
                assertThat(group.moduleCount()).isEqualTo(3);
            });
            assertThat(fit.moduleCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("haelt Module mit unterschiedlicher Munition auseinander")
        void keepsDifferentChargesApart() {
            sdeKnows(
                    new Sde("Nestor", 33472L, CATEGORY_SHIP, null),
                    new Sde("Heavy Missile Launcher II", 2404L, CATEGORY_MODULE, EFFECT_HIGH),
                    new Sde("Scourge Fury Heavy Missile", 24519L, CATEGORY_CHARGE, null),
                    new Sde("Mjolnir Fury Heavy Missile", 24515L, CATEGORY_CHARGE, null));

            ReadinessDtos.ParsedFitDto fit = service.parseAndResolve("""
                    [Nestor, Launcher]
                    Heavy Missile Launcher II, Scourge Fury Heavy Missile
                    Heavy Missile Launcher II, Mjolnir Fury Heavy Missile
                    """);

            assertThat(fit.groups().getFirst().modules()).hasSize(2)
                    .extracting(ReadinessDtos.FitModuleDto::chargeName)
                    .containsExactly("Scourge Fury Heavy Missile", "Mjolnir Fury Heavy Missile");
        }

        @Test
        @DisplayName("meldet nicht aufloesbare Namen statt sie zu verschweigen")
        void reportsUnresolvedNames() {
            sdeKnows(new Sde("Nestor", 33472L, CATEGORY_SHIP, null));

            ReadinessDtos.ParsedFitDto fit = service.parseAndResolve("""
                    [Nestor, Logi]
                    Erfundenes Modul II
                    Erfundenes Modul II
                    """);

            assertThat(fit.unresolved()).containsExactly("Erfundenes Modul II");
        }

        @Test
        @DisplayName("meldet eine nicht aufloesbare Munition eigens")
        void reportsUnresolvedCharge() {
            sdeKnows(
                    new Sde("Nestor", 33472L, CATEGORY_SHIP, null),
                    new Sde("Heavy Missile Launcher II", 2404L, CATEGORY_MODULE, EFFECT_HIGH));

            ReadinessDtos.ParsedFitDto fit = service.parseAndResolve("""
                    [Nestor, Logi]
                    Heavy Missile Launcher II, Erfundene Rakete
                    """);

            assertThat(fit.unresolved()).containsExactly("Erfundene Rakete");
        }

        @Test
        @DisplayName("liefert Symbol und Ansicht des Schiffs mit")
        void providesShipImages() {
            sdeKnows(new Sde("Nestor", 33472L, CATEGORY_SHIP, null));

            ReadinessDtos.ParsedFitDto fit = service.parseAndResolve("[Nestor, Logi]");

            assertThat(fit.shipTypeId()).isEqualTo(33472L);
            assertThat(fit.iconUrl()).isEqualTo("https://images.evetech.net/types/33472/icon?size=64");
            assertThat(fit.renderUrl()).isEqualTo("https://images.evetech.net/types/33472/render?size=256");
        }

        @Test
        @DisplayName("weist einen unbekannten Schiffstyp ab")
        void rejectsUnknownShip() {
            assertThatThrownBy(() -> service.parseAndResolve("[Erfundenes Schiff, Logi]"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unbekannter Schiffstyp");
        }

        @Test
        @DisplayName("weist ein Modul in der Kopfzeile als Schiff ab")
        void rejectsNonShipInHeader() {
            sdeKnows(new Sde("Damage Control II", 2048L, CATEGORY_MODULE, EFFECT_LOW));

            assertThatThrownBy(() -> service.parseAndResolve("[Damage Control II, Unsinn]"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("kein Schiff");
        }

        @Test
        @DisplayName("laesst ein Schiff ohne Kategorie in der SDE durch")
        void acceptsShipWithoutCategory() {
            sdeKnows(new Sde("Nestor", 33472L, null, null));

            assertThat(service.parseAndResolve("[Nestor, Logi]").shipTypeName()).isEqualTo("Nestor");
        }

        @Test
        @DisplayName("legt ein Modul ohne bekannten Slot in die Ladung")
        void putsUnknownSlotIntoCargo() {
            sdeKnows(
                    new Sde("Nestor", 33472L, CATEGORY_SHIP, null),
                    new Sde("Nanite Repair Paste", 28668L, CATEGORY_CHARGE, null));

            ReadinessDtos.ParsedFitDto fit = service.parseAndResolve("""
                    [Nestor, Logi]
                    Nanite Repair Paste x50
                    """);

            assertThat(fit.groups()).singleElement()
                    .satisfies(group -> assertThat(group.name()).isEqualTo("Cargo / Ladung"));
        }
    }

    @Test
    @DisplayName("stellt die Bildadressen als Hilfsmittel bereit")
    void exposesImageHelpers() {
        assertThat(EftParserService.icon(34L)).contains("/types/34/icon");
        assertThat(EftParserService.render(34L)).contains("/types/34/render");
    }
}
