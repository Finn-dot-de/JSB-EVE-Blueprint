package com.eve.own.auth.backend.domain.auth.service;

import com.eve.own.auth.backend.domain.auth.security.AesEncryptionService;
import com.eve.own.auth.backend.domain.auth.security.EveSsoClient;
import com.eve.own.auth.backend.domain.character.entity.Alliance;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.Corporation;
import com.eve.own.auth.backend.domain.character.repository.AllianceRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.character.repository.CorporationRepository;
import com.eve.own.auth.backend.esi.EsiService;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Der Anmeldevorgang und die Pflege der EVE-Token.
 *
 * <p>Zwei Aufgaben, die zusammengehoeren: beim Login entstehen die Token, und
 * jeder spaetere ESI-Aufruf braucht ein gueltiges. Die Rollenvergabe liegt
 * bewusst nicht hier, sondern im {@link CharacterRoleService} - sie wird auch
 * vom Hintergrund-Sync gebraucht.</p>
 */
@Service
@Slf4j
public class AuthService {

    /**
     * Sicherheitsabstand vor dem Ablauf: ein Token, das in weniger als einer
     * Minute verfaellt, wird vorsorglich erneuert. Sonst koennte es zwischen
     * Pruefung und Aufruf ungueltig werden.
     */
    private static final Duration EXPIRY_HEADROOM = Duration.ofSeconds(60);

    private final EveSsoClient ssoClient;
    private final EsiService esiService;
    private final CharacterRepository characterRepo;
    private final CorporationRepository corpRepo;
    private final AllianceRepository allianceRepo;
    private final AesEncryptionService encryptionService;
    private final CharacterRoleService roleService;
    private final TokenHealthService tokenHealth;

    public AuthService(EveSsoClient ssoClient,
                       EsiService esiService,
                       CharacterRepository characterRepo,
                       CorporationRepository corpRepo,
                       AllianceRepository allianceRepo,
                       AesEncryptionService encryptionService,
                       CharacterRoleService roleService,
                       TokenHealthService tokenHealth) {
        this.ssoClient = ssoClient;
        this.esiService = esiService;
        this.characterRepo = characterRepo;
        this.corpRepo = corpRepo;
        this.allianceRepo = allianceRepo;
        this.encryptionService = encryptionService;
        this.roleService = roleService;
        this.tokenHealth = tokenHealth;
    }

    /**
     * Der vollstaendige Login-Ablauf nach der Rueckleitung von CCP.
     *
     * @param loggedInMainId Main-Account des bereits angemeldeten Nutzers, wenn
     *     dieser gerade einen weiteren Charakter verknuepft; sonst {@code null}
     */
    public Character processEveLogin(String code, Long loggedInMainId) {
        EveSsoClient.TokenResponse tokens = ssoClient.exchangeCode(code);
        EveSsoClient.EveIdentity identity = ssoClient.readIdentity(tokens.access_token());

        Corporation corporation = syncCorporationAndAlliance(identity.characterId());
        Character character = saveOrUpdate(identity, corporation, tokens, loggedInMainId);

        return roleService.applyRoles(character, tokens.access_token());
    }

