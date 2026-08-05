package com.eve.own.auth.backend.domain.character;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Welche Corporations diese Instanz betreut.
 *
 * <p>Die Liste besteht aus der Haupt-Corporation und beliebig vielen
 * Alt-Corporations. Sie entscheidet an drei Stellen ueber sehr viel: wer sich
 * anmelden darf, wessen Bestaende gespiegelt werden und wer beim naechsten
 * Sync auf {@link com.eve.own.auth.backend.domain.auth.SystemRoles#GUEST}
 * zurueckgestuft wird.</p>
 *
 * <p>Vorher parste jede dieser Stellen die Konfiguration selbst - dreimal
 * derselbe Code, dreimal die Gelegenheit, sich in Kleinigkeiten zu
 * unterscheiden. Jetzt wird einmal beim Start gelesen und danach nur noch
 * gefragt.</p>
 */
@Component
public class CorporationScope {

    private final Long mainCorporationId;
    private final List<Long> allowedCorporationIds;

    public CorporationScope(@Value("${eve.sso.allowed-corp-id}") Long mainCorporationId,
                            @Value("${eve.alt-corp-ids:}") String altCorporationIds) {
        this.mainCorporationId = mainCorporationId;
        this.allowedCorporationIds = List.copyOf(parse(mainCorporationId, altCorporationIds));
    }

    private static List<Long> parse(Long mainCorporationId, String altCorporationIds) {
        List<Long> corporationIds = new ArrayList<>();
        corporationIds.add(mainCorporationId);
        if (altCorporationIds == null || altCorporationIds.isBlank()) {
            return corporationIds;
        }
        Arrays.stream(altCorporationIds.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .map(Long::valueOf)
                .filter(id -> !corporationIds.contains(id))
                .forEach(corporationIds::add);
        return corporationIds;
    }

    /** Die Haupt-Corporation. Ihre Mitglieder bekommen zusaetzliche Rollen. */
    public Long mainCorporationId() {
        return mainCorporationId;
    }

    /** Haupt- und Alt-Corporations, unveraenderlich. */
    public List<Long> allowedCorporationIds() {
        return allowedCorporationIds;
    }

    public boolean isAllowed(Long corporationId) {
        return corporationId != null && allowedCorporationIds.contains(corporationId);
    }

    public boolean isMain(Long corporationId) {
        return Objects.equals(mainCorporationId, corporationId);
    }
}
