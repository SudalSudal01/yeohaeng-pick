package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import kr.yeohaengpick.catalog.*;
import kr.yeohaengpick.common.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 저장한 여행의 방문 장소를 개인 찜으로 바꿀 때 실제 HTTP, 세션, CSRF, DB 트랜잭션을 모두 통과해 검증합니다.
 * 개인 찜의 보관 스냅샷은 화면 목록용일 뿐이며 비교와 적용에는 현재 공급자 응답만 사용합니다.
 */
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
class FavoriteVisitComparisonFlowTest extends HttpFlowDatabase {

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
    // 모든 HTTP 테스트가 같은 루프백 IP를 사용하므로 시나리오별 가입 제한 메모리만 격리합니다.
    (
      (Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(
        rateLimit,
        "attempts"
      )
    ).clear();
  }

  final Map<String, Object> conditions = Map.ofEntries(
    Map.entry("title", "방문 찜 비교 검증"),
    Map.entry("regions", List.of("서울")),
    Map.entry("startDate", "2026-11-01"),
    Map.entry("endDate", "2026-11-01"),
    Map.entry("origin", "서울역"),
    Map.entry("partySize", 2),
    Map.entry("budget", 300000),
    Map.entry("transport", "TRANSIT"),
    Map.entry("preferences", List.of("전시", "맛집")),
    Map.entry("lodging", "")
  );

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> items(Map<String, Object> trip) {
    return (List<Map<String, Object>>) trip.get("items");
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> changedItem(Object response, String itemId) {
    var tree = json.mapper().valueToTree(response).path("items");
    for (var item : tree)
      if (item.path("id").asText().equals(itemId)) return json.read(
        item.toString(),
        Map.class
      );
    throw new AssertionError(
      "비교 결과에서 대상 일정을 찾지 못했습니다: " + itemId
    );
  }

  Place place(String id) {
    return catalog
      .examples()
      .stream()
      .filter(p -> p.id().equals(id))
      .findFirst()
      .orElseThrow();
  }

  Place unused(Map<String, Object> trip, Predicate<Place> condition) {
    Set<String> used = new HashSet<>(
      items(trip)
        .stream()
        .map(i -> i.get("placeId").toString())
        .toList()
    );
    return catalog
      .examples()
      .stream()
      .filter(p -> p.region().equals("서울"))
      .filter(p -> !p.category().equals("숙소"))
      .filter(p -> !used.contains(p.id()))
      .filter(condition)
      .findFirst()
      .orElseThrow();
  }

  void favorite(Browser user, Place place) throws Exception {
    user.call(
      "POST",
      "/favorites",
      Map.of(
        "placeId",
        place.id(),
        "region",
        place.region(),
        "query",
        place.name(),
        "category",
        "전체"
      )
    );
  }

  Map<String, Object> change(
    Map<String, Object> target,
    Place place,
    Object version,
    boolean favorite,
    boolean rain
  ) {
    return new HashMap<>(
      Map.of(
        "itemId",
        target.get("id"),
        "placeId",
        place.id(),
        "version",
        version,
        "favorite",
        favorite,
        "rain",
        rain
      )
    );
  }

  void status(Browser user, String path, Object body, int expected)
    throws Exception {
    var response = user.send("POST", path, body, true);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
  }

  @Test
  void favoriteOutsideGeneralCandidatesUsesFreshMetadataAndStoresNoPrivateFlag()
    throws Exception {
    var owner = new Browser().signup();
    var trip = owner.trip();
    String base = "/trips/" + trip.get("id");
    var target = items(trip).getFirst();

    // favorite/rain을 보내지 않는 기존 일반 후보 요청도 계속 비교할 수 있어야 합니다.
    var alternatives = owner.array(base + "/alternatives/" + target.get("id"));
    var oldRequest = Map.of(
      "itemId",
      target.get("id"),
      "placeId",
      alternatives.getFirst().get("id"),
      "version",
      trip.get("version")
    );
    assertThat(
      owner.send("POST", base + "/preview", oldRequest, true).statusCode()
    ).isEqualTo(200);

    int oldDuration = ((Number) target.get("duration")).intValue();
    var selected = unused(trip, p -> p.duration() <= oldDuration);
    favorite(owner, selected);
    var stale = new Place(
      selected.id(),
      selected.name(),
      selected.region(),
      selected.category(),
      1,
      2,
      1,
      999999,
      selected.indoor(),
      selected.source(),
      selected.url()
    );
    db.update(
      "UPDATE favorite_places SET place_json=? WHERE member_id=? AND place_id=?",
      json.write(stale),
      owner.me(),
      selected.id()
    );

    // 일반 후보에서는 제외해도 같은 ID를 본인 찜으로 현재 재조회하면 비교할 수 있습니다.
    var narrowed = catalog
      .search("서울", "")
      .stream()
      .filter(p -> !p.id().equals(selected.id()))
      .toList();
    doReturn(narrowed).when(catalog).search("서울", "");
    var request = change(target, selected, trip.get("version"), true, false);
    status(
      owner,
      base + "/preview",
      change(target, selected, 0, false, false),
      400
    );
    var preview = owner.call("POST", base + "/preview", request);
    var previewItem = changedItem(preview, target.get("id").toString());
    assertThat(preview.get("canApply")).isEqualTo(true);
    assertThat(((Number) previewItem.get("latitude")).doubleValue()).isEqualTo(
      selected.latitude()
    );
    assertThat(previewItem.get("cost")).isEqualTo(selected.cost());
    assertThat(previewItem.get("placeName")).isEqualTo(selected.name());
    assertThat(owner.call("GET", base, null).get("items")).isEqualTo(
      trip.get("items")
    );

    var applied = owner.call("POST", base + "/apply", request);
    var savedItem = changedItem(applied, target.get("id").toString());
    assertThat(savedItem.get("placeId")).isEqualTo(selected.id());
    assertThat(savedItem.get("cost")).isEqualTo(selected.cost());
    assertThat(json.write(applied)).doesNotContain("\"favorite\"", "\"rain\"");
    var snapshots = db.query(
      "SELECT snapshot FROM trip_history WHERE trip_id=?",
      (rs, row) -> rs.getString(1),
      trip.get("id")
    );
    assertThat(snapshots).allSatisfy(snapshot ->
      assertThat(snapshot).doesNotContain("\"favorite\"", "\"rain\"")
    );
    String token = owner
      .call("POST", base + "/share", Map.of("enabled", true))
      .get("token")
      .toString();
    var shared = new Browser().call("GET", "/share/" + token, null);
    assertThat(json.write(shared)).doesNotContain("\"favorite\"", "\"rain\"");
  }

  @Test
  void sessionCsrfAndAcceptedCompanionStillUseEachMembersOwnFavorite()
    throws Exception {
    var owner = new Browser().signup();
    var companion = new Browser().signup();
    var outsider = new Browser().signup();
    var trip = owner.trip();
    String base = "/trips/" + trip.get("id");
    var target = items(trip).getFirst();
    var selected = unused(
      trip,
      p -> p.duration() <= ((Number) target.get("duration")).intValue()
    );
    favorite(owner, selected);
    var request = change(target, selected, trip.get("version"), true, false);

    assertThat(
      new Browser().send("POST", base + "/preview", request, true).statusCode()
    ).isEqualTo(401);
    for (String action : List.of("preview", "apply")) {
      assertThat(
        owner.send("POST", base + "/" + action, request, false).statusCode()
      ).isEqualTo(403);
      status(outsider, base + "/" + action, request, 403);
    }

    owner.call(
      "POST",
      "/invitations",
      Map.of("tripId", trip.get("id"), "email", companion.email)
    );
    status(companion, base + "/preview", request, 403);
    var invite = companion.array("/invitations").getFirst();
    companion.call(
      "POST",
      "/invitations/" + invite.get("id") + "/respond",
      Map.of("accept", true)
    );
    Object version = owner.call("GET", base, null).get("version");
    request.put("version", version);
    // 같은 여행의 동행이어도 소유자의 개인 찜을 대신 사용할 수 없습니다.
    status(companion, base + "/preview", request, 409);
    owner.call("POST", base + "/preview", request);

    favorite(companion, selected);
    companion.call("POST", base + "/preview", request);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      var a = pool.submit(() -> {
        gate.await();
        return owner.send("POST", base + "/apply", request, true).statusCode();
      });
      var b = pool.submit(() -> {
        gate.await();
        return companion
          .send("POST", base + "/apply", request, true)
          .statusCode();
      });
      gate.countDown();
      assertThat(
        List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))
      ).containsExactlyInAnyOrder(200, 409);
    }
    assertThat(
      ((Number) owner.call("GET", base, null).get("version")).intValue()
    ).isEqualTo(((Number) version).intValue() + 1);
  }

  @Test
  void applyRechecksRemovedMissingAndFailedProviderWithoutMutation()
    throws Exception {
    var owner = new Browser().signup();
    var trip = owner.trip();
    String base = "/trips/" + trip.get("id");
    var target = items(trip).getFirst();
    var selected = unused(
      trip,
      p -> p.duration() <= ((Number) target.get("duration")).intValue()
    );
    favorite(owner, selected);
    var request = change(target, selected, trip.get("version"), true, false);
    owner.call("POST", base + "/preview", request);
    var before = owner.call("GET", base, null);

    owner.call("DELETE", "/favorites/" + selected.id(), null);
    status(owner, base + "/apply", request, 409);
    assertThat(owner.call("GET", base, null)).isEqualTo(before);

    favorite(owner, selected);
    doReturn(List.of())
      .when(catalog)
      .search(selected.region(), selected.name());
    status(owner, base + "/preview", request, 409);
    status(owner, base + "/apply", request, 409);
    assertThat(owner.call("GET", base, null)).isEqualTo(before);

    doThrow(new DomainException(503, "검증용 공급자 장애"))
      .when(catalog)
      .search(selected.region(), selected.name());
    status(owner, base + "/preview", request, 503);
    status(owner, base + "/apply", request, 503);
    assertThat(owner.call("GET", base, null)).isEqualTo(before);
  }

  @Test
  void duplicateWrongRegionLodgingAndRainCandidatesAreRejected()
    throws Exception {
    var owner = new Browser().signup();
    var trip = owner.trip();
    String base = "/trips/" + trip.get("id");
    var target = items(trip).getFirst();
    var before = owner.call("GET", base, null);

    var scheduled = place(target.get("placeId").toString());
    favorite(owner, scheduled);
    status(
      owner,
      base + "/preview",
      change(target, scheduled, trip.get("version"), true, false),
      400
    );

    var busan = catalog
      .examples()
      .stream()
      .filter(p -> p.region().equals("부산") && !p.category().equals("숙소"))
      .findFirst()
      .orElseThrow();
    favorite(owner, busan);
    status(
      owner,
      base + "/preview",
      change(target, busan, trip.get("version"), true, false),
      400
    );

    var lodging = catalog
      .examples()
      .stream()
      .filter(p -> p.region().equals("서울") && p.category().equals("숙소"))
      .findFirst()
      .orElseThrow();
    favorite(owner, lodging);
    status(
      owner,
      base + "/apply",
      change(target, lodging, trip.get("version"), true, false),
      400
    );

    var outdoor = unused(trip, p -> !p.indoor());
    favorite(owner, outdoor);
    status(
      owner,
      base + "/preview",
      change(target, outdoor, trip.get("version"), true, true),
      400
    );
    var rainy = owner.array(
      base + "/alternatives/" + target.get("id") + "?rain=true"
    );
    assertThat(rainy).allSatisfy(p ->
      assertThat(p.get("indoor")).isEqualTo(true)
    );
    var dry = owner.array(
      base + "/alternatives/" + target.get("id") + "?rain=false"
    );
    var manipulatedOutdoor = dry
      .stream()
      .filter(p -> Boolean.FALSE.equals(p.get("indoor")))
      .findFirst()
      .orElseThrow();
    var forgedRain = new HashMap<String, Object>();
    forgedRain.put("itemId", target.get("id"));
    forgedRain.put("placeId", manipulatedOutdoor.get("id"));
    forgedRain.put("version", trip.get("version"));
    forgedRain.put("rain", true);
    status(owner, base + "/preview", forgedRain, 400);

    var negative = change(target, outdoor, -1, true, false);
    status(owner, base + "/preview", negative, 400);
    assertThat(owner.call("GET", base, null)).isEqualTo(before);
  }

  @Test
  void staleVersionAndFixedOrConfirmedVisitsCannotBeChanged() throws Exception {
    var owner = new Browser().signup();
    var trip = owner.trip();
    String base = "/trips/" + trip.get("id");
    var first = items(trip).get(0);
    var second = items(trip).get(1);
    var selected = unused(trip, p -> true);
    favorite(owner, selected);

    status(
      owner,
      base + "/preview",
      change(first, selected, 1, true, false),
      409
    );
    owner.call(
      "PUT",
      base + "/booking",
      Map.of(
        "itemId",
        first.get("id"),
        "status",
        "USER_CONFIRMED",
        "reason",
        "실제 예약 완료 보호",
        "fixed",
        false,
        "version",
        0
      )
    );
    var confirmed = change(first, selected, 1, true, false);
    status(owner, base + "/preview", confirmed, 409);
    status(owner, base + "/apply", confirmed, 409);

    owner.call(
      "PUT",
      base + "/booking",
      Map.of(
        "itemId",
        second.get("id"),
        "status",
        "NEEDS_CONFIRMATION",
        "reason",
        "시간 고정 보호",
        "fixed",
        true,
        "version",
        1
      )
    );
    var fixed = change(second, selected, 2, true, false);
    status(owner, base + "/preview", fixed, 409);
    status(owner, base + "/apply", fixed, 409);
    var saved = owner.call("GET", base, null);
    assertThat(saved.get("version")).isEqualTo(2);
    assertThat(
      changedItem(saved, first.get("id").toString()).get("placeId")
    ).isEqualTo(first.get("placeId"));
    assertThat(
      changedItem(saved, second.get("id").toString()).get("placeId")
    ).isEqualTo(second.get("placeId"));
  }

  class Browser {

    final String email = UUID.randomUUID() + "@example.test";
    final HttpClient client = HttpClient.newBuilder()
      .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
      .build();

    HttpResponse<String> send(
      String method,
      String path,
      Object body,
      boolean csrf
    ) throws Exception {
      var request = HttpRequest.newBuilder(
        URI.create("http://127.0.0.1:" + port + "/api" + path)
      ).header("Content-Type", "application/json");
      if (csrf) request.header(
        "X-XSRF-TOKEN",
        call("GET", "/auth/csrf", null).get("token").toString()
      );
      return client.send(
        request
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
      var response = send(method, path, body, !method.equals("GET"));
      assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
      return response.body().startsWith("{")
        ? json.read(response.body(), Map.class)
        : Map.of();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> array(String path) throws Exception {
      var response = send("GET", path, null, false);
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      return json.read(response.body(), List.class);
    }

    String me() throws Exception {
      return call("GET", "/auth/me", null).get("id").toString();
    }

    Browser signup() throws Exception {
      call(
        "POST",
        "/auth/register",
        Map.of(
          "email",
          email,
          "password",
          "Test-only-Password12!",
          "nickname",
          "방문 찜 검증 여행자"
        )
      );
      call(
        "POST",
        "/auth/login",
        Map.of("email", email, "password", "Test-only-Password12!")
      );
      return this;
    }

    Map<String, Object> trip() throws Exception {
      var response = send("POST", "/recommendations", conditions, true);
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      var courses = json.mapper().readTree(response.body());
      Object stops = json.read(
        courses.get(1).path("items").toString(),
        List.class
      );
      return call(
        "POST",
        "/trips",
        Map.of("conditions", conditions, "items", stops)
      );
    }
  }
}
