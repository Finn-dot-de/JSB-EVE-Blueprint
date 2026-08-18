package com.eve.own.auth.backend.domain.discord.service;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Der Bot fasst nur an, was dieses Auth verwaltet.
 *
 * <p>Anlass ist ein Fehler, der ein halbes Jahr unbemerkt lief: Der Abgleich
 * schickte die Soll-Rollen als Feld {@code roles} an Discord - und das ist dort
 * ein <b>Vollersatz</b>. Mit einem einzigen hinterlegten Mapping und fuenf
 * Auth-Rollen bedeutete jeder Lauf: setze diese eine Rolle, nimm alles andere
 * weg. Farbrollen, Pingrollen, alles von Hand Vergebene.</p>
 *
 * <p>Unbemerkt blieb es nur, weil die einzige verknuepfte Person der IT-Admin
 * war, dessen Rolle ueber der Bot-Rolle steht - dort scheitert der Aufruf mit
 * 403, bevor er etwas anrichtet. Bei normalen Mitgliedern greift das nicht.</p>
 */
class DiscordBotServiceRollenTest {

    private static final String GUILD = "42";
    private static final String USER = "777";
    private static final String BASIS = "https://discord.com/api/v10/guilds/" + GUILD
            + "/members/" + USER;

    /** Rollen, fuer die es ein Mapping gibt - nur die darf der Bot anfassen. */
    private static final String VERWALTET_JA = "1000";
    private static final String VERWALTET_NEIN = "2000";

    private MockRestServiceServer server;

    private DiscordBotService dienst() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new DiscordBotService(builder, "token", GUILD, "cid", "secret");
    }

    @Test
    @DisplayName("setzt eine verwaltete Rolle einzeln und nimmt die andere einzeln weg")
    void jeRolleEinAufruf() {
        DiscordBotService bot = dienst();
        server.expect(requestTo(BASIS + "/roles/" + VERWALTET_JA))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess());
        server.expect(requestTo(BASIS + "/roles/" + VERWALTET_NEIN))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess());

        bot.syncManagedRoles(USER, List.of(VERWALTET_JA, VERWALTET_NEIN),
                List.of(VERWALTET_JA), null);

        // Die eigentliche Zusicherung: genau diese zwei Aufrufe und kein
        // weiterer. Ein PATCH auf das Mitglied mit einem "roles"-Feld waere
        // hier ein unerwarteter dritter Aufruf und liesse den Test scheitern.
        server.verify();
    }

    @Test
    @DisplayName("fasst beim Trennen nur die verwalteten Rollen an")
    void trennenNimmtNichtAlles() {
        // Ausloesbar von jedem Angemeldeten fuer sich selbst. Frueher ging hier
        // ein leeres roles-Feld raus und das Mitglied verlor JEDE Rolle.
        DiscordBotService bot = dienst();
        server.expect(requestTo(BASIS + "/roles/" + VERWALTET_JA))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess());

        bot.syncManagedRoles(USER, List.of(VERWALTET_JA), List.of(), null);

        server.verify();
    }

    @Test
    @DisplayName("ruft ohne verwaltete Rollen gar nichts auf")
    void ohneMappingKeinAufruf() {
        // Solange kein Mapping gepflegt ist, hat das Auth in Discord nichts zu
        // suchen. Der Gegenfall - ohne ihn wuerde ein Code, der pauschal
        // aufraeumt, die beiden Tests darueber trotzdem bestehen.
        DiscordBotService bot = dienst();
        server.expect(ExpectedCount.never(), requestTo(BASIS));

        bot.syncManagedRoles(USER, List.of(), List.of(), null);

        server.verify();
    }
}
