package com.eve.own.auth.backend.domain.dashboard.service;

import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.dashboard.dto.DashboardDto;
import com.eve.own.auth.backend.esi.EsiService;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.character.entity.Character;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class DashboardService {

    private final EsiService esiService;
    private final CharacterRepository characterRepository;
    private final AuthService authService;

    public DashboardService(EsiService esiService,
                            CharacterRepository characterRepository,
                            AuthService authService) {
        this.esiService = esiService;
        this.characterRepository = characterRepository;
        this.authService = authService;
    }

    @Transactional(readOnly = true)
    public DashboardDto getDashboardData(Long requestingCharacterId) {

        // 1. Den Charakter laden, der den Request stellt
        Character reqChar = characterRepository.findById(requestingCharacterId)
                .orElseThrow(() -> new RuntimeException("Charakter nicht gefunden"));

        // 2. ALLE Charaktere des Accounts holen (Main + alle Alts)
        Long mainId = reqChar.getMainCharacterId() != null ? reqChar.getMainCharacterId() : reqChar.getId();
        List<Character> accountCharacters = characterRepository.findByMainCharacterId(mainId);

        // 3. Wallet über alle Charaktere aggregieren
        double totalIsk = 0.0;
        for (Character c : accountCharacters) {
            try {
                String validToken = authService.getValidAccessToken(c);

                var walletResp = esiService.getWalletBalance(c.getId(), validToken, null);
                Double balance = walletResp.data();

                if (balance != null) {
                    totalIsk += balance;
                }
            } catch (Exception e) {
                log.warn("Konnte Wallet für Char {} nicht abrufen: {}", c.getId(), e.getMessage());
            }
        }

        // 4. Allianz-Namen sicher extrahieren (falls vorhanden)
        String allianceName = null;
        if (reqChar.getCorporation().getAlliance() != null) {
            allianceName = reqChar.getCorporation().getAlliance().getName();
        }

        String portraitUrl = String.format("https://images.evetech.net/characters/%d/portrait?size=128", reqChar.getId());

        // 5. Fertiges DTO zurückgeben
        return new DashboardDto(
                reqChar.getName(),
                portraitUrl,
                reqChar.getCorporation().getName(),
                allianceName,
                totalIsk,
                accountCharacters.size()
        );
    }
}