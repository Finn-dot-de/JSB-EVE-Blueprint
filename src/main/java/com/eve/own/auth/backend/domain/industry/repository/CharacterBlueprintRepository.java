package com.eve.own.auth.backend.domain.industry.repository;

import com.eve.own.auth.backend.domain.industry.entity.CharacterBlueprint;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Die Blaupausen der Charaktere - die einzige Quelle fuer ME und TE. */
public interface CharacterBlueprintRepository extends JpaRepository<CharacterBlueprint, Long> {

    List<CharacterBlueprint> findByCharacterIdIn(Collection<Long> characterIds);

    /**
     * Die beste verfuegbare Blaupause fuer einen Typ im Kontoverbund.
     *
     * <p>Sortiert nach Materialeffizienz und erst danach nach Zeit: Material
     * kostet ISK, Zeit kostet Geduld. Originale vor Kopien, weil eine Kopie mit
     * wenigen Laeufen den Auftrag nicht traegt.</p>
     */
    @Query("""
           SELECT b FROM CharacterBlueprint b
           WHERE b.characterId IN :characterIds AND b.typeId = :typeId
           ORDER BY b.copy ASC, b.materialEfficiency DESC, b.timeEfficiency DESC
           """)
    List<CharacterBlueprint> findBest(@Param("characterIds") Collection<Long> characterIds,
                                      @Param("typeId") Long typeId);

    void deleteByCharacterId(Long characterId);
}
