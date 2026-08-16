package com.eve.own.auth.backend.domain.character.service;

import com.eve.own.auth.backend.common.EveImageUrls;
import com.eve.own.auth.backend.domain.character.dto.CharacterDtos;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Die Sicht auf einen Account, also einen Main-Charakter samt seiner Alts.
 *
 * <p>Ein "Account" ist hier nichts, was CCP kennt: EVE verwaltet Charaktere
 * einzeln. Die Zusammenfassung entsteht erst dadurch, dass ein Spieler seine
 * Charaktere hier verknuepft.</p>
 */
@Service
public class AccountService {

    private static final String UNKNOWN_CORPORATION = "Unbekannt";

    private final CharacterRepository characterRepo;

    public AccountService(CharacterRepository characterRepo) {
        this.characterRepo = characterRepo;
    }

    /** Alle Charaktere des Accounts, zu dem der angegebene Charakter gehoert. */
    @Transactional(readOnly = true)
    public List<CharacterDtos.CharacterRefDto> charactersOfAccount(Long characterId) {
        Long accountId = requireCharacter(characterId).getAccountId();
        return characterRepo.findByMainCharacterId(accountId).stream()
                .map(character -> toCharacterRef(character, accountId))
                .toList();
    }

    private static CharacterDtos.CharacterRefDto toCharacterRef(Character character, Long accountId) {
        return new CharacterDtos.CharacterRefDto(
                character.getId(), character.getName(),
                EveImageUrls.portrait(character.getId()),
                character.getId().equals(accountId));
    }

    /**
     * Bestimmt einen anderen Charakter des Accounts zum Main.
     *
     * <p>Alle Charaktere des Accounts werden umgehaengt, damit der Verbund
     * zusammenbleibt. Der neue Main muss bereits zum Account gehoeren - sonst
     * liesse sich ein fremder Charakter vereinnahmen.</p>
     *
     * @throws IllegalArgumentException wenn der Zielcharakter nicht zum Account gehoert
     */
    @Transactional
    public void changeMainCharacter(Long requestingCharacterId, Long newMainId) {
        Long accountId = requireCharacter(requestingCharacterId).getAccountId();
        List<Character> accountCharacters = characterRepo.findByMainCharacterId(accountId);

        boolean belongsToAccount = accountCharacters.stream()
                .anyMatch(character -> character.getId().equals(newMainId));
        if (!belongsToAccount) {
            throw new IllegalArgumentException("Dieser Charakter gehoert nicht zu deinem Account.");
        }

        accountCharacters.forEach(character -> character.setMainCharacterId(newMainId));
        characterRepo.saveAll(accountCharacters);
    }

    /** Alle bekannten Accounts fuer die Administration, alphabetisch nach Main. */
    @Transactional(readOnly = true)
    public List<CharacterDtos.AdminAccountDto> allAccounts() {
        Map<Long, List<Character>> byAccount = characterRepo.findAllWithCorporation().stream()
                .collect(Collectors.groupingBy(Character::getAccountId));

        return byAccount.entrySet().stream()
                .map(entry -> toAdminAccount(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(CharacterDtos.AdminAccountDto::mainName,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private CharacterDtos.AdminAccountDto toAdminAccount(Long accountId, List<Character> characters) {
        Character main = characters.stream()
                .filter(character -> character.getId().equals(accountId))
                .findFirst()
                .orElse(characters.getFirst());

        List<CharacterDtos.AdminAccountCharDto> alts = characters.stream()
                .filter(character -> !character.getId().equals(accountId))
                .map(AccountService::toAdminCharacter)
                .sorted(Comparator.comparing(CharacterDtos.AdminAccountCharDto::name,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();

        return new CharacterDtos.AdminAccountDto(accountId, main.getName(),
                EveImageUrls.portrait(accountId), corporationNameOf(main), alts);
    }

    private static CharacterDtos.AdminAccountCharDto toAdminCharacter(Character character) {
        return new CharacterDtos.AdminAccountCharDto(character.getId(), character.getName(),
                EveImageUrls.portrait(character.getId()), corporationNameOf(character));
    }

    private static String corporationNameOf(Character character) {
        return character.getCorporation() != null
                ? character.getCorporation().getName()
                : UNKNOWN_CORPORATION;
    }

    private Character requireCharacter(Long characterId) {
        return characterRepo.findById(characterId).orElseThrow(
                () -> new IllegalArgumentException("Charakter " + characterId + " ist unbekannt."));
    }
}
