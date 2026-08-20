package com.eve.own.auth.backend.domain.discord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Der Abgleich auf Zuruf - und die Rueckmeldung, die ihn erst brauchbar macht.
 *
 * <p>Die Ursache {@link DiscordRollenBefund.Ursache#ABGLEICH_STEHT_AUS} benennt
 * eine Wartezeit von bis zu dreissig Minuten. Ohne einen Knopf daneben waere sie
 * eine Feststellung ohne Handlungsmoeglichkeit. Und ohne Rueckmeldung waere der
 * Knopf stumm: Der Abgleich protokollierte bisher nur, wer ihn ausloeste, sah
 * das Log nicht - und sah in Discord nach, genau wie vorher.</p>
 */
class DiscordRoleSyncServiceTest {

    private static final String KONTO = "1424800550347735184";
    private static final String ROLLE_MITGLIED = "1000";
    private static final String ROLLE_DIRECTOR = "2000";

    private DiscordRoleAuditService audit;
    private DiscordBotService bot;
    private DiscordSyncStand syncStand;
    private DiscordRoleSyncService abgleich;

    @BeforeEach
    void setUp() {
        audit = Mockito.mock(DiscordRoleAuditService.class);
        bot = Mockito.mock(DiscordBotService.class);
        syncStand = new DiscordSyncStand();
        abgleich = new DiscordRoleSyncService(audit, bot, syncStand);
    }

    /** Der Plan, wie ihn die Pruefung liefert - hier vorgegeben statt gerechnet. */
    private void plan(String konto, List<String> verwaltet, List<String> soll) {
        when(audit.planFuer(1L)).thenReturn(Optional.of(new DiscordRollenplan(
                1L, "Tom", 1L, "Tom", konto, "Tom", verwaltet, soll,
                Map.of(ROLLE_MITGLIED, "ROLE_MITGLIED", ROLLE_DIRECTOR, "ROLE_DIRECTOR"))));
    }

    @Test
    @DisplayName("meldet je Rolle, was gesetzt und was entzogen wurde")
    void meldetJeRolleDasErgebnis() {
        // Die Frage nach jedem Anstoss lautet "hat es gewirkt?". Bliebe die
        // Antwort im Log, wuerde sie in Discord von Hand nachgeschlagen - und
        // damit waere der ganze Knopf ueberfluessig.
        plan(KONTO, List.of(ROLLE_MITGLIED, ROLLE_DIRECTOR), List.of(ROLLE_MITGLIED));
        when(bot.syncManagedRoles(anyString(), any(), any(), any())).thenReturn(List.of(
                DiscordRollenErgebnis.gelungen(ROLLE_MITGLIED, DiscordRollenErgebnis.Aktion.VERGEBEN),
                DiscordRollenErgebnis.gelungen(ROLLE_DIRECTOR, DiscordRollenErgebnis.Aktion.ENTZOGEN)));

        DiscordSyncErgebnis ergebnis = abgleich.stosseAn(1L).orElseThrow();

        assertThat(ergebnis.ausgefuehrt()).isTrue();
        assertThat(ergebnis.vollstaendig()).isTrue();
        assertThat(ergebnis.rollen()).extracting(DiscordSyncErgebnis.Zeile::authRolle,
                        DiscordSyncErgebnis.Zeile::aktion, DiscordSyncErgebnis.Zeile::erfolg)
                .containsExactly(
                        tuple("ROLE_MITGLIED", DiscordRollenErgebnis.Aktion.VERGEBEN, true),
                        tuple("ROLE_DIRECTOR", DiscordRollenErgebnis.Aktion.ENTZOGEN, true));
    }

    @Test
    @DisplayName("eine abgelehnte Rolle steht mit Grund da und verhindert die uebrigen nicht")
    void abgelehnteRolleMitGrund() {
        // Discord vergibt nur Rollen, die unter der eigenen des Bots stehen.
        // Steht EINE darueber, darf sie weder die uebrigen mitreissen noch
        // unsichtbar bleiben: Ohne den Grund wartet jemand auf eine Rolle, die
        // nie kommt, und sucht den Fehler im Auth.
        plan(KONTO, List.of(ROLLE_MITGLIED, ROLLE_DIRECTOR),
                List.of(ROLLE_MITGLIED, ROLLE_DIRECTOR));
        when(bot.syncManagedRoles(anyString(), any(), any(), any())).thenReturn(List.of(
                DiscordRollenErgebnis.gescheitert(ROLLE_DIRECTOR,
                        DiscordRollenErgebnis.Aktion.VERGEBEN, "Discord verweigert diese Rolle (403)."),
                DiscordRollenErgebnis.gelungen(ROLLE_MITGLIED, DiscordRollenErgebnis.Aktion.VERGEBEN)));

        DiscordSyncErgebnis ergebnis = abgleich.stosseAn(1L).orElseThrow();

        assertThat(ergebnis.ausgefuehrt()).isTrue();
        assertThat(ergebnis.vollstaendig()).isFalse();
        assertThat(ergebnis.rollen()).hasSize(2);
        assertThat(ergebnis.rollen().getFirst().erfolg()).isFalse();
        assertThat(ergebnis.rollen().getFirst().grund()).contains("403");
        assertThat(ergebnis.rollen().getFirst().authRolle()).isEqualTo("ROLE_DIRECTOR");
        // Die zweite Rolle ist trotzdem durchgegangen.
        assertThat(ergebnis.rollen().get(1).erfolg()).isTrue();
    }

    @Test
    @DisplayName("ohne Verknuepfung wird nichts geschickt, aber ein Grund genannt")
    void ohneVerknuepfungMitBegruendung() {
        // Der Knopf muss auch dann etwas sagen, wenn er nichts tun kann. Ein
        // stummer Fehlschlag sieht aus wie ein kaputter Bot.
        plan(null, List.of(ROLLE_MITGLIED), List.of(ROLLE_MITGLIED));

        DiscordSyncErgebnis ergebnis = abgleich.stosseAn(1L).orElseThrow();

        assertThat(ergebnis.ausgefuehrt()).isFalse();
        assertThat(ergebnis.hinweis()).isNotBlank();
        assertThat(ergebnis.rollen()).isEmpty();
        verify(bot, never()).syncManagedRoles(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("ein 404 des Kontos wird begruendet statt geworfen")
    void kontoNichtMehrAufDemServer() {
        // Wer den Knopf drueckt, bekommt sonst eine Fehlerseite ohne Aussage.
        // "Das Konto ist kein Mitglied mehr" ist eine Auskunft, mit der sich
        // etwas anfangen laesst.
        plan(KONTO, List.of(ROLLE_MITGLIED), List.of(ROLLE_MITGLIED));
        when(bot.syncManagedRoles(anyString(), any(), any(), any()))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND,
                        "Not Found", HttpHeaders.EMPTY, new byte[0], null));

        DiscordSyncErgebnis ergebnis = abgleich.stosseAn(1L).orElseThrow();

        assertThat(ergebnis.ausgefuehrt()).isFalse();
        assertThat(ergebnis.hinweis()).contains("404");
    }

    @Test
    @DisplayName("auch ein stolpernder Aufruf endet mit einer Begruendung")
    void netzfehlerWirdBegruendet() {
        plan(KONTO, List.of(ROLLE_MITGLIED), List.of(ROLLE_MITGLIED));
        when(bot.syncManagedRoles(anyString(), any(), any(), any()))
                .thenThrow(new ResourceAccessException("Zeitablauf"));

        DiscordSyncErgebnis ergebnis = abgleich.stosseAn(1L).orElseThrow();

        assertThat(ergebnis.ausgefuehrt()).isFalse();
        assertThat(ergebnis.hinweis()).contains("Zeitablauf");
    }

    @Test
    @DisplayName("ohne gepflegte Zuordnung laeuft der Abgleich, hat aber nichts zu tun")
    void ohneZuordnungKeinAufruf() {
        // Solange kein Mapping gepflegt ist, hat das Auth in Discord nichts zu
        // suchen. Das als Fehlschlag zu melden, schickte jemanden auf die Suche
        // nach einem Fehler, den es nicht gibt.
        plan(KONTO, List.of(), List.of());

        DiscordSyncErgebnis ergebnis = abgleich.stosseAn(1L).orElseThrow();

        assertThat(ergebnis.ausgefuehrt()).isTrue();
        assertThat(ergebnis.rollen()).isEmpty();
        assertThat(ergebnis.hinweis()).isNotBlank();
        verify(bot, never()).syncManagedRoles(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("der Anstoss vermerkt den Lauf, damit die Wartezeit-Ursache verschwindet")
    void anstossVermerktDenLauf() {
        // Ohne den Vermerk stuende nach dem Anstoss weiter "der Abgleich lief
        // noch nicht" an jeder fehlenden Rolle - eine Wartezeit, die abgelaufen
        // ist. Der Nutzer drueckte den Knopf ein zweites Mal.
        plan(KONTO, List.of(ROLLE_MITGLIED), List.of(ROLLE_MITGLIED));
        when(bot.syncManagedRoles(anyString(), any(), any(), any())).thenReturn(List.of(
                DiscordRollenErgebnis.gelungen(ROLLE_MITGLIED, DiscordRollenErgebnis.Aktion.VERGEBEN)));
        assertThat(syncStand.letzterLauf(KONTO)).isEmpty();

        abgleich.stosseAn(1L);

        assertThat(syncStand.letzterLauf(KONTO)).isPresent();
    }

    @Test
    @DisplayName("ein gescheiterter Aufruf gilt nicht als Lauf")
    void fehlschlagIstKeinLauf() {
        // Sonst behauptete die Pruefung "der Abgleich lief" fuer einen Aufruf,
        // der Discord nie erreicht hat - und ersetzte eine richtige Ursache
        // durch "unbekannt".
        plan(KONTO, List.of(ROLLE_MITGLIED), List.of(ROLLE_MITGLIED));
        when(bot.syncManagedRoles(anyString(), any(), any(), any()))
                .thenThrow(new ResourceAccessException("Zeitablauf"));

        abgleich.stosseAn(1L);

        assertThat(syncStand.letzterLauf(KONTO)).isEmpty();
    }

    @Test
    @DisplayName("zu einem unbekannten Charakter gibt es keine Rueckmeldung")
    void unbekannterCharakter() {
        // Leer statt einer Rueckmeldung ueber einen Abgleich, den es nicht gab:
        // Der Endpunkt antwortet darauf mit 404. Ein erfundenes Ergebnis liesse
        // einen Tippfehler in der Kennung wie einen erfolgreichen Lauf
        // aussehen.
        when(audit.planFuer(404L)).thenReturn(Optional.empty());

        assertThat(abgleich.stosseAn(404L)).isEmpty();
        verify(bot, never()).syncManagedRoles(anyString(), any(), any(), any());
    }
}
