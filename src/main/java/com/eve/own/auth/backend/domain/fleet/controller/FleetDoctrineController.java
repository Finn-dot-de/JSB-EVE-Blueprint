package com.eve.own.auth.backend.domain.fleet.controller;

import com.eve.own.auth.backend.common.AccessRules;
import com.eve.own.auth.backend.common.CurrentUser;
import com.eve.own.auth.backend.domain.fleet.entity.FleetDoctrine;
import com.eve.own.auth.backend.domain.fleet.service.FleetDoctrineService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Die Endpunkte zur Pflege der Doktrin-Fittings. */
@RestController
@RequestMapping("/api/doctrines")
public class FleetDoctrineController {

    private final FleetDoctrineService doctrineService;

    public FleetDoctrineController(FleetDoctrineService doctrineService) {
        this.doctrineService = doctrineService;
    }

    public record CreateDoctrineDto(String doctrineName, String shipType, String name, String eftString) {}

    /** Fittings darf jedes angemeldete Mitglied sehen. */
    @GetMapping
    public ResponseEntity<List<FleetDoctrine>> getAllDoctrines() {
        return ResponseEntity.ok(doctrineService.findAll());
    }

    @PreAuthorize(AccessRules.FLEET_STAFF_OR_IT)
    @PostMapping
    public ResponseEntity<FleetDoctrine> createDoctrine(@RequestBody CreateDoctrineDto dto) {
        return ResponseEntity.ok(doctrineService.create(CurrentUser.characterId(), toCommand(dto)));
    }

    @PreAuthorize(AccessRules.FLEET_STAFF)
    @PutMapping("/{id}")
    public ResponseEntity<FleetDoctrine> updateDoctrine(@PathVariable Long id,
                                                        @RequestBody CreateDoctrineDto dto) {
        return ResponseEntity.ok(doctrineService.update(id, toCommand(dto)));
    }

    @PreAuthorize(AccessRules.FLEET_STAFF_OR_IT)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDoctrine(@PathVariable Long id) {
        doctrineService.delete(id);
        return ResponseEntity.ok().build();
    }

    private static FleetDoctrineService.DoctrineCommand toCommand(CreateDoctrineDto dto) {
        return new FleetDoctrineService.DoctrineCommand(
                dto.doctrineName(), dto.shipType(), dto.name(), dto.eftString());
    }
}
