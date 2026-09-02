package com.eve.own.auth.backend.domain.character.repository;

import java.time.Instant;
import com.eve.own.auth.backend.domain.character.entity.CharacterContact;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface CharacterContactRepository extends JpaRepository<CharacterContact, Long> {

    List<CharacterContact> findByCharacterId(Long characterId);

    List<CharacterContact> findByContactId(Long contactId);

    /**
     * Die <b>vollstaendigen</b> Kontaktlisten dieser Charaktere.
     *
     * <p>Absichtlich vollstaendig und nicht auf die gesuchten Gegenparteien
     * eingeschraenkt: die Bewertung braucht die <em>Laenge</em> der Liste, um
     * einen Eintrag bei jemandem mit dreihundert Kontakten schwaecher zu werten
     * als denselben Eintrag bei jemandem mit fuenf. Wer nur die Treffer laedt,
     * kann diese Laenge nicht mehr kennen - und rechnet dann genau die
     * Adressbuecher hoch, gegen die die Daempfung steht.</p>
     */
    List<CharacterContact> findByCharacterIdIn(Collection<Long> characterIds);

    boolean existsByCharacterId(Long characterId);

    /**
     * Bewusst als JPQL-Loeschung und nicht als abgeleitetes {@code deleteBy...}.
     *
     * <p>Die abgeleitete Variante laedt die Zeilen und merkt sie zum Loeschen
     * vor; Hibernate reiht die Einfuegungen der neuen Momentaufnahme dann
     * <em>vor</em> den Loeschungen ein und laeuft in den eindeutigen Schluessel
     * {@code uk_contact_char_contact}. Die Massenloeschung geht sofort an die
     * Datenbank, also in der richtigen Reihenfolge.</p>
     */
    @Modifying
    @Query("DELETE FROM CharacterContact c WHERE c.characterId = :characterId")
    void deleteByCharacterId(Long characterId);

    /**
     * Der Loeschlauf der Aufbewahrungsfrist.
     *
     * <p>Noetig, obwohl jeder Erfassungslauf die Zeilen eines Charakters ersetzt:
     * das Ersetzen geschieht <em>je Charakter</em> und nur, wenn dieser Charakter
     * im Lauf ueberhaupt vorkommt. Wer sein Token entzieht, wessen Token
     * ungueltig wird oder wessen Quelle abgeschaltet wird, faellt aus dem Lauf
     * heraus - und seine Zeilen blieben sonst fuer immer liegen. Genau das ist
     * die Luecke zwischen dem, was die Oberflaeche zusagt, und dem, was die
     * Bauform leistet.</p>
     */
    @Modifying
    @Query("DELETE FROM CharacterContact e WHERE e.recordedAt < :threshold")
    int deleteOlderThan(Instant threshold);
}
