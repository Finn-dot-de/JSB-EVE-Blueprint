package com.eve.own.auth.backend.domain.groups.service;

import com.eve.own.auth.backend.domain.auth.entity.TitleRoleMapping;
import com.eve.own.auth.backend.domain.auth.repository.TitleRoleMappingRepository;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.character.service.DirectorTokenProvider;
import com.eve.own.auth.backend.domain.character.service.DirectorTokenProvider.CandidateFailure;
import com.eve.own.auth.backend.domain.character.service.DirectorTokenProvider.DirectorAttempt;
import com.eve.own.auth.backend.domain.character.service.DirectorTokenProvider.FailureReason;
import com.eve.own.auth.backend.esi.EsiAccessDeniedException;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verwaltet die Zuordnung von Ingame-Titeln zu Rollen dieser Anwendung.
 *
 * <p>Ueber diese Zuordnung entsteht der grosse Teil des Rechtemodells: wer in
 * EVE einen Titel traegt, bekommt hier die daran gehaengte Rolle - siehe
 * {@link com.eve.own.auth.backend.domain.auth.service.CharacterRoleService}.</p>
 */
@Service
public class TitleMappingService {

    /** Entfernt die HTML-Auszeichnung, mit der Spieler ihre Titel ingame faerben. */
    private static final String HTML_TAG_PATTERN = "<[^>]*>";

    /** Ohne diesen Scope weist ESI die Titelabfrage ab, egal welche Rolle jemand hat. */
    static final String TITLES_SCOPE = "esi-corporations.read_titles.v1";

    private final EsiService esiService;
    private final DirectorTokenProvider directorTokens;
    private final CharacterRepository characterRepo;
    private final TitleRoleMappingRepository mappingRepo;

    public TitleMappingService(EsiService esiService,
                               DirectorTokenProvider directorTokens,
                               CharacterRepository characterRepo,
                               TitleRoleMappingRepository mappingRepo) {
        this.esiService = esiService;
        this.directorTokens = directorTokens;
        this.characterRepo = characterRepo;
        this.mappingRepo = mappingRepo;
    }

    /** Ein Corp-Titel samt der Rolle, die er hier vergibt. */
    public record CorpTitleDto(Long titleId, String name, String mappedRole) {}

    /**
     * Alle Titel der Corporation des Anfragenden.
     *
     * @throws EsiAccessDeniedException wenn kein Kandidat durchkam - mit einer
     *     Begruendung, die den tatsaechlichen Grund benennt statt ihn zu raten
     */
    @Transactional(readOnly = true)
    public List<CorpTitleDto> corporationTitles(Long requestingCharacterId) {
        Character requester = requireCharacter(requestingCharacterId);
        Long corporationId = requester.getCorporation().getId();

        EsiService.EsiCorpTitleResponse[] esiTitles = fetchTitles(corporationId);
        if (esiTitles == null) {
            return List.of();
        }

        List<TitleRoleMapping> mappings = mappingRepo.findByCorporationId(corporationId);
        return Arrays.stream(esiTitles)
                .map(title -> new CorpTitleDto(
                        title.title_id(),
                        stripHtml(title.name()),
                        mappedRole(mappings, title.title_id())))
                .toList();
    }

    /**
     * Setzt oder loescht die Rolle eines Titels.
     *
     * <p>Ein leerer Rollenname bedeutet: dieser Titel vergibt nichts mehr. Die
     * Zuordnung wird dann entfernt statt auf einen leeren Wert gesetzt - sonst
     * legte der naechste Sync sie automatisch neu an.</p>
     */
    @Transactional
    public void saveMapping(Long requestingCharacterId, Long titleId, String roleName) {
        Long corporationId = requireCharacter(requestingCharacterId).getCorporation().getId();
        Optional<TitleRoleMapping> existing = mappingRepo.findByCorporationId(corporationId).stream()
                .filter(mapping -> mapping.getTitleId().equals(titleId))
                .findFirst();

        boolean clearMapping = roleName == null || roleName.isBlank();

        if (existing.isPresent()) {
            if (clearMapping) {
                mappingRepo.delete(existing.get());
            } else {
                existing.get().setRoleName(roleName);
                mappingRepo.save(existing.get());
            }
            return;
        }
        if (!clearMapping) {
            TitleRoleMapping mapping = new TitleRoleMapping();
            mapping.setCorporationId(corporationId);
            mapping.setTitleId(titleId);
            mapping.setRoleName(roleName);
            mappingRepo.save(mapping);
        }
    }

    /**
     * Holt die Titel bei ESI.
     *
     * <p>Der Endpunkt verlangt ein Token mit Ingame-Director-Rechten <em>und</em>
     * dem Scope {@link #TITLES_SCOPE}. Welcher Charakter beides mitbringt, weiss
     * niemand vorher - deshalb uebernimmt der {@link DirectorTokenProvider} das
     * Durchprobieren und sammelt dabei die Gruende des Scheiterns.</p>
     */
    private EsiService.EsiCorpTitleResponse[] fetchTitles(Long corporationId) {
        DirectorAttempt<EsiResponse<EsiService.EsiCorpTitleResponse[]>> attempt =
                directorTokens.attempt(corporationId, TITLES_SCOPE,
                        token -> esiService.getCorporationTitles(corporationId, token));

        if (attempt.succeeded()) {
            EsiResponse<EsiService.EsiCorpTitleResponse[]> response = attempt.value();
            return response == null ? null : response.data();
        }
        throw new EsiAccessDeniedException(explainFailure(attempt), attempt.firstCause());
    }

