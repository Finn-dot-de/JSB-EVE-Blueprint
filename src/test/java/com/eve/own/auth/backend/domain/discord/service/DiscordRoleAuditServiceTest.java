package com.eve.own.auth.backend.domain.discord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.discord.entity.DiscordConnection;
import com.eve.own.auth.backend.domain.discord.entity.DiscordRoleMapping;
import com.eve.own.auth.backend.domain.discord.repository.DiscordConnectionRepository;
import com.eve.own.auth.backend.domain.discord.repository.DiscordRoleMappingRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
 * Die Pruefung stellt fest und aendert nichts.
 *
 * <p>Anlass sind zwei EVE-Charaktere, die auf dasselbe Discord-Konto zeigen -
 * in den echten Daten Comander-Video und Morpheus Revenant. Der Abgleich lief
 * zweimal ueber dieselbe Person, jedes Mal mit den Rollen eines anderen
 * Charakters; der zweite Lauf ueberschrieb den ersten. Solange beide dieselben
 * Rollen trugen, fiel nichts auf. Sobald nicht, verlor jemand Rollen, ohne dass
 * es jemand mit dem Auth in Verbindung brachte.</p>
 *
 * <p>Die wichtigste Zusicherung hier ist {@link #handvergebeneRolleOhneMappingBleibtStumm()}:
 * Was das Auth nicht verwaltet, darf nie als "zu viel" erscheinen. Genau diese
 * Verwechslung hat den Abgleich schon einmal dazu gebracht, fremde Rollen
 * abzuraeumen - und ein Fehlalarm verleitet dazu, es von Hand zu wiederholen.</p>
 */
class DiscordRoleAuditServiceTest {

    private static final String KONTO = "1424800550347735184";

    /** Discord-Rollen, fuer die es ein Mapping gibt - nur die sind verwaltet. */
    private static final String ROLLE_MITGLIED = "1000";
    private static final String ROLLE_DIRECTOR = "2000";

    /** Von Hand in Discord vergeben, dem Auth voellig unbekannt. */
    private static final String ROLLE_FARBE_LILA = "9999";

    private DiscordConnectionRepository connectionRepo;
    private CharacterRepository characterRepo;
    private DiscordRoleMappingRepository mappingRepo;
    private DiscordBotService bot;
    private DiscordSyncStand syncStand;
    private DiscordRoleAuditService pruefung;

    /** Alle Charaktere, die der Test kennt - Grundlage fuer findById. */
    private final Map<Long, Character> charaktere = new HashMap<>();
    private final List<DiscordConnection> verbindungen = new ArrayList<>();
    private final List<DiscordRoleMapping> mappings = new ArrayList<>();

    /** Die Rollen, die es auf dem Discord-Server gibt. */
    private final List<DiscordBotService.GuildRole> serverrollen = new ArrayList<>();

    @BeforeEach
    void setUp() {
        connectionRepo = Mockito.mock(DiscordConnectionRepository.class);
        characterRepo = Mockito.mock(CharacterRepository.class);
        mappingRepo = Mockito.mock(DiscordRoleMappingRepository.class);
        bot = Mockito.mock(DiscordBotService.class);
        // Der echte Stand, kein Mock: Er ist eine Map mit zwei Methoden, und
        // sein Verhalten - leer heisst "noch nicht gelaufen" - ist genau das,
        // was hier geprueft werden soll.
        syncStand = new DiscordSyncStand();
        pruefung = new DiscordRoleAuditService(connectionRepo, characterRepo, mappingRepo, bot, syncStand);

        when(connectionRepo.findAll()).thenReturn(verbindungen);
        when(mappingRepo.findAll()).thenReturn(mappings);
        when(connectionRepo.findById(anyLong())).thenAnswer(a -> verbindungen.stream()
                .filter(v -> a.getArgument(0).equals(v.getCharacterId()))
                .findFirst());
        when(characterRepo.findById(anyLong()))
                .thenAnswer(a -> Optional.ofNullable(charaktere.get(a.<Long>getArgument(0))));
        when(bot.getGuildRoles()).thenReturn(serverrollen);

        mapping("ROLE_MITGLIED", ROLLE_MITGLIED);
        mapping("ROLE_DIRECTOR", ROLLE_DIRECTOR);
        serverrolle(ROLLE_MITGLIED, "Mitglied");
        serverrolle(ROLLE_DIRECTOR, "Director");
        serverrolle(ROLLE_FARBE_LILA, "Marauders Associated");
    }

    // ---- Aufbau ----------------------------------------------------------

    private void mapping(String authRolle, String discordRolle) {
        DiscordRoleMapping m = new DiscordRoleMapping();
        m.setAuthRole(authRolle);
        m.setDiscordRoleId(discordRolle);
        mappings.add(m);
    }

    private void serverrolle(String discordRolle, String name) {
        serverrollen.add(new DiscordBotService.GuildRole(discordRolle, name));
    }

    /** Die Zeile zu einer Auth-Rolle - der Kern der Gegenueberstellung. */
    private DiscordRollenBefund zeile(List<DiscordRollenBefund> rollen, String authRolle) {
        return rollen.stream()
                .filter(z -> z.authRolle().equals(authRolle))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Keine Zeile fuer " + authRolle));
    }

    /** Ein Charakter, der mit dem Konto verknuepft ist. {@code mainId} null = Main. */
    private Character charakter(long id, String name, Long mainId, String... authRollen) {
        Character c = new Character();
        c.setId(id);
        c.setName(name);
        c.setMainCharacterId(mainId);
        c.setRoles(new LinkedHashSet<>(List.of(authRollen)));
        charaktere.put(id, c);
        return c;
    }

    private void verknuepfe(long characterId, String discordUserId) {
        DiscordConnection v = new DiscordConnection();
        v.setCharacterId(characterId);
        v.setDiscordUserId(discordUserId);
        verbindungen.add(v);
    }

    private void istInDiscord(String... rollen) {
        when(bot.getMemberRoles(KONTO)).thenReturn(List.of(rollen));
    }

    private DiscordRoleAudit einzigerBefund() {
        List<DiscordRoleAudit> alle = pruefung.pruefeAlle();
        assertThat(alle).hasSize(1);
        return alle.getFirst();
    }

    // ---- Die Faelle ------------------------------------------------------

    @Test
    @DisplayName("Uebereinstimmung ergibt keinen Befund")
    void uebereinstimmungIstStill() {
        // Der Gegenfall zu allen uebrigen. Ohne ihn wuerde eine Pruefung, die
        // pauschal meldet, jeden anderen Test hier trotzdem bestehen - und die
        // Meldung waere wertlos, weil sie immer kaeme.
        charakter(1L, "Comander-Video", null, "ROLE_MITGLIED", "ROLE_DIRECTOR");
        verknuepfe(1L, KONTO);
        istInDiscord(ROLLE_MITGLIED, ROLLE_DIRECTOR);

        DiscordRoleAudit befund = einzigerBefund();

        assertThat(befund.hatBefund()).isFalse();
        assertThat(befund.pruefbar()).isTrue();
        assertThat(befund.fehlendeRollen()).isEmpty();
        assertThat(befund.ueberzaehligeRollen()).isEmpty();
        assertThat(befund.mainCharacterId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("eine fehlende Rolle wird als fehlend gemeldet")
    void fehlendeRolleWirdGemeldet() {
        // Der Fall, den es bisher gar nicht zu sehen gab: Der Abgleich setzt
        // die Rolle, Discord lehnt mit 403 ab, die Rolle sitzt nicht - und das
        // Auth erfuhr davon nichts, weil es den Ist-Zustand nie gelesen hat.
        charakter(1L, "Comander-Video", null, "ROLE_MITGLIED", "ROLE_DIRECTOR");
        verknuepfe(1L, KONTO);
        istInDiscord(ROLLE_MITGLIED);

        DiscordRoleAudit befund = einzigerBefund();

        assertThat(befund.fehlendeRollen()).containsExactly(ROLLE_DIRECTOR);
        assertThat(befund.ueberzaehligeRollen()).isEmpty();
        assertThat(befund.hatBefund()).isTrue();
    }

    @Test
    @DisplayName("eine ueberzaehlige verwaltete Rolle wird gemeldet")
    void ueberzaehligeVerwalteteRolleWirdGemeldet() {
        // Verwaltet heisst: es gibt ein Mapping, das Auth hat sie selbst
        // vergeben. Steht sie im Auth nicht mehr, ist sie in Discord ein Rest -
        // typischerweise nach einem Rollenentzug, den ein DELETE mit 403
        // quittiert hat. Ohne diese Meldung behaelt jemand Rechte, die ihm
        // niemand mehr zugedacht hat.
        charakter(1L, "Comander-Video", null, "ROLE_MITGLIED");
        verknuepfe(1L, KONTO);
        istInDiscord(ROLLE_MITGLIED, ROLLE_DIRECTOR);

        DiscordRoleAudit befund = einzigerBefund();

        assertThat(befund.ueberzaehligeRollen()).containsExactly(ROLLE_DIRECTOR);
        assertThat(befund.fehlendeRollen()).isEmpty();
    }

    @Test
    @DisplayName("eine handvergebene Rolle ohne Mapping wird NICHT gemeldet")
    void handvergebeneRolleOhneMappingBleibtStumm() {
        // DER wichtigste Test dieser Klasse.
        //
        // Ohne den Filter auf verwaltete Rollen erschiene hier die Farbrolle
        // als "zu viel". Sie ist es nicht - das Auth hat sie nie vergeben und
        // weiss ueber sie nichts. Genau diese Verwechslung hat den Abgleich
        // schon einmal dazu gebracht, fremde Rollen abzuraeumen: Farbrollen,
        // Pingrollen, alles von Hand Vergebene. Ein Fehlalarm an dieser Stelle
        // verleitet dazu, es von Hand zu wiederholen - und diesmal endgueltig.
        charakter(1L, "Comander-Video", null, "ROLE_MITGLIED");
        verknuepfe(1L, KONTO);
        istInDiscord(ROLLE_MITGLIED, ROLLE_FARBE_LILA);

        DiscordRoleAudit befund = einzigerBefund();

        assertThat(befund.ueberzaehligeRollen()).isEmpty();
        assertThat(befund.fehlendeRollen()).isEmpty();
        assertThat(befund.hatBefund()).isFalse();
    }

    @Test
    @DisplayName("eine Auth-Rolle ohne hinterlegte Discord-Rolle macht keinen Befund")
    void mappingOhneDiscordRolleZaehltNicht() {
        // Die Verwaltung speichert das Loeschen eines Mappings als leeres Feld.
        // Wuerde ein leerer Text als verwaltete Rolle mitgezaehlt, vergliche die
        // Pruefung gegen eine Rolle, die es in Discord nicht gibt, und meldete
        // sie bei jedem Konto als fehlend.
        DiscordRoleMapping leer = new DiscordRoleMapping();
        leer.setAuthRole("ROLE_OHNE_MAPPING");
        leer.setDiscordRoleId("  ");
        mappings.add(leer);

        // Und die zweite Schreibweise desselben Zustands: eine Zeile, die nie
        // eine Discord-Rolle hatte, steht mit null da statt mit Leerzeichen.
        DiscordRoleMapping nieGesetzt = new DiscordRoleMapping();
        nieGesetzt.setAuthRole("ROLE_NIE_GEMAPPT");
        mappings.add(nieGesetzt);

        charakter(1L, "Comander-Video", null, "ROLE_MITGLIED", "ROLE_OHNE_MAPPING");
        verknuepfe(1L, KONTO);
        istInDiscord(ROLLE_MITGLIED);

        assertThat(einzigerBefund().hatBefund()).isFalse();
    }

    @Test
    @DisplayName("403 ergibt 'nicht pruefbar', nicht 'alles fehlt'")
    void verweigerteAuskunftIstKeinRollenbefund() {
        // Am Server-Owner und bei zu tiefer Bot-Rolle scheitert schon das
        // Lesen. Wuerde man das mit einer leeren Rollenliste gleichsetzen,
        // meldete die Pruefung dort jede Soll-Rolle als fehlend - der lauteste
        // Fehlalarm ausgerechnet an dem Konto, an dem sich nichts machen laesst.
        charakter(1L, "Comander-Video", null, "ROLE_MITGLIED", "ROLE_DIRECTOR");
        verknuepfe(1L, KONTO);
        when(bot.getMemberRoles(KONTO)).thenThrow(HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY, new byte[0], null));

        DiscordRoleAudit befund = einzigerBefund();

        assertThat(befund.pruefbar()).isFalse();
        assertThat(befund.hinweis()).contains("403");
        assertThat(befund.fehlendeRollen()).isEmpty();
        assertThat(befund.ueberzaehligeRollen()).isEmpty();
        // Und es ist kein Rollenbefund: sonst stuende neben jedem unerreichbaren
        // Konto dauerhaft "Rollen weichen ab".
        assertThat(befund.hatBefund()).isFalse();
    }

    @Test
    @DisplayName("404 ergibt 'nicht pruefbar' mit eigenem Hinweis")
    void mitgliedNichtMehrAufDemServer() {
        // Anderer Grund, andere Abhilfe: Hier ist niemand mehr da, den man
        // hoeher ziehen koennte. Zusammengefasst mit dem 403 stuende in beiden
        // Faellen derselbe unbrauchbare Rat.
        charakter(1L, "Comander-Video", null, "ROLE_MITGLIED");
        verknuepfe(1L, KONTO);
        when(bot.getMemberRoles(KONTO)).thenThrow(HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null));

        DiscordRoleAudit befund = einzigerBefund();

        assertThat(befund.pruefbar()).isFalse();
        assertThat(befund.hinweis()).contains("404");
    }

    @Test
    @DisplayName("ein stolperndes Konto reisst die uebrigen nicht mit")
    void netzfehlerBleibtOertlich() {
        // Zeitablauf, Rate Limit, abgebrochene Verbindung: alles keine Aussage
        // ueber Rollen. Ohne diesen Zweig endete die Pruefung beim ersten
        // Stolperer, und die Konten dahinter wuerden nie angesehen.
        charakter(1L, "Comander-Video", null, "ROLE_MITGLIED");
        charakter(2L, "Jemand Anderes", null, "ROLE_MITGLIED");
        verknuepfe(1L, KONTO);
        verknuepfe(2L, "anderes-konto");
        when(bot.getMemberRoles(KONTO)).thenThrow(new ResourceAccessException("Zeitablauf"));
        when(bot.getMemberRoles("anderes-konto")).thenReturn(List.of(ROLLE_MITGLIED));

        List<DiscordRoleAudit> alle = pruefung.pruefeAlle();

        assertThat(alle).hasSize(2);
        assertThat(alle.getFirst().pruefbar()).isFalse();
        assertThat(alle.getFirst().hinweis()).contains("Zeitablauf");
        assertThat(alle.get(1).pruefbar()).isTrue();
    }

    @Test
    @DisplayName("zwei Charaktere auf einem Konto mit verschiedenem Soll werden gemeldet")
    void doppelverknuepfungMitWiderspruch() {
        // Der Fall aus den echten Daten. Der Abgleich laeuft zweimal ueber
        // dieselbe Person; wer zuletzt drankommt, bestimmt das Ergebnis. Ohne
        // diese Meldung sieht man nur, dass jemandem Rollen fehlen, nie warum -
        // und der naechste Lauf stellt sie wieder her, bis der uebernaechste sie
        // erneut nimmt.
        charakter(1L, "Comander-Video", null, "ROLE_MITGLIED", "ROLE_DIRECTOR");
        charakter(2L, "Morpheus Revenant", null, "ROLE_MITGLIED");
        verknuepfe(1L, KONTO);
        verknuepfe(2L, KONTO);
        istInDiscord(ROLLE_MITGLIED, ROLLE_DIRECTOR);

        DiscordRoleAudit befund = einzigerBefund();

        assertThat(befund.sollUneinig()).isTrue();
        assertThat(befund.hatBefund()).isTrue();
        assertThat(befund.charaktere()).hasSize(2);
        // Ein Ergebnis, nicht zwei: Zwei Zeilen ueber dieselbe Person wuerden
        // einander widersprechen, ohne dass jemand erkennt, dass es dieselbe ist.
        verify(bot).getMemberRoles(KONTO);
        // Die Rollenliste des Servers wird einmal je Durchlauf geholt, nicht je
        // Konto - sie gilt fuer den ganzen Server.
        verify(bot).getGuildRoles();
        verifyNoMoreInteractions(bot);
    }

    @Test
    @DisplayName("dasselbe Soll an zwei Charakteren ist kein Widerspruch")
    void doppelverknuepfungOhneWiderspruch() {
        // Zwei Charaktere an einem Konto sind fuer sich genommen in Ordnung -
        // gemeldet wird nur der Widerspruch. Ohne diesen Gegenfall genuegte
        // "mehr als einer" als Meldebedingung, und die Warnung staende an jedem
        // Spieler, der einen Alt verknuepft hat.
        charakter(1L, "Comander-Video", null, "ROLE_MITGLIED");
        charakter(2L, "Morpheus Revenant", 1L, "ROLE_MITGLIED");
        verknuepfe(1L, KONTO);
        verknuepfe(2L, KONTO);
        istInDiscord(ROLLE_MITGLIED);

        DiscordRoleAudit befund = einzigerBefund();

        assertThat(befund.sollUneinig()).isFalse();
        assertThat(befund.hatBefund()).isFalse();
    }

    @Test
    @DisplayName("das Soll haengt am Main, nicht am verknuepften Alt")
    void sollKommtVomMain() {
        // Der Kern der Aenderung. Verknuepft ist hier nur der Alt, der keine
        // Director-Rolle traegt. Waere das Soll an ihm festgemacht, gaelte die
        // Director-Rolle des Kontos als ueberzaehlig - und wer dem Befund
        // folgte, naehme dem Main eine Rolle weg, die ihm zusteht.
        charakter(1L, "Comander-Video", null, "ROLE_MITGLIED", "ROLE_DIRECTOR");
        charakter(2L, "Morpheus Revenant", 1L, "ROLE_MITGLIED");
        verknuepfe(2L, KONTO);
        istInDiscord(ROLLE_MITGLIED, ROLLE_DIRECTOR);

        DiscordRoleAudit befund = einzigerBefund();

        assertThat(befund.mainCharacterId()).isEqualTo(1L);
        assertThat(befund.mainCharacterName()).isEqualTo("Comander-Video");
        assertThat(befund.ueberzaehligeRollen()).isEmpty();
        assertThat(befund.fehlendeRollen()).isEmpty();
    }

    @Test
    @DisplayName("faellt auf den verknuepften Charakter zurueck, wenn der Main fehlt")
    void mainNichtInDerDatenbank() {
        // Der Main kann geloescht oder auf Gast zurueckgesetzt worden sein,
        // waehrend der Alt noch verknuepft ist. Ohne den Rueckfall stuende hier
        // eine NullPointerException und riss die Pruefung aller Konten mit -
        // wegen einer einzigen verwaisten Zeile.
        charakter(2L, "Morpheus Revenant", 999L, "ROLE_MITGLIED");
        verknuepfe(2L, KONTO);
        istInDiscord(ROLLE_MITGLIED);

        DiscordRoleAudit befund = einzigerBefund();

        assertThat(befund.mainCharacterId()).isEqualTo(2L);
        assertThat(befund.hatBefund()).isFalse();
    }

    @Test
    @DisplayName("ohne Main unter den Verknuepften entscheidet die kleinste Kennung")
    void ohneVerknuepftenMainStabileWahl() {
        // Zwei Alts verschiedener Accounts an einem Konto. Wichtig ist weniger,
        // welcher gewinnt, als dass immer derselbe gewinnt: Haenge die Wahl an
        // der Reihenfolge der Datenbankzeilen, wechselte der Befund von Lauf zu
        // Lauf - genau der Zufall, den diese Pruefung abstellen soll.
        charakter(10L, "Main A", null, "ROLE_MITGLIED");
        charakter(11L, "Alt A", 10L, "ROLE_MITGLIED");
        charakter(21L, "Alt B", 20L, "ROLE_DIRECTOR");
        verknuepfe(21L, KONTO);
        verknuepfe(11L, KONTO);
        istInDiscord(ROLLE_MITGLIED);

        DiscordRoleAudit befund = einzigerBefund();

        assertThat(befund.mainCharacterId()).isEqualTo(10L);
        assertThat(befund.sollUneinig()).isTrue();
    }

    @Test
    @DisplayName("uebergeht eine Verknuepfung ohne Charakter")
    void karteileicheOhneCharakter() {
        // Zu einer Verknuepfung, deren Charakter es nicht mehr gibt, laesst sich
        // kein Soll bilden. Sie zu melden hiesse, eine Zeile in die Liste zu
        // setzen, zu der niemand etwas tun kann - und sie kostete einen Aufruf
        // an eine Schnittstelle mit Rate Limit.
        verknuepfe(404L, KONTO);

        assertThat(pruefung.pruefeAlle()).isEmpty();
        verify(bot, never()).getMemberRoles(anyString());
    }

    @Test
    @DisplayName("uebergeht eine Verknuepfung ohne Discord-Kennung")
    void verbindungOhneKennung() {
        // Kaeme sie durch, fragte die Pruefung Discord nach einem Mitglied ohne
        // Kennung - eine sinnlose Anfrage, deren 404 als "nicht pruefbar" in
        // der Liste stuende.
        charakter(1L, "Comander-Video", null, "ROLE_MITGLIED");
        charakter(2L, "Morpheus Revenant", null, "ROLE_MITGLIED");
        verknuepfe(1L, "   ");
        // Beide Schreibweisen: eine abgebrochene Verknuepfung hinterlaesst
        // null, eine zurueckgesetzte Leerzeichen.
        verknuepfe(2L, null);

        assertThat(pruefung.pruefeAlle()).isEmpty();
        verify(bot, never()).getMemberRoles(anyString());
    }

    @Test
    @DisplayName("ein Charakter ohne Rollen erwartet nichts")
    void charakterOhneRollen() {
        charakter(1L, "Comander-Video", null);
        verknuepfe(1L, KONTO);
        istInDiscord(ROLLE_FARBE_LILA);

        DiscordRoleAudit befund = einzigerBefund();

        assertThat(befund.hatBefund()).isFalse();
    }

    @Test
    @DisplayName("vertraegt einen Charakter, dessen Rollensammlung gar nicht da ist")
    void charakterMitNullRollen() {
        // Die Sammlung ist am Charakter zwar vorbelegt, aber nicht erzwungen -
        // ein direkt gesetztes null genuegt. Ohne die Absicherung endete die
        // Pruefung aller Konten an einer NullPointerException, ausgeloest von
        // einer einzigen Zeile.
        charakter(1L, "Comander-Video", null).setRoles(null);
        verknuepfe(1L, KONTO);
        istInDiscord(ROLLE_MITGLIED);

        DiscordRoleAudit befund = einzigerBefund();

        // Nichts erwartet, also ist die vorhandene Rolle ueberzaehlig - sie ist
        // verwaltet, das Auth hat sie einmal selbst vergeben.
        assertThat(befund.ueberzaehligeRollen()).containsExactly(ROLLE_MITGLIED);
    }

    @Test
    @DisplayName("die Pruefung schreibt nichts nach Discord")
    void pruefenAendertNichts() {
        // Die Zusicherung, ohne die man dieses Werkzeug nicht im Zeitplan laufen
        // lassen kann: Es wird gelesen und sonst nichts. Ein syncManagedRoles
        // an dieser Stelle waere eine Reparatur, die niemand angeordnet hat.
        charakter(1L, "Comander-Video", null, "ROLE_MITGLIED", "ROLE_DIRECTOR");
        verknuepfe(1L, KONTO);
        istInDiscord(ROLLE_FARBE_LILA);

        pruefung.pruefeAlle();

        verify(bot).getMemberRoles(KONTO);
        verify(bot).getGuildRoles();
        verifyNoMoreInteractions(bot);
    }

    @Test
    @DisplayName("die Meldung im Zeitplan laeuft ueber alle Faelle, ohne etwas zu aendern")
    void zeitplanMeldetUndAendertNichts() {
        // Deckt den Weg ab, den in Betrieb niemand ansieht. Wichtig ist, dass
        // auch ein unpruefbares und ein widerspruechliches Konto ihn nicht
        // abbrechen - sonst faende der Zeitplan hinter dem ersten Server-Owner
        // nie wieder etwas.
        charakter(1L, "Comander-Video", null, "ROLE_MITGLIED", "ROLE_DIRECTOR");
        charakter(2L, "Morpheus Revenant", null, "ROLE_MITGLIED");
        charakter(3L, "Stiller Typ", null, "ROLE_MITGLIED");
        charakter(4L, "Server Owner", null, "ROLE_MITGLIED");
        verknuepfe(1L, KONTO);
        verknuepfe(2L, KONTO);
        verknuepfe(3L, "stilles-konto");
        verknuepfe(4L, "owner-konto");
        istInDiscord(ROLLE_MITGLIED);
        when(bot.getMemberRoles("stilles-konto")).thenReturn(List.of(ROLLE_MITGLIED));
        when(bot.getMemberRoles("owner-konto")).thenThrow(HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY, new byte[0], null));

        pruefung.meldeAbweichungen();

        verify(bot).getMemberRoles(KONTO);
        verify(bot).getMemberRoles("stilles-konto");
        verify(bot).getMemberRoles("owner-konto");
        verify(bot).getGuildRoles();
        verifyNoMoreInteractions(bot);
    }

    // ---- Die Ursachen ----------------------------------------------------
    //
    // Ab hier steht nicht mehr DASS eine Rolle fehlt, sondern WARUM. "Cap Azubi
    // fehlt" ist die Haelfte der Auskunft: Ob die Zuordnung fehlt, die Rollen-Id
    // auf dem Server nicht mehr existiert, der Bot nicht darf oder der Abgleich
    // schlicht noch nicht lief, verlangt jeweils eine andere Handlung. Ohne
    // eigene, unterscheidbare Ursache je Fall bleibt nur der Blick in Discord.

    @Test
    @DisplayName("die Gegenueberstellung zeigt je Auth-Rolle die Rollen-Id und ob sie sitzt")
    void gegenueberstellungJeAuthRolle() {
        // Der Fall des Nutzers: Von drei Auth-Rollen sitzen zwei, eine nicht.
        // Frueher stand im Ergebnis nur eine Rollen-Id unter "fehlend" - welche
        // Auth-Rolle dahintersteckt, musste man in der Zuordnungstabelle
        // nachschlagen.
        mapping("ROLE_CAP_AZUBI", "3000");
        serverrolle("3000", "Cap Azubi");
        charakter(1L, "Tom", null, "ROLE_MITGLIED", "ROLE_DIRECTOR", "ROLE_CAP_AZUBI");
        verknuepfe(1L, KONTO);
        istInDiscord(ROLLE_MITGLIED, ROLLE_DIRECTOR, ROLLE_FARBE_LILA);

        DiscordRoleAudit befund = einzigerBefund();

        assertThat(befund.rollen()).extracting(DiscordRollenBefund::authRolle)
                .containsExactly("ROLE_CAP_AZUBI", "ROLE_DIRECTOR", "ROLE_MITGLIED");
        DiscordRollenBefund mitglied = zeile(befund.rollen(), "ROLE_MITGLIED");
        assertThat(mitglied.zustand()).isEqualTo(DiscordRollenBefund.Zustand.VORHANDEN);
        assertThat(mitglied.discordRoleId()).isEqualTo(ROLLE_MITGLIED);
        assertThat(mitglied.ursache()).isNull();

        DiscordRollenBefund azubi = zeile(befund.rollen(), "ROLE_CAP_AZUBI");
        assertThat(azubi.zustand()).isEqualTo(DiscordRollenBefund.Zustand.FEHLT);
        assertThat(azubi.discordRoleId()).isEqualTo("3000");
        assertThat(azubi.grund()).isNotBlank();
    }

    @Test
    @DisplayName("eine handvergebene Rolle erscheint als vorhanden, aber nicht als Fehler")
    void handvergebeneRolleIstVorhandenUndKeinFehler() {
        // Der Nutzer nennt "Marauders Associated" ausdruecklich als vorhanden.
        // Sie muss in der Uebersicht stehen - wer sehen will, was ein Konto hat,
        // will sie sehen - und sie darf zugleich nirgends als ueberzaehlig
        // erscheinen. Ohne die Trennung ueber "verwaltet" faellt beides
        // zusammen, und genau diese Verwechslung hat den Abgleich schon einmal
        // dazu gebracht, handvergebene Rollen abzuraeumen.
        charakter(1L, "Tom", null, "ROLE_MITGLIED");
        verknuepfe(1L, KONTO);
        istInDiscord(ROLLE_MITGLIED, ROLLE_FARBE_LILA);

        DiscordRoleAudit befund = einzigerBefund();

        assertThat(befund.weitereDiscordRollen())
                .singleElement()
                .satisfies(rolle -> {
                    assertThat(rolle.discordRoleId()).isEqualTo(ROLLE_FARBE_LILA);
                    assertThat(rolle.name()).isEqualTo("Marauders Associated");
                    assertThat(rolle.verwaltet()).isFalse();
                });
        assertThat(befund.ueberzaehligeRollen()).isEmpty();
        assertThat(befund.hatBefund()).isFalse();
    }

    @Test
    @DisplayName("Ursache: zur Auth-Rolle gibt es gar keine Zuordnung")
    void ursacheKeinMapping() {
        // Der haeufigste Fall nach dem Anlegen einer neuen Rolle im Auth: Sie
        // existiert, aber niemand hat ihr eine Discord-Rolle zugewiesen. Ohne
        // eigene Ursache stuende hier dieselbe Meldung wie bei einem 403 - und
        // man suchte den Fehler beim Bot statt in der Zuordnungstabelle.
        charakter(1L, "Tom", null, "ROLE_MITGLIED", "ROLE_CAP_AZUBI");
        verknuepfe(1L, KONTO);
        istInDiscord(ROLLE_MITGLIED);

        DiscordRollenBefund azubi = zeile(einzigerBefund().rollen(), "ROLE_CAP_AZUBI");

        assertThat(azubi.zustand()).isEqualTo(DiscordRollenBefund.Zustand.FEHLT);
        assertThat(azubi.ursache()).isEqualTo(DiscordRollenBefund.Ursache.KEIN_MAPPING);
        assertThat(azubi.discordRoleId()).isNull();
    }

    @Test
    @DisplayName("Ursache: die Zuordnung existiert, traegt aber keine Rollen-Id")
    void ursacheMappingOhneRollenId() {
        // Ein geloeschtes Mapping wird als leeres Feld gespeichert. Fuer den
        // Leser sieht das aus wie "kein Mapping", ist aber ein anderer Zustand:
        // Die Zeile ist da, jemand hat sie angefasst und geleert. Beide auf eine
        // Ursache einzuebnen, verschweigt den Unterschied, den man zum Handeln
        // braucht.
        DiscordRoleMapping leer = new DiscordRoleMapping();
        leer.setAuthRole("ROLE_CAP_AZUBI");
        leer.setDiscordRoleId("  ");
        mappings.add(leer);
        charakter(1L, "Tom", null, "ROLE_MITGLIED", "ROLE_CAP_AZUBI");
        verknuepfe(1L, KONTO);
        istInDiscord(ROLLE_MITGLIED);

        DiscordRollenBefund azubi = zeile(einzigerBefund().rollen(), "ROLE_CAP_AZUBI");

        assertThat(azubi.ursache()).isEqualTo(DiscordRollenBefund.Ursache.MAPPING_OHNE_ROLLEN_ID);
        assertThat(azubi.ursache()).isNotEqualTo(DiscordRollenBefund.Ursache.KEIN_MAPPING);
    }

    @Test
    @DisplayName("Ursache: die hinterlegte Rollen-Id gibt es auf dem Server nicht mehr")
    void ursacheRolleAufDemServerUnbekannt() {
        // Wird eine Rolle in Discord geloescht und neu angelegt, hat sie eine
        // neue Id - die alte steht weiter in der Zuordnung und wird nie wieder
        // vergeben. Ohne die Rollenliste des Servers laege dieser Fall unter
        // "unbekannt" und niemand kaeme darauf, die Id nachzupflegen.
        mapping("ROLE_CAP_AZUBI", "3000");
        charakter(1L, "Tom", null, "ROLE_CAP_AZUBI");
        verknuepfe(1L, KONTO);
        istInDiscord(ROLLE_MITGLIED);

        DiscordRollenBefund azubi = zeile(einzigerBefund().rollen(), "ROLE_CAP_AZUBI");

        assertThat(azubi.ursache())
                .isEqualTo(DiscordRollenBefund.Ursache.ROLLE_AUF_SERVER_UNBEKANNT);
        assertThat(azubi.discordRoleId()).isEqualTo("3000");
    }

    @Test
    @DisplayName("Ursache: der Abgleich hat dieses Konto noch nicht angefasst")
    void ursacheAbgleichStehtAus() {
        // Der Abgleich laeuft alle dreissig Minuten. Eine gerade vergebene Rolle
        // ist noch nicht angekommen, weil noch niemand sie hinausgeschickt hat -
        // nicht, weil etwas kaputt ist. Ohne diese Ursache stuende dort
        // "unbekannt", und jemand suchte einen Fehler, den es nicht gibt.
        charakter(1L, "Tom", null, "ROLE_MITGLIED", "ROLE_DIRECTOR");
        verknuepfe(1L, KONTO);
        istInDiscord(ROLLE_MITGLIED);

        DiscordRollenBefund director = zeile(einzigerBefund().rollen(), "ROLE_DIRECTOR");

        assertThat(director.ursache()).isEqualTo(DiscordRollenBefund.Ursache.ABGLEICH_STEHT_AUS);
    }

    @Test
    @DisplayName("Ursache: unbekannt, wenn der Abgleich lief und die Rolle trotzdem fehlt")
    void ursacheUnbekanntStattGeraten() {
        // Die wichtigste Zusicherung dieser Reihe: Zuordnung gepflegt, Rolle
        // existiert, Zugriff besteht, Abgleich gelaufen - und die Rolle sitzt
        // nicht. Dann ist die Ursache unbekannt, und das muss auch dastehen.
        // Eine geratene Ursache ist schlimmer als keine, weil ihr jemand folgt
        // und die falsche Stelle repariert.
        syncStand.notiere(KONTO);
        charakter(1L, "Tom", null, "ROLE_MITGLIED", "ROLE_DIRECTOR");
        verknuepfe(1L, KONTO);
        istInDiscord(ROLLE_MITGLIED);

        DiscordRollenBefund director = zeile(einzigerBefund().rollen(), "ROLE_DIRECTOR");

        assertThat(director.ursache()).isEqualTo(DiscordRollenBefund.Ursache.UNBEKANNT);
        assertThat(director.grund()).contains("unbekannt");
    }

    @Test
    @DisplayName("403: jede Rolle des Kontos ist 'nicht feststellbar', keine 'fehlt'")
    void bei403IstKeineRolleFehlend() {
        // Der Regelfall bei diesem Nutzer: sein eigenes Konto liefert seit
        // Stunden 403. Wuerde daraus "fehlt" - fuer jede Rolle des Kontos,
        // einschliesslich der ohne Zuordnung -, staende die lauteste Meldung
        // ausgerechnet an dem Konto, ueber das sich nichts sagen laesst. Und
        // niemand koennte sie abstellen.
        mapping("ROLE_CAP_AZUBI", "3000");
        charakter(1L, "Tom", null, "ROLE_MITGLIED", "ROLE_CAP_AZUBI", "ROLE_OHNE_ZUORDNUNG");
        verknuepfe(1L, KONTO);
        when(bot.getMemberRoles(KONTO)).thenThrow(HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY, new byte[0], null));

        DiscordRoleAudit befund = einzigerBefund();

        assertThat(befund.rollen()).hasSize(3);
        assertThat(befund.rollen())
                .allMatch(z -> z.zustand() == DiscordRollenBefund.Zustand.NICHT_FESTSTELLBAR)
                .allMatch(z -> z.ursache() == DiscordRollenBefund.Ursache.ZUGRIFF_VERWEIGERT);
        assertThat(befund.rollen())
                .noneMatch(z -> z.zustand() == DiscordRollenBefund.Zustand.FEHLT);
        assertThat(befund.fehlendeRollen()).isEmpty();
        assertThat(befund.hatBefund()).isFalse();
    }

    @Test
    @DisplayName("404 traegt eine andere Ursache als 403")
    void bei404EigeneUrsache() {
        // Andere Ursache, andere Abhilfe: Beim 403 zieht man die Bot-Rolle
        // hoeher, beim 404 ist niemand mehr da, an dem sich etwas ziehen liesse.
        charakter(1L, "Tom", null, "ROLE_MITGLIED");
        verknuepfe(1L, KONTO);
        when(bot.getMemberRoles(KONTO)).thenThrow(HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null));

        DiscordRollenBefund mitglied = zeile(einzigerBefund().rollen(), "ROLE_MITGLIED");

        assertThat(mitglied.ursache())
                .isEqualTo(DiscordRollenBefund.Ursache.KONTO_NICHT_AUF_SERVER);
        assertThat(mitglied.zustand()).isEqualTo(DiscordRollenBefund.Zustand.NICHT_FESTSTELLBAR);
    }

    @Test
    @DisplayName("eine unlesbare Rollenliste macht keine Zuordnung veraltet")
    void serverrollenUnbekanntErfindetKeineUrsache() {
        // Scheitert der Abruf der Rollenliste, wissen wir nicht, welche Rollen
        // es gibt. Daraus "die hinterlegte Rolle existiert nicht mehr" zu
        // folgern, meldete jede Zuordnung gleichzeitig als veraltet - ausgeloest
        // von einem einzigen Zeitablauf.
        when(bot.getGuildRoles()).thenThrow(new ResourceAccessException("Zeitablauf"));
        charakter(1L, "Tom", null, "ROLE_MITGLIED", "ROLE_DIRECTOR");
        verknuepfe(1L, KONTO);
        istInDiscord(ROLLE_MITGLIED);

        DiscordRollenBefund director = zeile(einzigerBefund().rollen(), "ROLE_DIRECTOR");

        assertThat(director.ursache())
                .isNotEqualTo(DiscordRollenBefund.Ursache.ROLLE_AUF_SERVER_UNBEKANNT);
        assertThat(director.ursache()).isEqualTo(DiscordRollenBefund.Ursache.ABGLEICH_STEHT_AUS);
    }

    // ---- Die Sicht je Charakter ------------------------------------------

    @Test
    @DisplayName("je Charakter dieselben Zeilen wie am Konto, nicht neu gerechnet")
    void charaktersichtUebernimmtDenKontobefund() {
        // Zwei Charaktere an einem Konto ergeben zwei Zeilen in der Uebersicht -
        // aber nur einen Vergleich. Wuerde je Charakter neu gerechnet, kaeme man
        // an derselben Person zu zwei Ergebnissen und wuesste nicht, welchem zu
        // glauben ist. Und es kostete zwei Aufrufe an eine Schnittstelle mit
        // Rate Limit statt einem.
        charakter(1L, "Comander-Video", null, "ROLE_MITGLIED", "ROLE_DIRECTOR");
        charakter(2L, "Morpheus Revenant", 1L, "ROLE_MITGLIED");
        verknuepfe(1L, KONTO);
        verknuepfe(2L, KONTO);
        istInDiscord(ROLLE_MITGLIED);

        List<DiscordCharacterAudit> sichten = pruefung.pruefeCharaktere();

        assertThat(sichten).hasSize(2);
        assertThat(sichten).extracting(DiscordCharacterAudit::characterName)
                .containsExactly("Comander-Video", "Morpheus Revenant");
        assertThat(sichten).allSatisfy(sicht -> {
            assertThat(sicht.mainCharacterName()).isEqualTo("Comander-Video");
            assertThat(sicht.rollen()).extracting(DiscordRollenBefund::authRolle)
                    .containsExactly("ROLE_DIRECTOR", "ROLE_MITGLIED");
            assertThat(sicht.verknuepft()).isTrue();
        });
        verify(bot).getMemberRoles(KONTO);
    }

    @Test
    @DisplayName("Ursache: der Charakter hat gar keine Discord-Verknuepfung")
    void ursacheKeineVerknuepfung() {
        // Ohne Konto gibt es niemanden, dem der Bot etwas geben koennte. Das ist
        // keine unbeantwortete Frage an Discord, sondern eine Tatsache aus der
        // eigenen Datenbank - und eine mit einer klaren Abhilfe, die niemand
        // anderes fuer den Spieler erledigen kann.
        charakter(1L, "Tom", null, "ROLE_MITGLIED");

        DiscordCharacterAudit sicht = pruefung.pruefeCharakter(1L).orElseThrow();

        assertThat(sicht.verknuepft()).isFalse();
        assertThat(sicht.rollen()).allMatch(
                z -> z.ursache() == DiscordRollenBefund.Ursache.KEINE_VERKNUEPFUNG);
        assertThat(sicht.hinweis()).isNotBlank();
        verify(bot, never()).getMemberRoles(anyString());
    }

    @Test
    @DisplayName("ein unbekannter Charakter ist etwas anderes als ein unverknuepfter")
    void unbekannterCharakterLiefertNichts() {
        // Leer statt einer Zeile ohne Inhalt: Der Endpunkt antwortet darauf mit
        // 404. Gaebe es hier eine leere Gegenueberstellung, saehe ein Tippfehler
        // in der Kennung aus wie ein Charakter ohne Rollen.
        assertThat(pruefung.pruefeCharakter(404L)).isEmpty();
    }

    @Test
    @DisplayName("die Charaktersicht findet das Konto auch ueber einen verknuepften Alt")
    void charaktersichtFindetKontoUeberGeschwister() {
        // Das Konto gehoert dem Account, nicht dem einzelnen Charakter. Ist nur
        // der Alt verknuepft, sitzen die Rollen des Accounts trotzdem an diesem
        // Konto. Wer nur nach der eigenen Zeile suchte, meldete dem Main "nicht
        // verknuepft", waehrend seine Rollen in Discord sichtbar sind.
        charakter(1L, "Comander-Video", null, "ROLE_MITGLIED");
        charakter(2L, "Morpheus Revenant", 1L, "ROLE_MITGLIED");
        verknuepfe(2L, KONTO);
        istInDiscord(ROLLE_MITGLIED);

        DiscordCharacterAudit sicht = pruefung.pruefeCharakter(1L).orElseThrow();

        assertThat(sicht.verknuepft()).isTrue();
        assertThat(sicht.discordUserId()).isEqualTo(KONTO);
        assertThat(sicht.hatBefund()).isFalse();
    }

    // ---- Der Plan fuer den Abgleich --------------------------------------

    @Test
    @DisplayName("der Plan steht auf derselben Rechnung wie der Befund")
    void planKommtAusDerselbenRechnung() {
        // Wuerde der Anstoss selbst rechnen, koennte die Uebersicht "Director
        // fehlt" sagen und der Abgleich etwas anderes setzen - zwei Wahrheiten,
        // zwischen denen niemand entscheiden kann. Das Soll haengt auch hier am
        // Main: verknuepft ist der Alt, gefordert sind die Rollen des Mains.
        charakter(1L, "Comander-Video", null, "ROLE_MITGLIED", "ROLE_DIRECTOR");
        charakter(2L, "Morpheus Revenant", 1L, "ROLE_MITGLIED");
        verknuepfe(2L, KONTO);

        DiscordRollenplan plan = pruefung.planFuer(2L).orElseThrow();

        assertThat(plan.discordUserId()).isEqualTo(KONTO);
        assertThat(plan.sollRollen()).containsExactly(ROLLE_MITGLIED, ROLLE_DIRECTOR);
        assertThat(plan.verwalteteRollen())
                .containsExactlyInAnyOrder(ROLLE_MITGLIED, ROLLE_DIRECTOR);
        assertThat(plan.nickname()).isEqualTo("Comander-Video");
        assertThat(plan.authRolleJeDiscordRolle()).containsEntry(ROLLE_DIRECTOR, "ROLE_DIRECTOR");
        // Der Plan liest nichts aus Discord - er steht in der eigenen Datenbank.
        verifyNoMoreInteractions(bot);
    }

    @Test
    @DisplayName("ohne Verknuepfung nennt der Plan kein Konto")
    void planOhneKonto() {
        // Der Abgleich muss unterscheiden koennen, ob er nichts zu tun hat oder
        // gar nicht erst losziehen kann. Ohne dieses null schickte er seine
        // Aufrufe an eine leere Kennung.
        charakter(1L, "Tom", null, "ROLE_MITGLIED");

        assertThat(pruefung.planFuer(1L).orElseThrow().discordUserId()).isNull();
    }
}
