package com.eve.own.auth.backend.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.eve.own.auth.backend.domain.assets.service.MyAssetService;
import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.fleet.service.EftParserService;
import jakarta.transaction.Transactional;
import java.lang.reflect.Method;
import org.springframework.transaction.annotation.Propagation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Waechter ueber Transaktionsgrenzen, die man nicht sieht.
 *
 * <p>Der Anlass ist ein Fehler, der monatelang unbemerkt lief und teuer war.
 * {@code AuthService.getValidAccessToken} erneuert den EVE-Token ueber einen
 * HTTP-Aufruf. Lief die Methode in der Transaktion des Aufrufers mit, dann
 * markierte Spring diese bei einem toten Refresh-Token als rollback-only. Der
 * Aufrufer fing die Ausnahme, protokollierte sie und arbeitete weiter - und
 * erst beim Commit fiel alles auseinander:</p>
 *
 * <pre>
 * UnexpectedRollbackException: Transaction silently rolled back
 *   because it has been marked as rollback-only
 * </pre>
 *
 * <p>In Produktion legten so <b>drei</b> Charaktere mit abgelaufenem Token den
 * Industriejob-Abgleich fuer <b>225</b> lahm - bei jedem Lauf, alle zehn
 * Minuten, ohne dass es je gutging.</p>
 *
 * <p>Dieser Test prueft eine Anmerkung und nicht das Verhalten, und das ist
 * eine bewusste Abwaegung: das Verhalten braucht einen echten
 * Transaktionsmanager samt Datenbank und einen kaputten Token. Die Anmerkung
 * dagegen ist genau die Stelle, an der jemand beim Aufraeumen "vereinfacht" -
 * und der Fehler kaeme lautlos zurueck.</p>
 */
class TransactionBoundaryTest {

    @Test
    @DisplayName("die Token-Erneuerung läuft in einer eigenen Transaktion")
    void tokenErneuerungLaeuftEigenstaendig() throws NoSuchMethodException {
        Method methode = AuthService.class.getMethod("getValidAccessToken", Character.class);
        Transactional annotation = methode.getAnnotation(Transactional.class);

        assertThat(annotation)
                .as("getValidAccessToken muss @Transactional tragen")
                .isNotNull();

        // Ohne REQUIRES_NEW reisst ein toter Refresh-Token die Transaktion des
        // Aufrufers mit - und der merkt es erst beim Commit, wenn seine ganze
        // Arbeit schon verloren ist.
        assertThat(annotation.value())
                .as("Ein fehlgeschlagener Token-Refresh darf die Transaktion des "
                        + "Aufrufers nicht als rollback-only markieren")
                .isEqualTo(Transactional.TxType.REQUIRES_NEW);
    }

    @Test
    @DisplayName("die Auflösung des Hauptcharakters reißt den Aufrufer nicht mit")
    void hauptcharakterAufloesungLaeuftEigenstaendig() throws NoSuchMethodException {
        // Mehrere Aufrufer fangen die Ausnahme bewusst ab und antworten milde:
        // die Bauortsuche lässt einen Hinweis weg, die Planung rechnet ohne
        // Forschungsboni weiter. Ohne eigene Transaktion wird aus dieser Milde
        // ein 500 beim Commit - der Nutzer kann dann gar keinen Bauort wählen.
        var annotation = MyAssetService.class
                .getMethod("resolveMainId", Long.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    @DisplayName("ein unlesbares Fitting reißt nicht das ganze Readiness-Board mit")
    void fittingParserLaeuftEigenstaendig() throws NoSuchMethodException {
        // Der Vorsatz lautet: ein unlesbares Fitting darf ein Schiff nicht aus
        // der Doktrin verschwinden lassen. Ohne eigene Transaktion kehrt er sich
        // um - EIN kaputter Doktrin-Eintrag lässt das ganze Board mit 500
        // antworten, und die Warnung im Log sieht harmlos aus.
        var annotation = EftParserService.class
                .getMethod("parseAndResolve", String.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
