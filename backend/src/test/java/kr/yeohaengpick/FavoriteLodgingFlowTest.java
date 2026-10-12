package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.*;
import java.net.http.*;
import java.util.*;
import kr.yeohaengpick.catalog.*;
import kr.yeohaengpick.common.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 실제 HTTP/세션/CSRF/DB를 통과합니다. 공급자 결과만 제어해 일반 후보 밖의 숙소와 재조회 실패를 재현합니다. */
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "spring.datasource.url=jdbc:h2:mem:flows;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.sample-data=true",
    "app.kakao-rest-key=",
    "app.gemini-key=",
    "app.mail-enabled=false",
    "app.tour-api-key=",
  }
)
class FavoriteLodgingFlowTest extends HttpFlowDatabase {

  @LocalServerPort
  int port;

  @Autowired
  JsonCodec json;

  @Autowired
  JdbcTemplate db;

  @Autowired
  kr.yeohaengpick.auth.AuthRateLimit rateLimit;

  @MockitoSpyBean
  CatalogService catalog;

  @BeforeEach
  void resetBudget() {
    (
      (Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(
        rateLimit,
        "attempts"
      )
    ).clear();
  }

  Place hotel() {
    return catalog.search("서울", "", "숙소").getFirst();
  }

  Map<String, Object> lookup(Place p) {
    return Map.of("placeId", p.id(), "region", p.region());
  }

  void favorite(Browser user, Place p) throws Exception {
    user.call(
      "POST",
      "/favorites",
      Map.of(
        "placeId",
        p.id(),
        "region",
        p.region(),
        "query",
        "",
        "category",
        "전체"
      )
    );
  }

  Map<String, Object> conditions(List<String> ids, List<String> favorites) {
    var c = new HashMap<String, Object>();
    c.put("title", "찜 숙소 동선 검증");
    c.put("regions", List.of("서울"));
    c.put("startDate", "2026-11-01");
    c.put("endDate", "2026-11-02");
    c.put("origin", "서울역");
    c.put("partySize", 2);
    c.put("budget", 600000);
    c.put("transport", "TRANSIT");
    c.put("preferences", List.of());
    c.put("lodging", "");
    c.put("lodgingIds", ids);
    c.put("favoriteLodgingIds", favorites);
    return c;
  }

  Map<String, Object> selected(Place p) {
    return conditions(List.of(p.id()), List.of(p.id()));
  }

  List<?> courseItems(Browser user, Map<String, Object> c) throws Exception {
    var r = user.send("POST", "/recommendations", c, true);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    var courses = json.mapper().readTree(r.body());
    assertThat(courses.size()).isEqualTo(3);
    return json.read(courses.get(0).get("items").toString(), List.class);
  }

  void status(Browser user, String path, Object body, int expected)
    throws Exception {
    var r = user.send("POST", path, body, true);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
  }

  @Test
  void lookupRequiresSessionCsrfOwnershipAndValidHotelRegion()
    throws Exception {
    var p = hotel();
    var guest = new Browser();
    assertThat(
      guest
        .send("POST", "/favorites/lodgings/resolve", lookup(p), true)
        .statusCode()
    ).isEqualTo(401);
    var owner = new Browser().signup();
    var other = new Browser().signup();
    favorite(owner, p);
    assertThat(
      owner
        .send("POST", "/favorites/lodgings/resolve", lookup(p), false)
        .statusCode()
    ).isEqualTo(403);
    status(other, "/favorites/lodgings/resolve", lookup(p), 409);
    status(
      owner,
      "/favorites/lodgings/resolve",
      Map.of("placeId", p.id(), "region", "부산"),
      400
    );
    status(
      owner,
      "/favorites/lodgings/resolve",
      Map.of("placeId", "x".repeat(101), "region", "서울"),
      400
    );
    status(
      owner,
      "/favorites/lodgings/resolve",
      Map.of("placeId", "", "region", "서울"),
      400
    );
    var visit = catalog
      .search("서울", "")
      .stream()
      .filter(x -> !x.category().equals("숙소"))
      .findFirst()
      .orElseThrow();
    favorite(owner, visit);
    status(owner, "/favorites/lodgings/resolve", lookup(visit), 400);
    var response = owner.call("POST", "/favorites/lodgings/resolve", lookup(p));
    assertThat(response).containsOnlyKeys("place", "checkedAt");
    assertThat(response.get("checkedAt").toString()).isNotBlank();
  }

  @Test
  void freshMetadataBecomesLodgingWithoutClaimingReservationOrSharingPrivateFlags()
    throws Exception {
    var owner = new Browser().signup();
    var p = hotel();
    favorite(owner, p);
    // 과거 스냅샷의 좌표/금액을 바꿔도 재조회한 공급자 정보만 일정에 사용해야 합니다. 작은 JSON 바인딩은 양 DB에서 동일합니다.
    var stale = new Place(
      p.id(),
      p.name(),
      p.region(),
      p.category(),
      1,
      2,
      p.duration(),
      1,
      p.indoor(),
      p.source(),
      p.url()
    );
    db.update(
      "UPDATE favorite_places SET place_json=? WHERE member_id=? AND place_id=?",
      json.write(stale),
      owner.me(),
      p.id()
    );
    var checked = owner.call("POST", "/favorites/lodgings/resolve", lookup(p));
    assertThat(
      json.mapper().valueToTree(checked.get("place")).get("latitude").asDouble()
    ).isEqualTo(p.latitude());
    var c = selected(p);
    var r = owner.send("POST", "/recommendations", c, true);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    for (var course : json.mapper().readTree(r.body())) {
      assertThat(course.get("lodgings").size()).isEqualTo(1);
      assertThat(course.get("lodgings").get(0).get("cost").asInt()).isEqualTo(
        p.cost()
      );
      for (var item : course.get("items"))
        assertThat(item.get("place").get("category").asText()).isNotEqualTo(
          "숙소"
        );
    }
    var trip = owner.call(
      "POST",
      "/trips",
      Map.of("conditions", c, "items", courseItems(owner, c))
    );
    var tree = json.mapper().valueToTree(trip);
    assertThat(tree.has("favoriteLodgingIds")).isFalse();
    assertThat(tree.has("favoriteIds")).isFalse();
    var lodging = tree.get("lodgings").get(0);
    assertThat(lodging.get("nightlyEstimate").asInt()).isEqualTo(p.cost());
    assertThat(lodging.get("bookingStatus").asText()).isEqualTo("NONE");
    for (var item : tree.get("items"))
      assertThat(item.get("fixed").asBoolean()).isFalse();
    String token = owner
      .call(
        "POST",
        "/trips/" + trip.get("id") + "/share",
        Map.of("enabled", true)
      )
      .get("token")
      .toString();
    var publicView = new Browser().call("GET", "/share/" + token, null);
    assertThat(publicView).doesNotContainKeys(
      "lodgings",
      "favoriteLodgingIds",
      "favoriteIds"
    );
  }

  @Test
  void onlyOwnRecheckedHotelCanExpandGeneralCandidateList() throws Exception {
    var owner = new Browser().signup();
    var p = hotel();
    favorite(owner, p);
    var narrowed = catalog
      .candidates(List.of("서울"))
      .stream()
      .filter(x -> !x.id().equals(p.id()))
      .toList();
    doReturn(narrowed).when(catalog).candidates(List.of("서울"));
    var c = selected(p);
    var items = courseItems(owner, c);
    owner.call("POST", "/trips", Map.of("conditions", c, "items", items));
    status(
      owner,
      "/recommendations",
      conditions(List.of(p.id()), List.of()),
      400
    );
    var other = new Browser().signup();
    status(other, "/recommendations", c, 409);
    status(other, "/trips", Map.of("conditions", c, "items", items), 409);
  }

  @Test
  void invalidSelectionFailsAndExistingGeneralSelectionRemainsCompatible()
    throws Exception {
    var owner = new Browser().signup();
    var p = hotel();
    favorite(owner, p);
    status(
      owner,
      "/recommendations",
      conditions(List.of(), List.of(p.id())),
      400
    );
    status(
      owner,
      "/recommendations",
      conditions(List.of(p.id()), List.of(p.id(), p.id())),
      400
    );
    var second = catalog.search("서울", "", "숙소").get(1);
    favorite(owner, second);
    status(
      owner,
      "/recommendations",
      conditions(List.of(p.id(), second.id()), List.of(p.id(), second.id())),
      400
    );
    var busan = catalog.search("부산", "", "숙소").getFirst();
    favorite(owner, busan);
    status(owner, "/recommendations", selected(busan), 400);
    var multiRegion = conditions(
      List.of(p.id(), busan.id()),
      List.of(p.id(), busan.id())
    );
    multiRegion.put("regions", List.of("서울", "부산"));
    var multiResponse = owner.send(
      "POST",
      "/recommendations",
      multiRegion,
      true
    );
    assertThat(multiResponse.statusCode())
      .as(multiResponse.body())
      .isEqualTo(200);
    for (var course : json.mapper().readTree(multiResponse.body()))
      assertThat(course.get("lodgings").size()).isEqualTo(2);
    var visit = catalog
      .search("서울", "")
      .stream()
      .filter(x -> !x.category().equals("숙소"))
      .findFirst()
      .orElseThrow();
    favorite(owner, visit);
    status(owner, "/recommendations", selected(visit), 400);
    var oldContract = conditions(List.of(p.id()), List.of());
    oldContract.remove("favoriteLodgingIds");
    courseItems(owner, oldContract);
    var mixed = conditions(List.of(p.id(), second.id()), List.of(p.id()));
    status(owner, "/recommendations", mixed, 400);
    // 공유 가져오기는 원본의 공개 방문 일정만 복사하므로 별도 찜 숙소 삽입을 허용하지 않습니다.
    var trip = owner.call(
      "POST",
      "/trips",
      Map.of(
        "conditions",
        oldContract,
        "items",
        courseItems(owner, oldContract)
      )
    );
    String token = owner
      .call(
        "POST",
        "/trips/" + trip.get("id") + "/share",
        Map.of("enabled", true)
      )
      .get("token")
      .toString();
    var publicView = new Browser().call("GET", "/share/" + token, null);
    var importConditions = conditions(List.of(), List.of(p.id()));
    status(
      owner,
      "/trips/import",
      Map.of(
        "token",
        token,
        "snapshot",
        publicView.get("snapshot"),
        "requestId",
        UUID.randomUUID().toString(),
        "conditions",
        importConditions
      ),
      400
    );
  }

  @Test
  void removingFavoriteRejectsNewRecommendationAndSaveButPreservesExistingTrip()
    throws Exception {
    var owner = new Browser().signup();
    var p = hotel();
    favorite(owner, p);
    var c = selected(p);
    owner.call("POST", "/favorites/lodgings/resolve", lookup(p));
    var items = courseItems(owner, c);
    var trip = owner.call(
      "POST",
      "/trips",
      Map.of("conditions", c, "items", items)
    );
    owner.call("DELETE", "/favorites/" + p.id(), null);
    status(owner, "/favorites/lodgings/resolve", lookup(p), 409);
    status(owner, "/recommendations", c, 409);
    status(owner, "/trips", Map.of("conditions", c, "items", items), 409);
    var unchanged = owner.call("GET", "/trips/" + trip.get("id"), null);
    assertThat(
      json
        .mapper()
        .valueToTree(unchanged)
        .get("lodgings")
        .get(0)
        .get("placeId")
        .asText()
    ).isEqualTo(p.id());
  }

  @Test
  void missingReclassifiedOrFailedProviderNeverUsesSavedSnapshot()
    throws Exception {
    var owner = new Browser().signup();
    var p = hotel();
    favorite(owner, p);
    var c = selected(p);
    var items = courseItems(owner, c);
    int before = db.queryForObject(
      "SELECT COUNT(*) FROM trips WHERE owner_id=?",
      Integer.class,
      owner.me()
    );
    doReturn(List.of()).when(catalog).search(p.region(), p.name());
    status(owner, "/favorites/lodgings/resolve", lookup(p), 409);
    status(owner, "/recommendations", c, 409);
    status(owner, "/trips", Map.of("conditions", c, "items", items), 409);
    var reclassified = new Place(
      p.id(),
      p.name(),
      p.region(),
      "카페",
      p.latitude(),
      p.longitude(),
      p.duration(),
      p.cost(),
      true,
      p.source(),
      p.url()
    );
    doReturn(List.of(reclassified)).when(catalog).search(p.region(), p.name());
    status(owner, "/favorites/lodgings/resolve", lookup(p), 409);
    doThrow(new DomainException(503, "검증용 공급자 장애"))
      .when(catalog)
      .search(p.region(), p.name());
    status(owner, "/favorites/lodgings/resolve", lookup(p), 503);
    status(owner, "/recommendations", c, 503);
    status(owner, "/trips", Map.of("conditions", c, "items", items), 503);
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM trips WHERE owner_id=?",
        Integer.class,
        owner.me()
      )
    ).isEqualTo(before);
  }

  class Browser {

    final HttpClient client = HttpClient.newBuilder()
      .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
      .build();

    HttpResponse<String> send(
      String method,
      String path,
      Object body,
      boolean csrf
    ) throws Exception {
      var b = HttpRequest.newBuilder(
        URI.create("http://127.0.0.1:" + port + "/api" + path)
      ).header("Content-Type", "application/json");
      if (csrf) b.header(
        "X-XSRF-TOKEN",
        call("GET", "/auth/csrf", null).get("token").toString()
      );
      return client.send(
        b
          .method(
            method,
            body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(json.write(body))
          )
          .build(),
        HttpResponse.BodyHandlers.ofString()
      );
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> call(String method, String path, Object body)
      throws Exception {
      var r = send(method, path, body, !method.equals("GET"));
      assertThat(r.statusCode()).as(r.body()).isBetween(200, 299);
      return r.body().startsWith("{")
        ? json.read(r.body(), Map.class)
        : Map.of();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> array(String path) throws Exception {
      var r = send("GET", path, null, false);
      assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
      return json.read(r.body(), List.class);
    }

    String me() throws Exception {
      return call("GET", "/auth/me", null).get("id").toString();
    }

    Browser signup() throws Exception {
      String email = UUID.randomUUID() + "@example.test";
      call(
        "POST",
        "/auth/register",
        Map.of(
          "email",
          email,
          "password",
          "Test-only-Password12!",
          "nickname",
          "찜 검증 여행자"
        )
      );
      call(
        "POST",
        "/auth/login",
        Map.of("email", email, "password", "Test-only-Password12!")
      );
      return this;
    }
  }
}
