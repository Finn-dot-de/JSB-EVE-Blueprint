package com.eve.own.auth.backend.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Adressen des EVE-Bildservers")
class EveImageUrlsTest {

    private static final long CHARACTER_ID = 95465499L;
    private static final long CORPORATION_ID = 98378388L;

    @Test
    @DisplayName("baut Portraets mit Standard- und Wunschgroesse")
    void buildsPortraits() {
        assertThat(EveImageUrls.portrait(CHARACTER_ID))
                .isEqualTo("https://images.evetech.net/characters/95465499/portrait?size=64");
        assertThat(EveImageUrls.portrait(CHARACTER_ID, EveImageUrls.SIZE_LARGE))
                .isEqualTo("https://images.evetech.net/characters/95465499/portrait?size=128");
    }

    @Test
    @DisplayName("baut Corporation- und Allianz-Logos")
    void buildsLogos() {
        assertThat(EveImageUrls.corporationLogo(CORPORATION_ID))
                .isEqualTo("https://images.evetech.net/corporations/98378388/logo?size=64");
        assertThat(EveImageUrls.allianceLogo(99005338L, 32))
                .isEqualTo("https://images.evetech.net/alliances/99005338/logo?size=32");
    }

    @Test
    @DisplayName("baut Item-Symbole und Schiffsansichten")
    void buildsTypeImages() {
        assertThat(EveImageUrls.typeIcon(34L))
                .isEqualTo("https://images.evetech.net/types/34/icon?size=64");
        assertThat(EveImageUrls.typeIcon(34L, 32))
                .isEqualTo("https://images.evetech.net/types/34/icon?size=32");
        assertThat(EveImageUrls.typeRender(17738L))
                .isEqualTo("https://images.evetech.net/types/17738/render?size=256");
    }

    @Test
    @DisplayName("waehlt fuer Corp-Zeilen das Logo, fuer Spieler das Portraet")
    void picksOwnerImageByOrigin() {
        assertThat(EveImageUrls.ownerImage(CORPORATION_ID, true)).contains("/corporations/");
        assertThat(EveImageUrls.ownerImage(CHARACTER_ID, false)).contains("/characters/");
    }
}
