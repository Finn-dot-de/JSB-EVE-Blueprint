package com.eve.own.auth.backend.domain.fleet.controller;

import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.eve.repository.InvTypeRepository;
import com.eve.own.auth.backend.domain.fleet.entity.FleetDoctrine;
import com.eve.own.auth.backend.domain.fleet.repository.FleetDoctrineRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/doctrines")
public class FleetDoctrineController {

    private final FleetDoctrineRepository doctrineRepo;
    private final CharacterRepository characterRepo;
    private final InvTypeRepository invTypeRepo;

    public FleetDoctrineController(FleetDoctrineRepository doctrineRepo, CharacterRepository characterRepo, InvTypeRepository invTypeRepo) {
        this.doctrineRepo = doctrineRepo;
        this.characterRepo = characterRepo;
        this.invTypeRepo = invTypeRepo;
    }

    // Jeder eingeloggte User darf die Fittings sehen
    @GetMapping
    public ResponseEntity<List<FleetDoctrine>> getAllDoctrines() {
        return ResponseEntity.ok(doctrineRepo.findAll());
    }

    // Nur FCs und Directors dürfen Fittings erstellen
    public record CreateDoctrineDto(String doctrineName, String shipType, String name, String eftString) {}

    @PreAuthorize("hasAnyRole('ROLE_DIRECTOR', 'ROLE_FC', 'ROLE_A38')")
    @PostMapping
    public ResponseEntity<?> createDoctrine(@RequestBody CreateDoctrineDto dto) {
        Long charId = (Long) Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication()).getPrincipal();
        Character creator = characterRepo.findById(charId).orElseThrow();

        FleetDoctrine doc = new FleetDoctrine();
        doc.setDoctrineName(dto.doctrineName() != null && !dto.doctrineName().isBlank() ? dto.doctrineName() : "Ungruppiert");
        doc.setShipType(dto.shipType());
        doc.setName(dto.name());
        doc.setEftString(dto.eftString());
        doc.setCreatedBy(creator.getName());
        doc.setCreatedAt(Instant.now());

        invTypeRepo.findByTypeNameIgnoreCase(dto.shipType()).ifPresent(invType -> {
            doc.setShipTypeId(invType.getTypeId());
        });

        return ResponseEntity.ok(doctrineRepo.save(doc));
    }

    @PreAuthorize("hasAnyRole('ROLE_DIRECTOR', 'ROLE_FC', 'ROLE_A38', 'ROLE_IT_ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteDoctrine(@PathVariable Long id) {
        doctrineRepo.deleteById(id);
        return ResponseEntity.ok().build();
    }
}