    /**
     * Baut aus den gesammelten Fehlschlaegen eine Begruendung, die stimmt.
     *
     * <p>Vorher stand hier ein einziger fester Satz: "es muss ein Charakter mit
     * Ingame-Director-Rechten registriert sein". Der war in genau einem der vier
     * moeglichen Faelle richtig und schickte in den drei anderen jemanden auf
     * eine Suche, die nichts finden konnte.</p>
     */
    private String explainFailure(DirectorAttempt<?> attempt) {
        List<CandidateFailure> failures = attempt.failures();

        if (failures.isEmpty()) {
            return "Es ist kein Charakter dieser Corporation mit Fuehrungsrolle und gueltigem "
                    + "Token hier angemeldet. Ohne einen solchen Charakter kann die EVE-API die "
                    + "Corp-Titel nicht liefern - das ist keine Aussage darueber, wer ingame "
                    + "Director ist.";
        }

        List<CandidateFailure> forbidden = failures.stream()
                .filter(failure -> failure.reason() == FailureReason.FORBIDDEN)
                .toList();

        if (forbidden.isEmpty()) {
            // Nicht ein einziger Kandidat kam bis zu einer Antwort von ESI. Das
            // als "kein Director" auszugeben waere eine Erfindung.
            return "Die Corp-Titel liessen sich nicht abrufen - und zwar nicht, weil Rechte "
                    + "fehlen: " + failures.stream().map(this::describe)
                    .collect(Collectors.joining("; ")) + ".";
        }

        List<CandidateFailure> withoutScope = forbidden.stream()
                .filter(failure -> Boolean.FALSE.equals(failure.scopeInToken()))
                .toList();
        List<CandidateFailure> withScope = forbidden.stream()
                .filter(failure -> !Boolean.FALSE.equals(failure.scopeInToken()))
                .toList();

        StringBuilder message = new StringBuilder("Die EVE-API verweigert den Zugriff auf die Corp-Titel. ");

        if (!withoutScope.isEmpty()) {
            message.append("Das Token von ").append(names(withoutScope))
                    .append(" traegt den Scope ").append(TITLES_SCOPE)
                    .append(" nicht - es stammt aus der Zeit vor der Scope-Erweiterung. Eine "
                            + "einmalige Neuanmeldung dieser Charaktere genuegt, ingame aendert "
                            + "sich nichts. ");
        }

        if (!withScope.isEmpty()) {
            appendRoleVerdict(message, withScope);
        }

        String ccpText = forbidden.stream()
                .map(CandidateFailure::ccpText)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse(null);
        if (ccpText != null) {
            message.append("EVE meldet dazu: \"").append(ccpText).append("\".");
        }
        return message.toString().strip();
    }

    /**
     * Der Teil der Meldung fuer Kandidaten, deren Token den Scope traegt.
     *
     * <p>Erst hier wird ESI nach der echten Ingame-Rolle gefragt - auf dem
     * gluecklichen Pfad passiert das nie.</p>
     */
    private void appendRoleVerdict(StringBuilder message, List<CandidateFailure> withScope) {
        Map<Long, Boolean> confirmed = directorTokens.confirmDirectorRole(withScope);

        List<CandidateFailure> withoutRole = withScope.stream()
                .filter(failure -> Boolean.FALSE.equals(confirmed.get(failure.characterId())))
                .toList();
        List<CandidateFailure> confirmedDirectors = withScope.stream()
                .filter(failure -> Boolean.TRUE.equals(confirmed.get(failure.characterId())))
                .toList();
        List<CandidateFailure> unclear = withScope.stream()
                .filter(failure -> confirmed.get(failure.characterId()) == null)
                .toList();

        if (!withoutRole.isEmpty()) {
            message.append("Fuer ").append(names(withoutRole))
                    .append(" meldet ESI keine Ingame-Rolle \"")
                    .append(DirectorTokenProvider.INGAME_ROLE_DIRECTOR)
                    .append("\" - hier fehlt es also wirklich an den Ingame-Director-Rechten. ");
        }
        if (!confirmedDirectors.isEmpty()) {
            message.append(names(confirmedDirectors))
                    .append(" ist ingame bestaetigt Director und traegt den Scope - "
                            + "die Absage hat damit einen anderen Grund. ");
        }
        if (!unclear.isEmpty()) {
            message.append("Bei ").append(names(unclear))
                    .append(" liess sich nicht bestaetigen, ob es an den Ingame-Director-Rechten "
                            + "fehlt: die Rollenabfrage bei ESI blieb ohne Antwort. ");
        }
    }

    private String names(List<CandidateFailure> failures) {
        return failures.stream()
                .map(CandidateFailure::characterName)
                .collect(Collectors.joining(", "));
    }

    /** Ein Fehlschlag in einem Halbsatz - fuer die Faelle jenseits von 403. */
    private String describe(CandidateFailure failure) {
        String reason = switch (failure.reason()) {
            case NO_TOKEN -> "kein gueltiges Token";
            case TOKEN_REFRESH_FAILED -> "Token nicht erneuerbar, Neuanmeldung noetig";
            case HTTP_ERROR -> "die EVE-API antwortete mit einem Fehler";
            case UNEXPECTED -> "unerwarteter Fehler";
            case FORBIDDEN -> "Zugriff verweigert";
        };
        return failure.characterName() + ": " + reason;
    }

    private static String mappedRole(List<TitleRoleMapping> mappings, Long titleId) {
        return mappings.stream()
                .filter(mapping -> mapping.getTitleId().equals(titleId))
                .map(TitleRoleMapping::getRoleName)
                .findFirst()
                .orElse(null);
    }

    private static String stripHtml(String titleName) {
        return titleName.replaceAll(HTML_TAG_PATTERN, "");
    }

    private Character requireCharacter(Long characterId) {
        return characterRepo.findById(characterId).orElseThrow(
                () -> new IllegalArgumentException("Charakter " + characterId + " ist unbekannt."));
    }
}
