package com.eve.own.auth.backend.domain.character.service;

import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.auth.security.EveTokenScopes;
import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.esi.EsiErrorText;
import com.eve.own.auth.backend.esi.EsiHttpStatus;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

/**
 * Besorgt ein Token, mit dem ein Corp-Endpunkt mit Director-Zwang durchkommt.
 *
 * <p>Welcher unserer Charaktere die Ingame-Rolle Director wirklich hat, weiss
 * die Datenbank nicht: {@code ROLE_DIRECTOR} entsteht hier aus einem Corp-
 * <em>Titel</em>, nicht aus der echten Rolle. Deshalb laesst sich der richtige
 * Token-Geber nicht ausrechnen - er muss gefunden werden, indem die
 * aussichtsreichsten Kandidaten der Reihe nach durchprobiert werden.</p>
 *
 * <p>Es gab diese Schleife im Projekt bereits, aber nur im Corp-Hangar-Abgleich.
 * Die Titelabfrage griff daneben mit {@code findFirst()} genau einen Kandidaten
 * heraus - ohne Rangfolge, ohne Pruefung auf ein vorhandenes Token, ohne
 * {@code ORDER BY} und ohne zweiten Versuch. Traf dieser eine nicht, war die
 * Antwort "es ist kein Director angemeldet", obwohl daneben zwei standen. Weil
 * die Datenbank keine Reihenfolge zusichert, ging es dazu mal gut und mal nicht -
 * der schlimmste aller Fehlerzustaende. Diese Klasse gibt es, damit es die
 * Schleife genau einmal gibt.</p>
 */
@Slf4j
@Component
public class DirectorTokenProvider {

    /** Die echte Ingame-Rolle, wie CCP sie schreibt: einteilig, ohne Unterstrich. */
    public static final String INGAME_ROLE_DIRECTOR = "Director";

    private final AuthService authService;
    private final CharacterRepository characterRepo;
    private final EsiService esiService;

    public DirectorTokenProvider(AuthService authService,
                                 CharacterRepository characterRepo,
                                 EsiService esiService) {
        this.authService = authService;
        this.characterRepo = characterRepo;
        this.esiService = esiService;
    }

    /** Der Aufruf, der mit dem Token eines Kandidaten versucht werden soll. */
    @FunctionalInterface
    public interface DirectorCall<T> {
        T call(String token);
    }

    /** Woran ein einzelner Kandidat gescheitert ist. */
    public enum FailureReason {
        /** {@code getValidAccessToken} lieferte kein Token zurueck. */
        NO_TOKEN,
        /** Die Erneuerung des Tokens schlug fehl - der Charakter muss sich neu anmelden. */
        TOKEN_REFRESH_FAILED,
        /** ESI antwortete mit 403. Nur hier ist "Rechte fehlen" ueberhaupt moeglich. */
        FORBIDDEN,
        /** Ein anderer HTTP-Fehler. Sagt nichts ueber Rechte aus. */
        HTTP_ERROR,
        /** Alles Uebrige. Sagt erst recht nichts ueber Rechte aus. */
        UNEXPECTED
    }

    /**
     * Ein gescheiterter Versuch, festgehalten statt verschwiegen.
     *
     * @param scopeInToken ob der verlangte Scope laut Token-Nutzlast vorhanden war;
     *     {@code null} heisst unbekannt und darf nicht als "fehlt" gelesen werden
     * @param ccpText der Klartext aus CCPs Antwort - die einzige Auskunft, die
     *     "Scope fehlt" von "Ingame-Rolle fehlt" unterscheidet
     * @param cause die urspruengliche Ausnahme, damit sie als {@code cause}
     *     weiterwandern kann statt zu verschwinden
     */
    public record CandidateFailure(Character character, FailureReason reason,
                                   Boolean scopeInToken, String ccpText, Throwable cause) {

        public Long characterId() {
            return character.getId();
        }

        public String characterName() {
            return character.getName();
        }
    }

    /**
     * Das Ergebnis eines Durchlaufs.
     *
     * @param provider der Charakter, dessen Token getragen hat - {@code null},
     *     wenn keiner durchkam
     */
    public record DirectorAttempt<T>(T value, Character provider, List<CandidateFailure> failures) {

        public boolean succeeded() {
            return provider != null;
        }

