package com.example.idmhub.controller;

import com.unboundid.scim2.common.types.Email;
import com.unboundid.scim2.common.types.Meta;
import com.unboundid.scim2.common.types.UserResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Calendar;
import java.util.UUID;

@RestController
// SCIM verlangt zwingend diesen speziellen Content-Type
@RequestMapping(value = "/scim/v2/Users", produces = "application/scim+json")
public class ScimUserController {

    @PostMapping(consumes = "application/scim+json")
    public ResponseEntity<UserResource> createUser(@RequestBody UserResource user) {
        System.out.println("Neuer User empfangen: " + user.getUserName());

        // 1. Eigene Logik: ID vergeben (macht später deine JPA Entity)
        String newId = UUID.randomUUID().toString();
        user.setId(newId);

        // 2. SCIM Meta-Daten pflegen (Das UEM-System erwartet diese zwingend)
        Meta meta = new Meta();
        meta.setResourceType("User");
        meta.setCreated(Calendar.getInstance());
        meta.setLastModified(Calendar.getInstance());
        
        // Die Location, unter der dieser User zukünftig erreichbar ist
        String location = "http://localhost:8080/scim/v2/Users/" + newId;
        meta.setLocation(location);
        user.setMeta(meta);

        // 3. Saubere HTTP 201 Created Antwort an das UEM-System schicken
        return ResponseEntity
                .created(URI.create(location))
                .body(user);
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResource> getUser(@PathVariable String id) {
        System.out.println("Suche nach User mit ID: " + id);

        // Simulierter Dummy-User für den Prototypen
        UserResource dummyUser = new UserResource();
        dummyUser.setId(id);
        dummyUser.setUserName("max.mustermann");
        
        // Dank SDK sparst du dir hier die ekligen verschachtelten Listen
        dummyUser.addEmail(new Email()
                .setValue("max@beispiel.de")
                .setType("work")
                .setPrimary(true));

        return ResponseEntity.ok(dummyUser);
    }
}
