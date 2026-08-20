package com.eve.own.auth.backend.domain.discord.service;

import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Stoesst den Abgleich fuer <b>einen</b> Charakter an und sagt, was daraus wurde.
 *
 * <p>Bisher gab es nur den Zeitplan: Wer eine Rolle vergab, wartete bis zu
 * dreissig Minuten und sah dann in Discord nach, ob sie angekommen war. Die
 * Ursache {@link DiscordRollenBefund.Ursache#ABGLEICH_STEHT_AUS} benennt genau
 * diese Wartezeit - ohne einen Knopf daneben waere sie eine Feststellung ohne
 * Handlungsmoeglichkeit.</p>
 *
 * <p><b>Der Plan kommt aus der Pruefung, nicht von hier.</b>
 * {@link DiscordRoleAuditService#planFuer(Long)} rechnet das Soll; diese Klasse
 * schickt es hinaus. Wuerde sie selbst rechnen, koennte die Uebersicht "Cap
 * Azubi fehlt" sagen und der Anstoss etwas anderes setzen - zwei Wahrheiten,
 * zwischen denen niemand entscheiden kann.</p>
 *
 * <p>Der Zeitplan bleibt daneben bestehen. Ein Anstoss von Hand erreicht nur die
 * Person, an die gerade jemand denkt.</p>
 */
@Slf4j
@Service
public class DiscordRoleSyncService {

    private final DiscordRoleAuditService auditService;
    private final DiscordBotService discordBotService;
    private final DiscordSyncStand syncStand;

    public DiscordRoleSyncService(DiscordRoleAuditService auditService,
                                  DiscordBotService discordBotService,
                                  DiscordSyncStand syncStand) {
        this.auditService = auditService;
        this.discordBotService = discordBotService;
        this.syncStand = syncStand;
    }

    /**
     * Fuehrt den Abgleich sofort aus.
     *
     * @return leer, wenn es den Charakter nicht gibt - das ist ein Fehler des
     *         Aufrufers und keine Rueckmeldung ueber einen Abgleich
     */
    public Optional<DiscordSyncErgebnis> stosseAn(Long characterId) {
        Optional<DiscordRollenplan> gefunden = auditService.planFuer(characterId);
        if (gefunden.isEmpty()) {
            return Optional.empty();
        }
        DiscordRollenplan plan = gefunden.get();

        if (plan.discordUserId() == null) {
            return Optional.of(ohneAbgleich(plan,
                    DiscordRollenBefund.Ursache.KEINE_VERKNUEPFUNG.erklaerung()));
        }
        if (plan.verwalteteRollen().isEmpty()) {
            // Ausgefuehrt, aber ohne Wirkung: Solange keine einzige Zuordnung
            // gepflegt ist, hat das Auth in Discord nichts zu suchen. Das als
            // Fehlschlag zu melden, schickte jemanden auf die Suche nach einem
            // Fehler, den es nicht gibt.
            return Optional.of(new DiscordSyncErgebnis(plan.characterId(), plan.characterName(),
                    plan.mainCharacterId(), plan.mainCharacterName(), plan.discordUserId(),
                    true, "Es ist keine einzige Discord-Rolle zugeordnet - der Abgleich "
                    + "hatte nichts zu tun.", List.of()));
        }

        List<DiscordRollenErgebnis> ergebnisse;
        try {
            ergebnisse = discordBotService.syncManagedRoles(plan.discordUserId(),
                    plan.verwalteteRollen(), plan.sollRollen(), plan.nickname());
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.of(ohneAbgleich(plan,
                    "Das Konto ist kein Mitglied des Servers (404). Rollen kann nur tragen, wer da ist."));
        } catch (HttpClientErrorException.TooManyRequests e) {
            // Eigener Zweig, weil die Abhilfe eine andere ist als bei jedem
            // sonstigen Fehler: abwarten und erneut druecken.
            return Optional.of(ohneAbgleich(plan,
                    "Discord bremst gerade (429). In ein paar Sekunden noch einmal versuchen."));
        } catch (HttpClientErrorException.Forbidden e) {
            // Eigener Zweig, seit der Abgleich mit einem Lesezugriff beginnt:
            // Scheitert schon der, kennt niemand den Ist-Zustand, und es geht
            // kein einziger Schreibzugriff hinaus. Unter "Discord antwortet
            // nicht" abgelegt, klaenge das nach Stoerung - es ist aber eine
            // Rangfolge, die sich in den Servereinstellungen richten laesst.
            return Optional.of(ohneAbgleich(plan,
                    "Discord verweigert die Auskunft ueber dieses Konto (403). Die Bot-Rolle "
                            + "muss ueber den zu setzenden Rollen stehen; am Server-Owner "
                            + "scheitert jeder Bot."));
        } catch (RuntimeException e) {
            return Optional.of(ohneAbgleich(plan, "Discord antwortet nicht: " + e.getMessage()));
        }

        // Der Lauf zaehlt, auch wenn einzelne Rollen abgelehnt wurden: Er hat
        // stattgefunden, und die Ursache ABGLEICH_STEHT_AUS trifft ab jetzt
        // nicht mehr zu. Sie stehen zu lassen, hiesse eine Wartezeit zu melden,
        // die abgelaufen ist.
        syncStand.notiere(plan.discordUserId());

        List<DiscordSyncErgebnis.Zeile> zeilen = ergebnisse.stream()
                .map(e -> new DiscordSyncErgebnis.Zeile(
                        plan.authRolleJeDiscordRolle().get(e.discordRoleId()),
                        e.discordRoleId(), e.aktion(), e.erfolg(), e.geaendert(), e.grund()))
                .toList();

        long gescheitert = zeilen.stream().filter(z -> !z.erfolg()).count();
        if (gescheitert > 0) {
            log.warn("Abgleich fuer {} ({}) angestossen: {} von {} Rollen abgelehnt.",
                    plan.characterName(), plan.discordUserId(), gescheitert, zeilen.size());
        }
        return Optional.of(new DiscordSyncErgebnis(plan.characterId(), plan.characterName(),
                plan.mainCharacterId(), plan.mainCharacterName(), plan.discordUserId(),
                true, null, zeilen));
    }

    /** Der Abgleich ging gar nicht erst hinaus - mit Begruendung statt stummer Leere. */
    private DiscordSyncErgebnis ohneAbgleich(DiscordRollenplan plan, String hinweis) {
        return new DiscordSyncErgebnis(plan.characterId(), plan.characterName(),
                plan.mainCharacterId(), plan.mainCharacterName(), plan.discordUserId(),
                false, hinweis, List.of());
    }
}
