package com.eve.own.auth.backend.esi;

import static org.assertj.core.api.Assertions.assertThat;

import com.eve.own.auth.backend.esi.EsiService.FuzzworkBuy;
import com.eve.own.auth.backend.esi.EsiService.FuzzworkPrice;
import com.eve.own.auth.backend.esi.EsiService.FuzzworkSell;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Was passiert, wenn die Marktquelle mit Nullen antwortet.
 *
 * <p>Der belegte Fall: HTTP 200, formal einwandfreies JSON, und darin ist jede
 * Zahl 0 - fuer Tritanium ebenso wie fuer PLEX, aus jedem Netz und von jeder
 * Station. Kein Parse-Fehler, kein Formatbruch, kein Netzproblem. Genau deshalb
 * lief der Abgleich einen Tag lang stuendlich durch und meldete jedes Mal
 * Erfolg, waehrend er die Tabelle mit Nullen fuellte.</p>
 */
class FuzzworkNullpreisTest {

    private static FuzzworkPrice preis(Double kauf, Double verkauf) {
        return new FuzzworkPrice(new FuzzworkBuy(kauf), new FuzzworkSell(verkauf));
    }

    @Test
    @DisplayName("wertet eine Antwort aus lauter Nullen als Ausfall der Quelle")
    void nurNullenIstEinAusfall() {
        Map<String, FuzzworkPrice> antwort = new LinkedHashMap<>();
        antwort.put("34", preis(0.0, 0.0));
        antwort.put("35", preis(0.0, 0.0));
        antwort.put("11399", preis(0.0, 0.0));

        var sauber = EsiService.sanitizeFuzzwork(antwort, 3);

        // Leer, nicht "drei Eintraege ohne Wert". Beide Aufrufer zaehlen leere
        // Antworten als fehlgeschlagenen Block und lassen die alten Preise
        // stehen. Ohne diese Umdeutung war der Totalausfall vom Erfolg nicht zu
        // unterscheiden: die Antwort war ja nicht leer, sondern voller Nullen -
        // und wurde als "2165 Typen gespeichert" protokolliert.
        assertThat(sauber).isEmpty();
    }

    @Test
    @DisplayName("streicht einzelne Nullpreise, behaelt aber die echten")
    void einzelneNullenFallenWeg() {
        Map<String, FuzzworkPrice> antwort = new LinkedHashMap<>();
        antwort.put("34", preis(3.77, 3.82));
        antwort.put("35", preis(0.0, 0.0));

        var sauber = EsiService.sanitizeFuzzwork(antwort, 2);

        // Ein Typ ohne Order ist normal - deshalb ist eine einzelne Null noch
        // kein Ausfall. Der Eintrag faellt trotzdem ganz weg, statt als Huelle
        // mit Nullen weiterzureisen: sonst prueft der Aufrufer wieder nur, ob
        // die Huelle da ist, und schreibt die 0 als Preis.
        assertThat(sauber).containsOnlyKeys("34");
        assertThat(sauber.get("34").buy().max()).isEqualTo(3.77);
        assertThat(sauber.get("34").sell().min()).isEqualTo(3.82);
    }

    @Test
    @DisplayName("behaelt eine Seite, wenn nur die andere fehlt")
    void halbeAuskunftBleibtErhalten() {
        Map<String, FuzzworkPrice> antwort = new LinkedHashMap<>();
        // Niemand bietet, aber es gibt ein Verkaufsangebot. Das kommt bei
        // duenn gehandelten Typen vor und ist eine echte Auskunft.
        antwort.put("34", preis(0.0, 3.82));

        var sauber = EsiService.sanitizeFuzzwork(antwort, 1);

        assertThat(sauber).containsOnlyKeys("34");
        // "niemand kauft" darf nicht zu "kostet nichts" werden.
        assertThat(sauber.get("34").buy()).isNull();
        assertThat(sauber.get("34").sell().min()).isEqualTo(3.82);
    }

    @Test
    @DisplayName("laesst eine leere Antwort leer")
    void leerBleibtLeer() {
        assertThat(EsiService.sanitizeFuzzwork(null, 5)).isEmpty();
        assertThat(EsiService.sanitizeFuzzwork(Map.of(), 5)).isEmpty();
    }
}
