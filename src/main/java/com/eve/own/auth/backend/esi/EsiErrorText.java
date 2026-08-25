package com.eve.own.auth.backend.esi;

import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Holt den lesbaren Satz aus einer Fehlerantwort von ESI.
 *
 * <p>Diese Klasse gibt es, weil genau dieser Satz jahrelang weggeworfen wurde.
 * Der Code fing eine 403 und schrieb einen selbst erfundenen Text daneben - und
 * damit war die einzige Auskunft weg, die "dir fehlt der Scope" von "dir fehlt
 * die Ingame-Rolle" unterscheidet. Wer den Fehler suchte, stand vor einer
 * Meldung, die die Anwendung sich ausgedacht hatte.</p>
 *
 * <p>Der Text wird bewusst <em>nicht</em> ausgewertet, sondern nur
 * durchgereicht: CCP hat den Wortlaut schon einmal geaendert
 * ("Character does not have required role(s)" wurde zu "The given character
 * doesn't have the required role(s)"). Ein Mustervergleich darauf waere eine
 * Zeitbombe. Die belastbare Unterscheidung liefern der scp-Claim und die
 * Rollenabfrage, nicht das Woerterraten.</p>
 */
public final class EsiErrorText {

    /** Laut ESI-Definition das einzige Pflichtfeld im Fehlerkoerper. */
    private static final String ERROR_FIELD = "error";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EsiErrorText() {
        throw new AssertionError("Helferklasse, nicht instanziierbar.");
    }

    /**
     * Der Klartext, den CCP mitgeschickt hat.
     *
     * @return CCPs Satz, ersatzweise der rohe Koerper - oder {@code null}, wenn
     *     die Antwort ueberhaupt keinen Koerper trug
     */
    public static String of(RestClientResponseException exception) {
        if (exception == null) {
            return null;
        }
        String raw = exception.getResponseBodyAsString();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode error = MAPPER.readTree(raw).path(ERROR_FIELD);
            if (error.isString() && !error.asString().isBlank()) {
                return error.asString();
            }
        } catch (Exception ignored) {
            // Kein JSON: dann traegt der Rohtext die Auskunft. Ein Fehler beim
            // Deuten eines Fehlers darf den Fehler nicht ersetzen.
        }
        return raw.strip();
    }
}
