package com.eve.own.auth.backend.domain.character.repository;

import com.eve.own.auth.backend.domain.character.entity.CharacterAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CharacterAssetRepository extends JpaRepository<CharacterAsset, Long> {

    @Modifying
    @Query("DELETE FROM CharacterAsset a WHERE a.characterId = :characterId")
    void deleteByCharacterId(Long characterId);

    /**
     * Die Schiffsbestaende eines Accounts, verdichtet auf SDE-Gruppen.
     *
     * <p>Als native Query, um das {@code evesde}-Schema direkt zu joinen.
     * Kategorie 6 sind die Schiffe - alles andere waere fuer die Uebersicht des
     * Dashboards ohnehin nur Rauschen.</p>
     *
     * <p>Spalten der Ergebniszeilen: [0] Gruppenname, [1] Menge.</p>
     *
     * <p>Die Abfrage lieferte frueher zusaetzlich eine fertige Bild-URL, die
     * niemand gelesen hat. Sie zwang die Gruppierung auf die einzelne typeID
     * herunter und damit zu einem Vielfachen der noetigen Zeilen - je Gruppe
     * eine pro Schiffstyp statt einer einzigen.</p>
     */
    @Query(value = """
        SELECT g."groupName" AS groupName,
               SUM(a.quantity) AS quantity
        FROM character_assets a
        JOIN evesde."invTypes" t ON a.type_id = t."typeID"
        JOIN evesde."invGroups" g ON t."groupID" = g."groupID"
        WHERE a.character_id IN (:characterIds)
          AND g."categoryID" = 6
        GROUP BY g."groupName"
        ORDER BY quantity DESC
        """, nativeQuery = true)
    List<Object[]> aggregateAssetsByGroup(List<Long> characterIds);

    @Query(value = "SELECT DISTINCT a.type_id FROM character_assets a", nativeQuery = true)
    List<Long> findDistinctAssetTypeIds();

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM character_assets a
                JOIN evesde."invTypes" t ON t."typeID" = a.type_id
                JOIN evesde."invGroups" g ON g."groupID" = t."groupID"
                WHERE a.character_id = :characterId
                  AND a.is_singleton IS TRUE
                  AND g."categoryID" IN (2, 6, 22, 65)
                  AND a.custom_name IS NULL
            )
            """, nativeQuery = true)
    boolean hasPendingCustomNames(Long characterId);
}
