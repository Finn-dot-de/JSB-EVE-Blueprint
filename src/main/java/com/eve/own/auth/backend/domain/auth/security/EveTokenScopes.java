package com.eve.own.auth.backend.domain.auth.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Liest die Scope-Liste aus der Nutzlast eines EVE-Access-Tokens.
 *
 * <p><b>Ausschliesslich zur Fehlerdeutung.</b> Die Signatur des Tokens wird
 * hier nicht geprueft - der Claim ist damit eine unbeglaubigte Behauptung. Ein
 * unverifizierter Claim darf niemals eine Zugriffsentscheidung tragen: weder
 * darf er einen Aufruf freigeben noch ihn verhindern. Er darf nur einen Satz an
 * den Nutzer begruenden.</p>
 *
 * <p>Diese Regel ist hier nicht bloss aufgeschrieben, sie ist gebaut: die
 * Methoden heissen {@code carries}/{@code of} statt {@code darf}/{@code pruefe},
 * sie liefern kein Ja/Nein sondern ein Ja/Nein/<em>unbekannt</em>, und der
 * einzige Aufrufer fragt sie erst, <em>nachdem</em> ESI mit 403 geantwortet hat.
 * Solange niemand vor dem Aufruf auf den Claim verzweigt, <em>kann</em> er
 * nichts gewaehren und nichts entziehen.</p>
 *
 * <p>Wozu das gut ist: ein Charakter, der sich anmeldete, bevor ein Scope in die
 * Liste der Anwendung aufgenommen wurde, traegt ihn bis heute nicht - sein Token
 * erneuert sich sauber und ist trotzdem zu schmal. ESI antwortet darauf mit 403,
 * genau wie bei einer fehlenden Ingame-Rolle. Ohne diesen Blick ins Token sind
 * die beiden Faelle von aussen nicht zu unterscheiden, und die Anwendung raet.</p>
 */
public final class EveTokenScopes {

    /** Der Claim, in dem CCP die gewaehrten Scopes ablegt. */
    private static final String SCOPE_CLAIM = "scp";

    /** Ein JWT besteht aus Kopf, Nutzlast und Signatur. */
    private static final int JWT_PART_COUNT = 3;
    private static final int PAYLOAD_PART = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EveTokenScopes() {
        throw new AssertionError("Helferklasse, nicht instanziierbar.");
    }

    /**
     * Die im Token vermerkten Scopes.
     *
     * <p>Ein leeres Ergebnis ist eine Aussage ("das Token traegt keinen Scope"),
     * {@code null} ist keine ("die Nutzlast war nicht lesbar"). Der Unterschied
     * entscheidet darueber, ob die Meldung an den Nutzer eine Ursache nennen
     * darf oder ehrlich sagen muss, dass sie es nicht weiss.</p>
     *
     * @return die Scopes oder {@code null}, wenn sich das Token nicht lesen liess
     */
    public static Set<String> of(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return null;
        }
        String[] parts = accessToken.split("\\.");
        if (parts.length < JWT_PART_COUNT) {
            return null;
        }
        try {
            String payloadJson = new String(
                    Base64.getUrlDecoder().decode(parts[PAYLOAD_PART]), StandardCharsets.UTF_8);
            JsonNode scp = MAPPER.readTree(payloadJson).path(SCOPE_CLAIM);

            Set<String> scopes = new LinkedHashSet<>();
            if (scp.isArray()) {
                scp.values().forEach(entry -> scopes.add(entry.asString()));
            } else if (scp.isString()) {
                // Bei genau einem gewaehrten Scope schreibt CCP einen blanken
                // String statt einer einelementigen Liste. Wer nur isArray()
                // abfragt, haelt so ein Token faelschlich fuer scope-frei.
                scopes.add(scp.asString());
            }
            return scopes;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Ob das Token einen bestimmten Scope traegt.
     *
     * @return {@code TRUE}/{@code FALSE} laut Nutzlast, {@code null} wenn
     *     unbekannt - "unbekannt" darf nicht als "fehlt" gelesen werden
     */
    public static Boolean carries(String accessToken, String scope) {
        Set<String> scopes = of(accessToken);
        return scopes == null ? null : scopes.contains(scope);
    }
}