        /**
         * Ob es gar keinen Kandidaten gab.
         *
         * <p>Unterscheidet sich fachlich hart von "alle abgelehnt": im einen Fall
         * ist niemand angemeldet, im anderen fehlen Rechte. Wer beides gleich
         * meldet, schickt den Nutzer in die falsche Richtung.</p>
         */
        public boolean noCandidateTried() {
            return provider == null && failures.isEmpty();
        }

        /** Die erste festgehaltene Ursache, fuer die Weitergabe als {@code cause}. */
        public Throwable firstCause() {
            return failures.stream()
                    .map(CandidateFailure::cause)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * Probiert die Kandidaten der Corporation durch, bis einer traegt.
     *
     * <p>Ein 420 (Fehler-Budget aufgebraucht) wird durchgereicht statt geschluckt:
     * jeder weitere Versuch verlaengerte nur das Zeitfenster.</p>
     *
     * @param requiredScope der Scope, den der Endpunkt verlangt - nur zur
     *     Fehlerdeutung, siehe {@link EveTokenScopes}. {@code null} schaltet sie ab.
     */
    public <T> DirectorAttempt<T> attempt(Long corporationId, String requiredScope, DirectorCall<T> call) {
        List<CandidateFailure> failures = new ArrayList<>();

        for (Character candidate : candidates(corporationId)) {
            // Die Token-Beschaffung liegt bewusst in einem EIGENEN try-Block.
            // Lagen beide zusammen, bekam ein toter Refresh-Token denselben Text
            // wie eine Absage von ESI - zwei grundverschiedene Ursachen, eine
            // Meldung, und der Nutzer suchte an der falschen Stelle.
            String token;
            try {
                token = authService.getValidAccessToken(candidate);
            } catch (RuntimeException e) {
                log.warn("Token von {} liess sich nicht erneuern: {}", candidate.getName(), e.getMessage());
                failures.add(new CandidateFailure(
                        candidate, FailureReason.TOKEN_REFRESH_FAILED, null, null, e));
                continue;
            }
            if (token == null) {
                failures.add(new CandidateFailure(candidate, FailureReason.NO_TOKEN, null, null, null));
                continue;
            }

            // Der Blick in die Nutzlast kostet nichts und passiert hier, solange
            // das Token noch zur Hand ist. Er entscheidet nichts - er wird nur
            // mitgeschrieben, falls der Aufruf gleich abgelehnt wird.
            Boolean scopeInToken =
                    requiredScope == null ? null : EveTokenScopes.carries(token, requiredScope);

            try {
                return new DirectorAttempt<>(call.call(token), candidate, List.copyOf(failures));

            } catch (RestClientResponseException e) {
                if (EsiHttpStatus.isErrorLimited(e)) {
                    throw e;
                }
                String ccpText = EsiErrorText.of(e);
                FailureReason reason = EsiHttpStatus.isForbidden(e)
                        ? FailureReason.FORBIDDEN : FailureReason.HTTP_ERROR;
                // CCPs Wortlaut gehoert ins Protokoll, immer. Dass er frueher
                // verworfen wurde, ist der Grund, warum die Ursache monatelang
                // unklar blieb.
                log.warn("ESI lehnt {} fuer Corp {} mit {} ab. Scope im Token: {}. CCP: {}",
                        candidate.getName(), corporationId, e.getStatusCode().value(),
                        scopeInToken == null ? "unbekannt" : scopeInToken, ccpText);
                failures.add(new CandidateFailure(candidate, reason, scopeInToken, ccpText, e));

            } catch (Exception e) {
                log.warn("Aufruf ueber {} fuer Corp {} fehlgeschlagen: {}",
                        candidate.getName(), corporationId, e.getMessage());
                failures.add(new CandidateFailure(
                        candidate, FailureReason.UNEXPECTED, scopeInToken, null, e));
            }
        }

        return new DirectorAttempt<>(null, null, List.copyOf(failures));
    }

    /**
     * Fragt ESI nach der <em>echten</em> Ingame-Rolle der gescheiterten Kandidaten.
     *
     * <p><b>Warum erst hier und nicht vorher:</b> ein Vorab-Test wuerde bei jedem
     * Seitenaufruf einen ESI-Aufruf je Kandidat kosten - und er koennte den
     * eigentlichen Aufruf nicht einmal ersetzen, weil der Corp-Endpunkt Rolle
     * <em>und</em> Scope verlangt. Der ETag-Cache des Executors hilft dagegen
     * nicht: er spart bei 304 den Rumpf, nicht den Rundlauf und nicht das
     * Fehler-Budget. Auf dem gluecklichen Pfad - ein Kandidat traegt - kostet
     * diese Bestaetigung deshalb null Aufrufe. Bezahlt wird sie nur im bereits
     * kaputten Fall, und genau der ist der, den jemand erklaert haben will.</p>
     *
     * <p>Jeder Kandidat wird mit <em>seinem eigenen</em> Token gefragt: ESI
     * beantwortet {@code /characters/{id}/roles/} nur fuer den Charakter, dem das
     * Token gehoert.</p>
     *
     * @return je Charakter-ID: {@code TRUE} = Director bestaetigt, {@code FALSE} =
     *     ESI kennt die Rolle fuer ihn nicht, {@code null} = nicht feststellbar
     */
    public Map<Long, Boolean> confirmDirectorRole(List<CandidateFailure> failures) {
        Map<Long, Boolean> confirmed = new LinkedHashMap<>();
        for (CandidateFailure failure : failures) {
            confirmed.put(failure.characterId(), hasIngameDirectorRole(failure.character()));
        }
        return confirmed;
    }

    private Boolean hasIngameDirectorRole(Character character) {
        try {
            String token = authService.getValidAccessToken(character);
            if (token == null) {
                return null;
            }
            EsiResponse<EsiService.EsiCharacterRolesResponse> response =
                    esiService.getCharacterRoles(character.getId(), token);
            if (response == null || response.data() == null) {
                return null;
            }
            return response.data().hasCorporationRole(INGAME_ROLE_DIRECTOR);

        } catch (RestClientResponseException e) {
            if (EsiHttpStatus.isErrorLimited(e)) {
                throw e;
            }
            log.warn("Ingame-Rollen von {} nicht abrufbar ({}). CCP: {}",
                    character.getName(), e.getStatusCode().value(), EsiErrorText.of(e));
            return null;
        } catch (Exception e) {
            log.warn("Ingame-Rollen von {} nicht abrufbar: {}", character.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * Die Charaktere der Corporation, die als Token-Geber in Frage kommen -
     * der aussichtsreichste zuerst.
     *
     * <p>Bei gleichem Rang entscheidet die Charakter-ID. Das ist kein Schoenheits-
     * wunsch: ohne zweites Kriterium gibt die Reihenfolge der Datenbank den Ton
     * an, und die sichert ohne {@code ORDER BY} nichts zu. Dann traegt derselbe
     * Aufruf heute und faellt morgen um - und niemand findet den Unterschied.</p>
     *
     * <p>Wer keine Fuehrungsrolle traegt, bleibt aussen vor: ESI antwortet ihm
     * ohnehin mit 403 und wir verbrennen nur Fehler-Budget. Wer kein Token
     * hinterlegt hat oder dessen Token dauerhaft ungueltig vermerkt ist,
     * ebenfalls - ihn zu fragen kostet einen SSO-Rundlauf und endet sicher im
     * selben Fehlschlag, den der Vermerk bereits festhaelt.</p>
     */
    private List<Character> candidates(Long corporationId) {
        return characterRepo.findAllWithCorporation().stream()
                .filter(character -> character.getCorporation() != null
                        && corporationId.equals(character.getCorporation().getId()))
                .filter(character -> character.getRefreshToken() != null)
                .filter(character -> character.getTokenInvalidSince() == null)
                .filter(character -> directorRank(character) > 0)
                .sorted(Comparator.<Character>comparingInt(DirectorTokenProvider::directorRank).reversed()
                        .thenComparing(Character::getId))
                .toList();
    }

    /** Wie aussichtsreich ein Charakter als Director-Token-Geber ist; 0 = chancenlos. */
    private static int directorRank(Character character) {
        if (character.hasRole(SystemRoles.CEO)) {
            return 3;
        }
        if (character.hasRole(SystemRoles.DIRECTOR)) {
            return 2;
        }
        if (character.hasRole(SystemRoles.IT_ADMIN)) {
            return 1;
        }
        return 0;
    }
}
