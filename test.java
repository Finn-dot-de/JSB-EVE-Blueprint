package com.example.idmhub.controller;

import com.unboundid.scim2.common.exceptions.BadRequestException;
import com.unboundid.scim2.common.filters.Filter;
import com.unboundid.scim2.common.messages.ListResponse;
import com.unboundid.scim2.common.types.UserResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
// SCIM verlangt zwingend diesen Content-Type für alle Antworten
@RequestMapping(value = "/scim/v2/Users", produces = "application/scim+json")
public class ScimUserController {

    @GetMapping
    public ResponseEntity<?> searchUsers(
            @RequestParam(required = false) String filter,
            @RequestParam(required = false, defaultValue = "1") Integer startIndex,
            @RequestParam(required = false, defaultValue = "100") Integer count) {

        System.out.println("GET Request empfangen. Filter-String: " + filter);

        List<UserResource> foundUsers = new ArrayList<>();

        if (filter != null && !filter.isBlank()) {
            try {
                // Der absolute Gamechanger des SDKs: 
                // Aus dem unhandlichen String wird ein fertiger Abstract Syntax Tree (AST)
                Filter parsedFilter = Filter.fromString(filter);
                
                System.out.println("Erfolgreich geparst! Filter-Typ: " + parsedFilter.getFilterType());
                
                // Hier würdest du später das Visitor-Pattern ansetzen,
                // um den parsedFilter in eine saubere JPA- oder SQL-Query zu übersetzen.
                // Beispiel: JpaSpecification spec = parsedFilter.visit(new MyJpaFilterVisitor());
                // foundUsers = userRepository.findAll(spec);
                
            } catch (BadRequestException e) {
                // Wenn das externe System syntaktischen Müll schickt (z.B. vergessene Klammer),
                // fangen wir das hier ab und geben ein 100% standardkonformes SCIM-Fehler-JSON (HTTP 400) zurück.
                return ResponseEntity.badRequest().body(e.getScimError());
            }
        } else {
            
        }

       
        ListResponse<UserResource> response = new ListResponse<>(
                foundUsers,       // Die eigentliche Liste der User-Ressourcen
                foundUsers.size(),// Total Results (wichtig für die Paginierung des Clients)
                startIndex,       // Start Index
                count             // Items per Page
        );

        return ResponseEntity.ok(response);
    }
}
