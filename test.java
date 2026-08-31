package com.example.idmhub.controller;

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
import java.util.UUID;

@RestController
@RequestMapping(value = "/scim/v2/Users", produces = "application/scim+json")
// Swagger: Gruppiert alle Endpunkte dieses Controllers in der UI
@Tag(name = "SCIM 2.0 User Provisioning", description = "Endpunkte für das Verwalten von Benutzern im IDM Hub")
public class ScimUserController {

    @PostMapping(consumes = "application/scim+json")
    // Swagger: Beschreibt, was diese Methode macht
    @Operation(summary = "Neuen Benutzer anlegen", description = "Erstellt einen neuen SCIM-Benutzer und vergibt eine interne ID.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Benutzer erfolgreich angelegt", 
                         content = @Content(schema = @Schema(implementation = UserResource.class))),
            @ApiResponse(responseCode = "400", description = "Ungültiges SCIM JSON Format geliefert", content = @Content)
    })
    public ResponseEntity<UserResource> createUser(
            @Parameter(description = "Das standardkonforme SCIM User JSON") 
            @RequestBody UserResource user) {
        
        System.out.println("Neuer User empfangen: " + user.getUserName());

        String newId = UUID.randomUUID().toString();
        user.setId(newId);

        Meta meta = new Meta();
        meta.setResourceType("User");
        meta.setCreated(Calendar.getInstance());
        meta.setLastModified(Calendar.getInstance());
        
        String location = "http://localhost:8080/scim/v2/Users/" + newId;
        meta.setLocation(location);
        user.setMeta(meta);

        return ResponseEntity
                .created(URI.create(location))
                .body(user);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Benutzer anhand der ID abrufen")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Benutzer gefunden"),
            @ApiResponse(responseCode = "404", description = "Benutzer nicht gefunden", content = @Content)
    })
    public ResponseEntity<UserResource> getUser(
            @Parameter(description = "Die interne UUID des Benutzers") 
            @PathVariable String id) {
        
        System.out.println("Suche nach User mit ID: " + id);

        UserResource dummyUser = new UserResource();
        dummyUser.setId(id);
        dummyUser.setUserName("max.mustermann");
        
        dummyUser.addEmail(new Email()
                .setValue("max@beispiel.de")
                .setType("work")
                .setPrimary(true));

        return ResponseEntity.ok(dummyUser);
    }
}
