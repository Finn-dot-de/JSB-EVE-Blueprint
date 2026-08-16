package com.eve.own.auth.backend.domain.fleet.service;

import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.eve.entity.InvType;
import com.eve.own.auth.backend.domain.eve.repository.InvTypeRepository;
import com.eve.own.auth.backend.domain.fleet.entity.FleetDoctrine;
import com.eve.own.auth.backend.domain.fleet.repository.FleetDoctrineRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Verwaltet die hinterlegten Doktrin-Fittings. */
@Service
public class FleetDoctrineService {

    /** Auffangkorb fuer Fittings, die keiner Doktrin zugeordnet wurden. */
    private static final String UNGROUPED = "Ungruppiert";

    private final FleetDoctrineRepository doctrineRepo;
    private final CharacterRepository characterRepo;
    private final InvTypeRepository invTypeRepo;

    public FleetDoctrineService(FleetDoctrineRepository doctrineRepo,
                                CharacterRepository characterRepo,
                                InvTypeRepository invTypeRepo) {
        this.doctrineRepo = doctrineRepo;
        this.characterRepo = characterRepo;
        this.invTypeRepo = invTypeRepo;
    }

    /** Die Angaben zu einem Fitting. */
    public record DoctrineCommand(String doctrineName, String shipType, String name, String eftString) {}

    @Transactional(readOnly = true)
    public List<FleetDoctrine> findAll() {
        return doctrineRepo.findAll();
    }

    @Transactional
    public FleetDoctrine create(Long creatorCharacterId, DoctrineCommand command) {
        Character creator = characterRepo.findById(creatorCharacterId).orElseThrow(
                () -> new IllegalArgumentException("Charakter " + creatorCharacterId + " ist unbekannt."));

        FleetDoctrine doctrine = new FleetDoctrine();
        doctrine.setCreatedBy(creator.getName());
        doctrine.setCreatedAt(Instant.now());
        apply(doctrine, command);
        return doctrineRepo.save(doctrine);
    }

    @Transactional
    public FleetDoctrine update(Long id, DoctrineCommand command) {
        FleetDoctrine doctrine = doctrineRepo.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Fitting " + id + " ist unbekannt."));
        apply(doctrine, command);
        return doctrineRepo.save(doctrine);
    }

    @Transactional
    public void delete(Long id) {
        doctrineRepo.deleteById(id);
    }

    /**
     * Uebertraegt die Angaben und loest den Schiffstyp gegen die SDE auf.
     *
     * <p>Auch beim Aendern noetig: mit dem Schiffsnamen wandert die typeID mit,
     * an der Bild und Skillcheck haengen.</p>
     */
    private void apply(FleetDoctrine doctrine, DoctrineCommand command) {
        doctrine.setDoctrineName(doctrineNameOrDefault(command.doctrineName()));
        doctrine.setShipType(command.shipType());
        doctrine.setName(command.name());
        doctrine.setEftString(command.eftString());

        if (command.shipType() != null) {
            invTypeRepo.findByTypeNameIgnoreCase(command.shipType())
                    .map(InvType::getTypeId)
                    .ifPresent(doctrine::setShipTypeId);
        }
    }

    private static String doctrineNameOrDefault(String doctrineName) {
        return doctrineName != null && !doctrineName.isBlank() ? doctrineName : UNGROUPED;
    }
}
