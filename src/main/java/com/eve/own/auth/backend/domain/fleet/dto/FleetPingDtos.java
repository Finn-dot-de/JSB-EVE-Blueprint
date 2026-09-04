package com.eve.own.auth.backend.domain.fleet.dto;

import com.eve.own.auth.backend.domain.fleet.PingErwaehnung;
import com.eve.own.auth.backend.domain.fleet.entity.FleetPing;
import com.eve.own.auth.backend.domain.fleet.service.FleetPingService;
import java.time.Instant;

/**
 * Was ueber die Schnittstelle geht.
 *
 * <h2>Zeiten</h2>
 * <p>{@link Instant} in beide Richtungen, und damit ISO-8601 <b>mit</b> Versatz:
 * {@code 2026-09-03T19:00:00Z}. Eine Angabe ohne Versatz weist Jackson ab, und
 * das ist der Zweck - eine Zeit ohne Zone waere entweder die des Servers, die
 * des Browsers oder EVE-Zeit, und welche davon gemeint war, kann hinterher
 * niemand mehr feststellen. Bei einer Ankuendigung, zu der Leute puenktlich
 * erscheinen sollen, ist eine um Stunden verschobene Zeit schlimmer als eine
 * abgewiesene Eingabe.</p>
 */
public class FleetPingDtos {

    /**
     * Ein abzusetzender oder zu aendernder Ping.
     *
     * @param erwaehnung als Zeichenkette und nicht als Aufzaehlung, damit ein
     *     unbekannter Wert nicht die ganze Anfrage mit einem Jackson-Fehler
     *     abweist, sondern in {@link PingErwaehnung#of} still zu "kein Ton"
     *     wird. Die Richtung stimmt: ein Tippfehler soll leiser machen, nie
     *     lauter.
     * @param rolleId welche Rolle bei der Erwaehnung {@code ROLLE} gerufen wird.
     *     Anders als {@code erwaehnung} faellt ein unbekannter Wert hier
     *     <b>nicht</b> still auf eine Vorgabe zurueck, sondern wird abgewiesen:
     *     Bei der Lautstaerke ist "leiser als gemeint" die sichere Richtung, bei
     *     der Zielgruppe gibt es keine sichere Richtung - eine geratene Rolle
     *     waere entweder die falsche oder gar keine. Geprueft wird im
     *     {@code FleetPingService} gegen die Zuordnungen aus
     *     {@code discord_role_mappings}.
     */
    public record PingRequest(
            String fleetType,
            String doctrine,
            String formupLocation,
            Instant formupTime,
            String comms,
            Boolean srpCovered,
            String notes,
            String erwaehnung,
            String rolleId) {}

    /** Der Grund einer Absage - als Koerper, damit er auch leer sein darf. */
    public record CancelRequest(String grund) {}

    /**
     * Ein Ping, wie ihn die Liste zeigt.
     *
     * <p>Die Discord-Nachrichten-ID steht mit drin: Wer in der Liste einen Ping
     * sucht, den er in Discord gesehen hat, findet ihn nur darueber wieder.</p>
     */
    public record PingResponse(
            Long id,
            Long fcCharacterId,
            String fcCharacterName,
            String fleetType,
            String doctrine,
            String formupLocation,
            Instant formupTime,
            String comms,
            Boolean srpCovered,
            String notes,
            String erwaehnung,
            /*
             * Welche Rolle es getroffen hat. Ohne diese Angabe sagt die
             * Rechenschaftsliste bei einem Rollen-Ping nur noch "ROLLE" - und
             * seit die Rolle waehlbar ist, ist das keine Auskunft mehr.
             */
            String erwaehnungRolleId,
            String zustand,
            String discordMessageId,
            Instant createdAt,
            Instant updatedAt,
            Instant cancelledAt,
            String cancelReason) {

        public static PingResponse von(FleetPing ping) {
            return new PingResponse(
                    ping.getId(), ping.getFcCharacterId(), ping.getFcCharacterName(),
                    ping.getFleetType(), ping.getDoctrine(), ping.getFormupLocation(),
                    ping.getFormupTime(), ping.getComms(), ping.getSrpCovered(), ping.getNotes(),
                    ping.getErwaehnung() == null ? null : ping.getErwaehnung().name(),
                    ping.getErwaehnungRolleId(),
                    ping.getZustand() == null ? null : ping.getZustand().name(),
                    ping.getDiscordMessageId(), ping.getCreatedAt(), ping.getUpdatedAt(),
                    ping.getCancelledAt(), ping.getCancelReason());
        }
    }

    /**
     * Ob die Funktion ueberhaupt benutzbar ist.
     *
     * <p>Damit das Frontend den Knopf gar nicht erst anbietet, statt ihn
     * anzubieten und jedes Mal einen Fehler zu zeigen. Das ist der sichtbare
     * Teil der sauberen Abschaltung; der unsichtbare ist die Warnung beim
     * Start.</p>
     *
     * @param rolleKonfiguriert ob die Auswahl "eine Rolle" etwas bewirkt - also
     *     ob ueberhaupt eine Discord-Rolle im Auth verknuepft ist. Ohne diese
     *     Auskunft boete das Frontend eine Erwaehnung an, hinter der ein leeres
     *     Auswahlfeld steht.
     */
    public record PingStatusResponse(boolean verfuegbar, boolean rolleKonfiguriert, String hinweis) {}

    /**
     * Eine Rolle, die ein FC anpingen kann.
     *
     * <p>Der Name steht mit drin und ist der eigentliche Zweck dieser Antwort:
     * Eine nackte {@code 1539289011737329796} kann niemand zuordnen. Ist Discord
     * nicht erreichbar, steht dort der Auth-Rollenname - eine schlechtere
     * Beschriftung, aber eine Auswahl, die weiter benutzbar ist.</p>
     *
     * <p>{@code authRole} steht zusaetzlich daneben, weil sie beantwortet, warum
     * diese Rolle ueberhaupt waehlbar ist: weil jemand sie unter
     * {@code /admin/discord} verknuepft hat. Wer eine Rolle vermisst, weiss
     * damit, wo er sie hinzufuegt.</p>
     */
    public record PingRolleResponse(
            String discordRoleId, String authRole, String name, boolean vorbelegt) {

        public static PingRolleResponse von(FleetPingService.PingRolle rolle) {
            return new PingRolleResponse(
                    rolle.discordRoleId(), rolle.authRole(), rolle.name(), rolle.vorbelegt());
        }
    }
}
