import com.unboundid.scim2.common.filters.Filter;
import com.unboundid.scim2.common.messages.ListResponse;
import com.unboundid.scim2.common.exceptions.BadRequestException;
// ... die anderen Imports von vorhin

    @GetMapping
    @Operation(summary = "Benutzer suchen", description = "Sucht Benutzer anhand eines komplexen SCIM-Filters.")
    public ResponseEntity<?> searchUsers(
            @Parameter(description = "SCIM Suchfilter, z.B. userName eq \"test\" and active eq true") 
            @RequestParam(required = false) String filter) {
        
        System.out.println("Eingehender Such-Request. Raw-Filter: " + filter);

        try {
            // SCIM-Suchen geben immer eine strukturierte ListResponse zurück, kein nacktes Array
            ListResponse<UserResource> response = new ListResponse<>();

            if (filter != null && !filter.isBlank()) {
                
                // 1. Die Magie des Ping SDKs: Den String parsen und validieren
                Filter parsedFilter = Filter.fromString(filter);
                
                System.out.println("Filter erfolgreich geparst!");
                System.out.println("Interne Repräsentation: " + parsedFilter.toString());
                
                /*
                 * 2. Ab hier übernimmt deine Datenbank-Logik.
                 * Das geparste Filter-Objekt ist jetzt ein sogenannter Abstrakter Syntaxbaum (AST).
                 * Du durchläufst diesen Baum später mit dem "Visitor Pattern", 
                 * um daraus z.B. eine JPA Specification oder einen SQL-String zu bauen.
                 * 
                 * Beispiel für später:
                 * JpaSpecificationVisitor visitor = new JpaSpecificationVisitor();
                 * Specification<UserEntity> spec = parsedFilter.visit(visitor);
                 * List<UserEntity> dbUsers = userRepository.findAll(spec);
                 */
            } else {
                System.out.println("Kein Filter übergeben. Lade alle User (mit Paginierung).");
                // Logik für findAll()
            }

            // Dummy-Daten für den Prototypen
            response.setTotalResults(0); 
            response.setItemsPerPage(0);
            response.setStartIndex(1);

            return ResponseEntity.ok(response);

        } catch (BadRequestException e) {
            // Das SDK wirft automatisch eine Exception, wenn die Syntax falsch ist 
            // (z. B. eine fehlende schließende Klammer im Filter-String).
            System.err.println("Das UEM hat einen fehlerhaften Filter geschickt: " + e.getMessage());
            
            // Für den Prototypen reicht ein nackter String. 
            // In Produktion baut man hieraus ein ErrorResponse-Objekt aus dem SDK.
            return ResponseEntity.badRequest().body("Syntax-Fehler im Filter: " + e.getMessage());
        }
    }
