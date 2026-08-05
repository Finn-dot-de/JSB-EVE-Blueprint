package com.eve.own.auth.backend.domain.groups.service;

import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.auth.entity.TitleRoleMapping;
import com.eve.own.auth.backend.domain.auth.repository.TitleRoleMappingRepository;
import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.esi.EsiAccessDeniedException;
import com.eve.own.auth.backend.esi.EsiService;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

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

    private final EsiService esiService;
    private final AuthService authService;
    private final CharacterRepository characterRepo;
    private final TitleRoleMappingRepository mappingRepo;

    public TitleMappingService(EsiService esiService,
                               AuthService authService,
                               CharacterRepository characterRepo,
                               TitleRoleMappingRepository mappingRepo) {
        this.esiService = esiService;
        this.authService = authService;
        this.characterRepo = characterRepo;
        this.mappingRepo = mappingRepo;
    }

    /** Ein Corp-Titel samt der Rolle, die er hier vergibt. */
    public record CorpTitleDto(Long titleId, String name, String mappedRole) {}

    /**
     * Alle Titel der Corporation des Anfragenden.
     *
     * @throws EsiAccessDeniedException wenn kein Charakter mit Ingame-Director-Rechten registriert ist
     */
    @Transactional(readOnly = true)
    public List<CorpTitleDto> corporationTitles(Long requestingCharacterId) {
        Character requester = requireCharacter(requestingCharacterId);
        Long corporationId = requester.getCorporation().getId();

        EsiService.EsiCorpTitleResponse[] esiTitles = fetchTitles(corporationId, requester);
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
     * <p>Der Endpunkt verlangt ein Token mit Ingame-Director-Rechten. Der
     * anfragende Charakter dient nur als Rueckfallebene - er hat sie meist nicht.</p>
     */
    private EsiService.EsiCorpTitleResponse[] fetchTitles(Long corporationId, Character requester) {
        Character tokenProvider = characterRepo.findByCorporationId(corporationId).stream()
                .filter(character -> character.hasRole(SystemRoles.DIRECTOR)
                        || character.hasRole(SystemRoles.CEO))
                .findFirst()
                .orElse(requester);

        try {
            String token = authService.getValidAccessToken(tokenProvider);
            return esiService.getCorporationTitles(corporationId, token).data();
        } catch (HttpClientErrorException.Forbidden e) {
            throw new EsiAccessDeniedException(
                    "Die EVE-API verweigert den Zugriff. Es muss mindestens ein Charakter mit "
                            + "Ingame-Director-Rechten registriert sein, um die Titel auszulesen.");
        }
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
