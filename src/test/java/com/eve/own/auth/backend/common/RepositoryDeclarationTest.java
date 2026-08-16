package com.eve.own.auth.backend.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wacht darueber, dass jede Repository-Schnittstelle fuer sich steht.
 *
 * <p>Anlass ist ein echter Ausfall: sechs Repositories lagen als verschachtelte
 * Schnittstellen in einer Sammelklasse. Das uebersetzt sauber, die Tests mit
 * Mocks laufen durch - aber Spring Data uebergeht verschachtelte Schnittstellen
 * beim Suchen und legt fuer sie <em>keine</em> Bohne an. Aufgefallen ist es erst,
 * als die Anwendung im Betrieb nicht mehr hochkam.</p>
 *
 * <p>Dieser Test kostet nichts und faengt genau diesen Fall. Er braucht weder
 * Datenbank noch Spring-Kontext - beides gibt es in diesem Projekt in den Tests
 * nicht, und gerade deshalb hat niemand gemerkt, dass der Start kaputt war.</p>
 */
class RepositoryDeclarationTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    /**
     * Eine Schnittstelle, die eines der Spring-Data-Repositories erweitert.
     *
     * <p>Bewusst auch {@code CrudRepository} und {@code Repository}: die Falle
     * haengt nicht an {@code JpaRepository}, sondern daran, dass Spring Data die
     * Schnittstelle finden muss.</p>
     */
    private static final Pattern REPOSITORY_INTERFACE = Pattern.compile(
            "interface\\s+(\\w+)\\s*(?:<[^>]*>\\s*)?extends\\s+[^{]*\\b"
            + "(JpaRepository|CrudRepository|PagingAndSortingRepository|Repository)\\b");

    @Test
    @DisplayName("jede Repository-Schnittstelle steht auf oberster Ebene ihrer Datei")
    void keineVerschachteltenRepositories() throws IOException {
        List<String> verstoesse = new ArrayList<>();

        try (Stream<Path> dateien = Files.walk(SOURCE_ROOT)) {
            for (Path datei : dateien.filter(p -> p.toString().endsWith(".java")).toList()) {
                String quelle = Files.readString(datei, StandardCharsets.UTF_8);
                Matcher m = REPOSITORY_INTERFACE.matcher(quelle);
                while (m.find()) {
                    String name = m.group(1);
                    String dateiname = datei.getFileName().toString().replace(".java", "");
                    if (!name.equals(dateiname)) {
                        verstoesse.add("%s liegt verschachtelt in %s.java".formatted(name, dateiname));
                    }
                }
            }
        }

        assertThat(verstoesse)
                .as("""
                        Spring Data findet verschachtelte Repository-Schnittstellen nicht und legt \
                        für sie keine Bohne an. Die Anwendung übersetzt trotzdem und die Tests laufen \
                        durch - sie startet nur nicht mehr. Jede Repository-Schnittstelle gehört in \
                        eine eigene Datei gleichen Namens.""")
                .isEmpty();
    }

    @Test
    @DisplayName("die Industrie-Repositories sind einzeln vorhanden")
    void industrieRepositoriesVorhanden() {
        // Namentlich geprüft und nicht bloß gezählt: eine gestiegene Gesamtzahl
        // beweist nicht, dass die richtigen dabei sind.
        Path ordner = SOURCE_ROOT.resolve(Path.of("com", "eve", "own", "auth", "backend",
                "domain", "industry", "repository"));

        for (String name : List.of(
                "IndustryOrderRepository", "IndustryOrderRequirementRepository",
                "IndustryOrderBaselineRepository", "IndustryJobRepository",
                "IndustryOrderJobRepository", "CharacterBlueprintRepository",
                "IndustryStructureRepository")) {
            assertThat(ordner.resolve(name + ".java"))
                    .as("%s muss eine eigene Datei sein", name)
                    .exists();
        }
    }
}
