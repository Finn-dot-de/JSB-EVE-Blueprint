package com.eve.own.auth.backend.domain.mining.service;

import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Die Rechtepruefung der Mining-Verwaltung, in der Fachschicht statt am
 * Controller.
 *
 * <p>Am Endpunkt steht {@code @PreAuthorize(AccessRules.LEADERSHIP_OR_IT)}, und
 * das bleibt auch so. Die Annotation gehoert aber zu <em>einem</em>
 * Einstiegspunkt: sie faellt bei einem Umbau lautlos weg, sie schuetzt einen
 * zweiten Aufrufer nicht, und sie greift gar nicht, wenn ein Scheduler oder ein
 * anderer Dienst die Methode direkt ruft. Hinter ihr liegt hier die Frage, wer
 * wieviel ISK bekommt - die teuerste Stelle der Anwendung nach der
 * Rollenvergabe. Deshalb wird zweimal geprueft, und das ist keine Unsicherheit,
 * sondern dieselbe Ueberlegung, die {@code RoleAssignmentService} schon
 * ausformuliert hat.</p>
 *
 * <p>Als eigene Komponente und nicht als privates {@code requireAdmin} in beiden
 * Diensten: {@link MiningLedgerService} und {@link MiningTaxCreditService}
 * brauchen dieselbe Regel. Zweimal ausgeschrieben waere sie zweimal zu aendern,
 * und die vergessene Haelfte waere die, an der das Geld haengt.</p>
 *
 * <p>Geprueft wird am Rollensatz der Entitaet und nicht am Sicherheitskontext -
 * dasselbe Vorgehen wie in {@code RoleAssignmentService}, {@code AuthGroupService}
 * und {@code CorporationStatsService}. Die drei Namen sind dieselben wie in
 * {@link com.eve.own.auth.backend.common.AccessRules#LEADERSHIP_OR_IT}; wer dort
 * etwas aendert, muss es hier mitaendern.</p>
 */
@Component
public class MiningAdminGuard {

    private final CharacterRepository characterRepo;

    public MiningAdminGuard(CharacterRepository characterRepo) {
        this.characterRepo = characterRepo;
    }

    /**
     * Stellt sicher, dass der Handelnde zur Fuehrung gehoert.
     *
     * @return der Handelnde, weil jeder Aufrufer ihn ohnehin fuer den Nachweis
     *     oder die Protokollzeile braucht
     * @throws AccessDeniedException wenn ihm die Rolle fehlt. Bewusst dieselbe
     *     Ausnahme wie bei einer abgewiesenen {@link PreAuthorize}-Pruefung, damit
     *     {@code ApiExceptionHandler} daraus ein 403 macht und kein 500.
     * @throws IllegalArgumentException wenn es den Charakter gar nicht gibt
     */
    @Transactional(readOnly = true)
    public Character requireLeadership(Long actorId) {
        Character actor = characterRepo.findById(actorId).orElseThrow(
                () -> new IllegalArgumentException("Charakter " + actorId + " ist unbekannt."));

        boolean admin = actor.hasRole(SystemRoles.DIRECTOR)
                || actor.hasRole(SystemRoles.CEO)
                || actor.hasRole(SystemRoles.IT_ADMIN);
        if (!admin) {
            throw new AccessDeniedException(
                    "Die Steuerakten und die Gutschriften sieht nur die Fuehrung.");
        }
        return actor;
    }
}
