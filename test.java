package com.example.idmhub.controller;

import com.example.idmhub.entity.UserEntity;
import com.example.idmhub.repository.UserRepository;
import com.unboundid.scim2.common.types.Email;
import com.unboundid.scim2.common.types.Meta;
import com.unboundid.scim2.common.types.UserResource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Calendar;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping(value = "/scim/v2/Users", produces = "application/scim+json")
@Tag(name = "SCIM 2.0 User Provisioning", description = "Endpunkte für das Verwalten von Benutzern im IDM Hub")
public class ScimUserController {

    private final UserRepository userRepository;

    // Dependency Injection via Konstruktor
    public ScimUserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @PostMapping(consumes = "application/scim+json")
    @Operation(summary = "Neuen Benutzer anlegen", description = "Speichert einen SCIM-Benutzer in Postgres.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Benutzer erfolgreich angelegt", 
                         content = @Content(schema = @Schema(implementation = UserResource.class))),
            @ApiResponse(responseCode = "400", description = "Ungültiges Format")
    })
    public ResponseEntity<UserResource> createUser(@RequestBody UserResource incomingUser) {
        
        System.out.println("Speichere User in DB: " + incomingUser.getUserName());

        // 1. ID generieren
        String newId = UUID.randomUUID().toString();
        
        // 2. Primäre E-Mail aus dem SCIM-Objekt extrahieren (falls vorhanden)
        String primaryEmail = null;
        if (incomingUser.getEmails() != null && !incomingUser.getEmails().isEmpty()) {
            primaryEmail = incomingUser.getEmails().get(0).getValue();
        }

        // 3. Entity bauen und in Postgres speichern
        UserEntity entity = new UserEntity(newId, incomingUser.getUserName(), primaryEmail);
        userRepository.save(entity);

        // 4. Das SCIM Response-Objekt fertigstellen
        incomingUser.setId(newId);
        
        Meta meta = new Meta();
        meta.setResourceType("User");
        meta.setCreated(Calendar.getInstance());
        meta.setLastModified(Calendar.getInstance());
        String location = "http://localhost:8080/scim/v2/Users/" + newId;
        meta.setLocation(location);
        incomingUser.setMeta(meta);

        return ResponseEntity
                .created(URI.create(location))
                .body(incomingUser);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Benutzer aus Postgres abrufen")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Benutzer gefunden"),
            @ApiResponse(responseCode = "404", description = "Benutzer nicht gefunden", content = @Content)
    })
    public ResponseEntity<UserResource> getUser(@PathVariable String id) {
        
        // 1. In Postgres nach der ID suchen
        Optional<UserEntity> userOpt = userRepository.findById(id);

        if (userOpt.isEmpty()) {
            // Wenn nicht gefunden: Sauberes HTTP 404 zurückgeben
            return ResponseEntity.notFound().build();
        }

        UserEntity dbUser = userOpt.get();

        // 2. Datenbank-Entität wieder auf ein SCIM-Objekt (UserResource) mappen
        UserResource scimUser = new UserResource();
        scimUser.setId(dbUser.getId());
        scimUser.setUserName(dbUser.getUserName());
        
        if (dbUser.getPrimaryEmail() != null) {
            scimUser.addEmail(new Email()
                    .setValue(dbUser.getPrimaryEmail())
                    .setType("work")
                    .setPrimary(true));
        }

        return ResponseEntity.ok(scimUser);
    }
}
