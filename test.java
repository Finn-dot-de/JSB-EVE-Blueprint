@RestClientTest(OigScimClient.class)
@Import(OigClientConfiguration.class)
@TestPropertySource(properties = {
        "oig.base-url=" + OigScimClientUserQueryTest.BASE_URL,
        "oig.username=xelsysadm",
        "oig.password=geheim"
})
class OigScimClientUserQueryTest {

    static final String BASE_URL = "https://oig.example/iam/governance/scim/v1";
    private static final MediaType SCIM_JSON = MediaType.parseMediaType("application/scim+json");

    @Autowired
    private MockRestServiceServer oig;

    @Autowired
    private OigScimClient client;

    @AfterEach
    void alleErwartungenErfuellt() {
        oig.verify();
    }

    private static String user(String id, String userName, String givenName, String familyName) {
        return """
                {
                  "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
                  "id": "%s",
                  "userName": "%s",
                  "name": { "givenName": "%s", "familyName": "%s" },
                  "active": true,
                  "meta": { "resourceType": "User", "location": "%s/Users/%s" }
                }""".formatted(id, userName, givenName, familyName, BASE_URL, id);
    }

    private static String listResponse(int total, String... resources) {
        return """
                {
                  "schemas": ["urn:ietf:params:scim:api:messages:2.0:ListResponse"],
                  "totalResults": %d,
                  "startIndex": 1,
                  "itemsPerPage": %d,
                  "Resources": [ %s ]
                }""".formatted(total, resources.length, String.join(",", resources));
    }

    private static final String MARIA = user("4711", "m.schneider@example.de", "Maria", "Schneider");
    private static final String THOMAS = user("4712", "t.weber@example.de", "Thomas", "Weber");

    @Test
    @DisplayName("Get User details by userid")
    void getUserById() {
        oig.expect(requestTo(BASE_URL + "/Users/4711"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(MARIA, SCIM_JSON));

        UserResource user = client.get(USER, UserResource.class, "4711");

        assertThat(user.getId()).isEqualTo("4711");
        assertThat(user.getUserName()).isEqualTo("m.schneider@example.de");
        assertThat(user.getName().getGivenName()).isEqualTo("Maria");
    }

    @Test
    @DisplayName("Get User Details by userName")
    void getUserByUserName() {
        String filter = "userName eq \"m.schneider@example.de\"";

        oig.expect(method(HttpMethod.GET))
                .andExpect(request -> assertThat(request.getURI().getPath()).endsWith("/Users"))
                .andExpect(request -> assertThat(request.getURI().getQuery()).contains("filter=" + filter))
                .andExpect(queryParam("count", "1"))
                .andRespond(withSuccess(listResponse(1, MARIA), SCIM_JSON));

        ListResponse<UserResource> ergebnis = client.search(USER, UserResource.class, filter, 1, 1);

        assertThat(ergebnis.getTotalResults()).isEqualTo(1);
        assertThat(ergebnis.getResources()).singleElement()
                .satisfies(u -> assertThat(u.getUserName()).isEqualTo("m.schneider@example.de"));
    }

    @Test
    @DisplayName("Get User details by search criteria")
    void getUsersBySearchCriteria() {
        String filter = "name.familyName sw \"Sch\" and active eq true";

        oig.expect(method(HttpMethod.GET))
                .andExpect(request -> assertThat(request.getURI().getQuery()).contains("filter=" + filter))
                .andRespond(withSuccess(listResponse(1, MARIA), SCIM_JSON));

        ListResponse<UserResource> ergebnis = client.search(USER, UserResource.class, filter, 1, 100);

        assertThat(ergebnis.getResources())
                .extracting(UserResource::getName)
                .extracting(n -> n.getFamilyName())
                .containsExactly("Schneider");
    }

    @Test
    @DisplayName("Search User")
    void searchUsers() {
        oig.expect(method(HttpMethod.GET))
                .andExpect(request -> assertThat(request.getURI().getPath()).endsWith("/Users"))
                .andExpect(request -> assertThat(request.getURI().getQuery()).doesNotContain("filter"))
                .andExpect(queryParam("startIndex", "1"))
                .andExpect(queryParam("count", "10"))
                .andRespond(withSuccess(listResponse(2, MARIA, THOMAS), SCIM_JSON));

        ListResponse<UserResource> ergebnis = client.search(USER, UserResource.class, null, 1, 10);

        assertThat(ergebnis.getTotalResults()).isEqualTo(2);
        assertThat(ergebnis.getResources())
                .extracting(UserResource::getId)
                .containsExactly("4711", "4712");
    }
}
