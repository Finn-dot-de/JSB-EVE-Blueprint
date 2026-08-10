package com.eve.own.auth.backend.domain.industry.service;

import com.eve.own.auth.backend.domain.assets.service.MyAssetService;
import com.eve.own.auth.backend.domain.industry.dto.IndustryDtos;
import com.eve.own.auth.backend.domain.industry.entity.CharacterBlueprint;
import com.eve.own.auth.backend.domain.industry.repository.CharacterBlueprintRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository.BlueprintInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prueft, ob die vorhandenen Blaupausen fuer einen Auftrag ausreichen.
 *
 * <p>Zwei Fragen, die man nicht verwechseln darf. Erstens: gibt es ueberhaupt
 * eine? Ohne Blaupause laesst sich der Job nicht einmal starten. Zweitens:
 * reichen die <em>Laeufe</em>? Eine Kopie mit fuenf Laeufen traegt keinen
 * Auftrag ueber fuenfzig Schiffe - und genau das faellt sonst erst auf, wenn
 * die Kopie mitten im Auftrag aufgebraucht ist.</p>
 *
 * <p>Ein Original hat unbegrenzt Laeufe. Deshalb ist die Unterscheidung
 * Original/Kopie hier nicht Beiwerk, sondern der Kern der Antwort.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlueprintCheckService {

    private final CharacterBlueprintRepository blueprintRepo;
    private final IndustryQueryRepository queryRepo;
    private final MyAssetService assetService;

    /**
     * Prueft alle Blaupausen, die ein Auftrag braucht.
     *
     * <p>Aufgefuehrt wird alles, wofuer es ueberhaupt eine Blaupause gibt -
     * nicht nur, was gerade auf "Bauen" steht. Wer wissen will, was er
     * braeuchte, um alles von Grund auf zu machen, sieht die Liste sonst nie:
     * Reaktionsformeln etwa tauchen erst auf, wenn man jede Zeile einzeln
     * umgestellt hat. Was gekauft werden soll, ist als solches gekennzeichnet
     * und zaehlt nicht gegen die Vollstaendigkeit.</p>
     *
     * @param productTypeId  das Endprodukt
     * @param productRuns    wie viele Laeufe dafuer noetig sind
     * @param buildRows      die Zeilen, die auf "Bauen" stehen
     */
    @Transactional(readOnly = true)
    public List<IndustryDtos.BlueprintCheckDto> check(Long characterId, long productTypeId,
                                                      long productRuns,
                                                      List<IndustryDtos.RequirementDto> buildRows) {
        Set<Long> chars;
        try {
            chars = assetService.ownCharacterIds(assetService.resolveMainId(characterId));
        } catch (IllegalStateException e) {
            log.debug("Kein Konto zu Charakter {}: {}", characterId, e.getMessage());
            return List.of();
        }

        List<IndustryDtos.BlueprintCheckDto> zeilen = new ArrayList<>();
        zeilen.add(checkOne(chars, productTypeId, productRuns, true));

        for (IndustryDtos.RequirementDto zeile : buildRows) {
            BlueprintInfo bp = queryRepo.blueprintFor(zeile.typeId());
            if (bp == null) {
                // Mineralien, PI-Gueter und Gas haben keine Blaupause - dort ist
                // die Frage gegenstandslos.
                continue;
            }
            boolean wirdGebaut = "BUILD".equals(zeile.decision());
            long laeufe = IndustryMath.runsForQuantity(zeile.needed(), bp.unitsPerRun());
            zeilen.add(checkOne(chars, zeile.typeId(), laeufe, wirdGebaut));
        }
        return zeilen;
    }

    /**
     * Die Lage zu einer einzelnen Blaupause.
     *
     * <p>Die Laeufe aller Kopien werden addiert: drei Kopien zu je zwanzig
     * Laeufen tragen einen Auftrag ueber sechzig Stueck genauso wie eine Kopie
     * mit sechzig.</p>
     */
    private IndustryDtos.BlueprintCheckDto checkOne(Set<Long> characterIds, long productTypeId,
                                                     long neededRuns, boolean required) {
        BlueprintInfo bp = queryRepo.blueprintFor(productTypeId);
        if (bp == null) {
            return new IndustryDtos.BlueprintCheckDto(
                    productTypeId, "", 0, neededRuns, 0, false, false, 0, 0,
                    required, formulaLabel(0),
                    "Für dieses Produkt gibt es keine Blaupause.");
        }

        List<CharacterBlueprint> vorhanden = characterIds.isEmpty()
                ? List.of()
                : blueprintRepo.findBest(characterIds, bp.blueprintTypeId());

        if (vorhanden.isEmpty()) {
            return new IndustryDtos.BlueprintCheckDto(
                    productTypeId, bp.productName(), bp.blueprintTypeId(), neededRuns, 0,
                    false, false, 0, 0, required, formulaLabel(bp.activityId()),
                    required
                            ? "Nicht vorhanden - ohne sie lässt sich der Job nicht starten."
                            : "Nicht vorhanden - nötig, falls du das selbst herstellen willst.");
        }

        boolean original = vorhanden.stream().anyMatch(b -> !Boolean.TRUE.equals(b.getCopy()));
        long laeufeGesamt = original
                ? Long.MAX_VALUE
                : vorhanden.stream().mapToLong(b -> Math.max(0, b.getRuns())).sum();

        CharacterBlueprint beste = vorhanden.getFirst();
        boolean reicht = original || laeufeGesamt >= neededRuns;

        String hinweis = null;
        if (!reicht) {
            hinweis = "Nur %d von %d Läufen - es fehlen %d."
                    .formatted(laeufeGesamt, neededRuns, neededRuns - laeufeGesamt);
        }

        return new IndustryDtos.BlueprintCheckDto(
                productTypeId, bp.productName(), bp.blueprintTypeId(),
                neededRuns, original ? -1 : laeufeGesamt,
                true, reicht,
                beste.getMaterialEfficiency(), beste.getTimeEfficiency(),
                required, formulaLabel(bp.activityId()), hinweis);
    }

    /**
     * Ob es eine Blaupause oder eine Reaktionsformel ist.
     *
     * <p>Ingame heissen die Dinger verschieden, und wer eine Reaktionsformel
     * sucht, findet sie nicht unter "Blueprint". Der Unterschied gehoert also
     * auf den Bildschirm.</p>
     */
    private static String formulaLabel(int activityId) {
        return activityId == com.eve.own.auth.backend.domain.industry.IndustryActivity.REACTION_SDE
                ? "Reaktionsformel"
                : "Blaupause";
    }
}