    /**
     * Ein gueltiges Access-Token fuer ESI-Aufrufe.
     *
     * <p>Erneuert bei Bedarf ueber den Refresh-Token und schreibt das Ergebnis
     * zurueck, damit parallele Aufrufer nicht erneut erneuern muessen.</p>
     */
    /**
     * Bewusst eine <b>eigene</b> Transaktion.
     *
     * <p>Ohne {@code REQUIRES_NEW} lief diese Methode in der Transaktion des
     * Aufrufers mit - und riss sie mit sich, sobald ein Refresh-Token tot war.
     * Der Ablauf war heimtueckisch: {@code ssoClient.refresh} wirft bei
     * {@code invalid_grant}, Spring markiert die gemeinsame Transaktion als
     * rollback-only, der Aufrufer faengt die Ausnahme und macht weiter - und
     * erst beim Commit fliegt eine {@code UnexpectedRollbackException}. Alles
     * dazwischen ist verloren, obwohl es funktioniert hat.</p>
     *
     * <p>Gemessen in Produktion: drei Charaktere mit abgelaufenem Refresh-Token
     * legten damit den kompletten Industriejob-Abgleich fuer 225 Charaktere
     * lahm, bei jedem Lauf, alle zehn Minuten.</p>
     *
     * <p>Der zweite Grund ist ebenso wichtig: ein <em>erfolgreich</em>
     * erneuerter Token wird jetzt sofort festgeschrieben. EVE dreht bei jeder
     * Erneuerung auch den Refresh-Token weiter; ginge der Schreibvorgang durch
     * ein spaeteres Rollback des Aufrufers verloren, waere die Kette gerissen
     * und der Charakter beim naechsten Mal ausgesperrt.</p>
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public String getValidAccessToken(Character character) {
        if (character.getTokenExpiry() != null
                && character.getTokenExpiry().isAfter(Instant.now().plus(EXPIRY_HEADROOM))) {
            return encryptionService.decrypt(character.getAccessToken());
        }

        EveSsoClient.TokenResponse tokens;
        try {
            tokens = ssoClient.refresh(encryptionService.decrypt(character.getRefreshToken()));
        } catch (RuntimeException e) {
            // Der Vermerk wird in einer eigenen Transaktion geschrieben - diese
            // hier rollt gleich zurueck, und mit ihr ginge er sonst unter.
            tokenHealth.markInvalid(character.getId(), e.getMessage());
            throw e;
        }
        if (tokens == null || tokens.access_token() == null) {
            tokenHealth.markInvalid(character.getId(), "EVE lieferte keinen Token zurück.");
            throw new IllegalStateException(
                    "EVE-Token fuer Charakter " + character.getId() + " liess sich nicht erneuern.");
        }

        storeTokens(character, tokens);
        characterRepo.save(character);
        // Hat es geklappt, ist ein alter Vermerk hinfaellig.
        tokenHealth.markValid(character.getId());
        return tokens.access_token();
    }

    /**
     * Legt Corporation und - sofern vorhanden - Allianz des Charakters an bzw. aktualisiert sie.
     *
     * <p>Beim Login sind diese Daten Pflicht: ohne sie laesst sich kein Account
     * anlegen, weil die Corp-Zugehoerigkeit ueber saemtliche Rechte entscheidet.</p>
     */
    private Corporation syncCorporationAndAlliance(Long characterId) {
        var esiCharacter = esiService.getCharacter(characterId).data();
        if (esiCharacter == null) {
            throw new IllegalStateException("ESI lieferte keine Charakterdaten fuer " + characterId);
        }

        Long corporationId = esiCharacter.corporation_id();
        var esiCorporation = esiService.getCorporation(corporationId).data();
        if (esiCorporation == null) {
            throw new IllegalStateException("ESI lieferte keine Corp-Daten fuer " + corporationId);
        }

        Corporation corporation = new Corporation();
        corporation.setId(corporationId);
        corporation.setName(esiCorporation.name());
        corporation.setTicker(esiCorporation.ticker());
        corporation.setAlliance(syncAlliance(esiCorporation.alliance_id()));
        return corpRepo.save(corporation);
    }

    private Alliance syncAlliance(Long allianceId) {
        if (allianceId == null) {
            return null;
        }
        var esiAlliance = esiService.getAlliance(allianceId).data();
        if (esiAlliance == null) {
            log.warn("Allianz {} nicht abrufbar, Corporation wird ohne Allianz gespeichert.", allianceId);
            return null;
        }
        Alliance alliance = new Alliance();
        alliance.setId(allianceId);
        alliance.setName(esiAlliance.name());
        alliance.setTicker(esiAlliance.ticker());
        return allianceRepo.save(alliance);
    }

    private Character saveOrUpdate(EveSsoClient.EveIdentity identity,
                                   Corporation corporation,
                                   EveSsoClient.TokenResponse tokens,
                                   Long loggedInMainId) {
        Character character = characterRepo.findById(identity.characterId()).orElseGet(Character::new);
        character.setId(identity.characterId());
        character.setName(identity.characterName());
        character.setCorporation(corporation);
        storeTokens(character, tokens);

        if (loggedInMainId != null) {
            // Der Charakter wird einem bestehenden Account als Alt zugeordnet.
            character.setMainCharacterId(loggedInMainId);
        } else if (character.getMainCharacterId() == null) {
            // Erster Login ohne bestehende Sitzung: der Charakter ist sein eigener Main.
            character.setMainCharacterId(identity.characterId());
        }

        return characterRepo.save(character);
    }

    /** Token verschluesselt ablegen - sie liegen dauerhaft in der Datenbank. */
    /**
     * Schreibt frische Tokens - und nimmt dabei die Abmelde-Marke zurueck.
     *
     * <p>Hier und nicht erst nach einem erfolgreichen Refresh: dieselbe Methode
     * bedient auch die Anmeldung, und genau die ist der Moment, in dem der
     * Spieler das Problem geloest hat. Wer nur den Refresh-Pfad entwarnt,
     * laesst die Marke nach einer Neuanmeldung stehen.</p>
     */
    private void storeTokens(Character character, EveSsoClient.TokenResponse tokens) {
        character.setTokenInvalidSince(null);
        character.setTokenInvalidReason(null);
        character.setTokenInvalidNotifiedAt(null);
        character.setAccessToken(encryptionService.encrypt(tokens.access_token()));
        if (tokens.refresh_token() != null) {
            // Beim Erneuern liefert CCP nicht immer einen neuen Refresh-Token.
            character.setRefreshToken(encryptionService.encrypt(tokens.refresh_token()));
        }
        character.setTokenExpiry(Instant.now().plusSeconds(tokens.expires_in()));
    }
}
