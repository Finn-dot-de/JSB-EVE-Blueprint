package com.eve.buy.bot.backend.domain.buybot.service;

import com.eve.buy.bot.backend.domain.auth.service.AuthService;
import com.eve.buy.bot.backend.domain.character.entity.Character;
import com.eve.buy.bot.backend.domain.character.repository.CharacterRepository;
import com.eve.buy.bot.backend.esi.EsiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zaehlt die Skill Injectors, die ein angemeldeter Charakter tatsaechlich besitzt.
 *
 * <p>Gezaehlt wird ueber die Besitzliste aus ESI. Die ist gross - jeder Hangar, jedes Schiff
 * und jeder Container wird einzeln aufgefuehrt - deshalb wird das Ergebnis je Charakter
 * zwischengespeichert. ESI selbst liefert Assets ohnehin nur stuendlich frisch, oefter
 * abzufragen brächte also nichts ausser Last.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InjectorInventoryService {

    /** Ohne diesen Scope lehnt ESI die Besitzliste ab. */
    public static final String ASSETS_SCOPE = "esi-assets.read_assets.v1";

    /** So lange gilt ein einmal gezaehlter Bestand als aktuell genug. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(15);

    private final AuthService authService;
    private final CharacterRepository characterRepo;
    private final EsiService esiService;

    /**
     * Der Bestand eines Charakters.
     *
     * @param quantity  Anzahl der Large Skill Injectors, {@code null} wenn unbekannt
     * @param available {@code true}, wenn die Zahl belastbar ist
     * @param hint      Grund, falls sie es nicht ist - fuer die Anzeige im Frontend
     */
    public record InjectorStock(Long quantity, boolean available, String hint) {}

    /**
     * Ein zwischengespeicherter Zaehlerstand.
     *
     * @param stock     der ermittelte Bestand
     * @param fetchedAt Zeitpunkt der Ermittlung
     */
    private record CachedStock(InjectorStock stock, Instant fetchedAt) {}

    private final Map<Long, CachedStock> cache = new ConcurrentHashMap<>();

    /**
     * Liefert den Injector-Bestand eines Charakters.
     *
     * <p>Antwortet auch im Fehlerfall mit einem Ergebnis statt mit einer Ausnahme: das Badge
     * ist Beiwerk, ein Ausfall der Besitzliste darf die Seite nicht stoeren.
     *
     * @param characterId der angemeldete Charakter
     * @return der Bestand oder ein Ergebnis mit Begruendung
     */
    public InjectorStock getStock(Long characterId) {
        CachedStock cached = cache.get(characterId);
        if (cached != null && Duration.between(cached.fetchedAt(), Instant.now()).compareTo(CACHE_TTL) < 0) {
            return cached.stock();
        }

        InjectorStock stock = countFromEsi(characterId);
        // Auch ein Fehlschlag wird gemerkt, sonst laeuft jeder Seitenaufruf erneut ins Leere
        cache.put(characterId, new CachedStock(stock, Instant.now()));
        return stock;
    }

    /** Verwirft den gemerkten Bestand, etwa nach einer Neuanmeldung mit anderen Scopes. */
    public void invalidate(Long characterId) {
        cache.remove(characterId);
    }

    /**
     * Holt die Besitzliste und zaehlt die Injectors darin zusammen.
     *
     * @param characterId der Charakter
     * @return der Bestand oder ein Ergebnis mit Begruendung
     */
    private InjectorStock countFromEsi(Long characterId) {
        Optional<Character> characterOpt = characterRepo.findById(characterId);
        if (characterOpt.isEmpty()) {
            return new InjectorStock(null, false, "Charakter ist nicht verknuepft.");
        }
        Character character = characterOpt.get();

        String token;
        try {
            token = authService.getValidAccessToken(character);
        } catch (Exception e) {
            log.warn("Token fuer {} nicht erneuerbar: {}", character.getName(), e.getMessage());
            return new InjectorStock(null, false, "Zugriffstoken konnte nicht erneuert werden.");
        }

        if (!authService.tokenHasScope(token, ASSETS_SCOPE)) {
            return new InjectorStock(null, false,
                    "Der Zugriff auf die Besitzliste fehlt. Melde dich einmal neu an, dann wird der Bestand angezeigt.");
        }

        try {
            List<EsiService.EsiAssetResponse> assets = esiService.getAllAssets(characterId, token);
            long anzahl = 0;
            for (EsiService.EsiAssetResponse asset : assets) {
                if (asset.type_id() != null
                        && asset.type_id() == MarketService.LARGE_SKILL_INJECTOR_TYPE_ID
                        && asset.quantity() != null) {
                    anzahl += asset.quantity();
                }
            }
            log.debug("{} besitzt {} Skill Injectors ({} Positionen geprueft).",
                    character.getName(), anzahl, assets.size());
            return new InjectorStock(anzahl, true, null);
        } catch (Exception e) {
            log.warn("Besitzliste fuer {} nicht lesbar: {}", character.getName(), e.getMessage());
            return new InjectorStock(null, false, "Die Besitzliste war gerade nicht abrufbar.");
        }
    }
}
