package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import kr.yeohaengpick.common.JsonCodec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** 세션·CSRF·바인딩 SQL을 통과해 개인 찜 권한, 재조회, 중복/상한 경합과 탈퇴 정리를 검증합니다. */
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
class FavoriteFlowTest extends HttpFlowDatabase {

  @LocalServerPort
  int port;

  @Autowired
  JsonCodec json;

  @Autowired
  JdbcTemplate db;

  @Autowired
  kr.yeohaengpick.auth.AuthRateLimit rateLimit;

  @BeforeEach
  void resetBudget() {
    (
      (Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(
        rateLimit,
        "attempts"
      )
    ).clear();
  }

  List<Map<String, Object>> places(String region) throws Exception {
    return new Browser().array(
      "/catalog/places?region=" +
        URLEncoder.encode(region, java.nio.charset.StandardCharsets.UTF_8)
    );
  }

  Map<String, Object> saveInput(Map<String, Object> p) {
    return Map.of(
      "placeId",
      p.get("id"),
      "region",
      p.get("region"),
      "query",
      "",
      "category",
      "전체"
    );
  }

  Map<String, Object> conditions(List<String> favorites) {
    var c = new HashMap<String, Object>();
    c.put("title", "찜 추천 검증");
    c.put("regions", List.of("서울"));
    c.put("startDate", "2026-11-01");
    c.put("endDate", "2026-11-01");
    c.put("origin", "서울역");
    c.put("partySize", 2);
    c.put("budget", 300000);
    c.put("transport", "TRANSIT");
    c.put("preferences", List.of());
    c.put("lodging", "");
    c.put("lodgingIds", List.of());
    c.put("favoriteIds", favorites);
    return c;
  }

  @Test
  void authenticationCsrfAndServerSearchAreRequired() throws Exception {
    var anonymous = new Browser();
    var p = places("서울").getFirst();
    assertThat(
      anonymous.send("GET", "/favorites", null, false).statusCode()
    ).isEqualTo(401);
    assertThat(
      anonymous.send("POST", "/favorites", saveInput(p), true).statusCode()
    ).isEqualTo(401);
    var user = new Browser().signup();
    assertThat(
      user.send("POST", "/favorites", saveInput(p), false).statusCode()
    ).isEqualTo(403);
    assertThat(
      user.send("DELETE", "/favorites/" + p.get("id"), null, false).statusCode()
    ).isEqualTo(403);
    assertThat(
      user
        .send(
          "POST",
          "/favorites",
          Map.of(
            "placeId",
            "forged",
            "region",
            "서울",
            "query",
            "",
            "category",
            "전체"
          ),
          true
        )
        .statusCode()
    ).isEqualTo(409);
    assertThat(
      user
        .send(
          "POST",
          "/favorites",
          Map.of(
            "placeId",
            p.get("id"),
            "region",
            "부산",
            "query",
            "",
            "category",
            "전체"
          ),
          true
        )
        .statusCode()
    ).isEqualTo(409);
    assertThat(
      user
        .send(
          "POST",
          "/favorites",
          Map.of(
            "placeId",
            p.get("id"),
            "region",
            "서울",
            "query",
            "x".repeat(101),
            "category",
            "전체"
          ),
          true
        )
        .statusCode()
    ).isEqualTo(400);
    assertThat(user.array("/favorites")).isEmpty();
  }

  @Test
  void privateIdempotentAndConcurrentSavingDoesNotDuplicate() throws Exception {
    var user = new Browser().signup();
    var other = new Browser().signup();
    var p = places("서울").getFirst();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      var a = pool.submit(() -> {
        gate.await();
        return user.send("POST", "/favorites", saveInput(p), true);
      });
      var b = pool.submit(() -> {
        gate.await();
        return user.send("POST", "/favorites", saveInput(p), true);
      });
      gate.countDown();
      assertThat(a.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
      assertThat(b.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }
    var first = user.array("/favorites");
    assertThat(first).hasSize(1);
    assertThat(first.getFirst()).containsOnlyKeys("place", "savedAt");
    assertThat(first.getFirst().get("place")).isEqualTo(p);
    user.call("POST", "/favorites", saveInput(p));
    assertThat(user.array("/favorites")).isEqualTo(first);
    assertThat(other.array("/favorites")).isEmpty();
    other.call("DELETE", "/favorites/" + p.get("id"), null);
    assertThat(user.array("/favorites")).hasSize(1);
    user.call("DELETE", "/favorites/" + p.get("id"), null);
    user.call("DELETE", "/favorites/" + p.get("id"), null);
    assertThat(user.array("/favorites")).isEmpty();
  }

  @Test
  void recommendationsRequeryPlacesAndSaveTrustedMetadataWithoutSharingFavorites()
    throws Exception {
    var user = new Browser().signup();
    var p = places("서울")
      .stream()
      .filter(x -> "남산서울타워".equals(x.get("name")))
      .findFirst()
      .orElseThrow();
    user.call("POST", "/favorites", saveInput(p));
    // 보관 당시 금액/좌표가 낡아도 현재 검색 값을 추천/저장에 사용해야 합니다.
    var old = new HashMap<>(p);
    old.put("cost", 999999);
    old.put("latitude", 1.0);
    db.update(
      "UPDATE favorite_places SET place_json=? WHERE member_id=?",
      json.write(old),
      user.me()
    );
    var c = conditions(List.of(p.get("id").toString()));
    var response = user.send("POST", "/recommendations", c, true);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    var courses = json.mapper().readTree(response.body());
    for (var course : courses) {
      assertThat(course.get("warnings").toString()).contains("찜 1곳 중 1곳");
      var match = new ArrayList<tools.jackson.databind.JsonNode>();
      course.get("items").forEach(s -> {
        if (s.get("place").get("id").asText().equals(p.get("id"))) match.add(
          s.get("place")
        );
      });
      assertThat(match).hasSize(1);
      assertThat(match.getFirst().get("cost").asInt()).isEqualTo(21000);
      assertThat(match.getFirst().get("latitude").asDouble()).isEqualTo(
        ((Number) p.get("latitude")).doubleValue()
      );
    }
    var items = json.read(courses.get(0).get("items").toString(), List.class);
    var trip = user.call(
      "POST",
      "/trips",
      Map.of("conditions", c, "items", items)
    );
    assertThat(trip).doesNotContainKeys("favoriteIds", "favorites");
    var token = user
      .call(
        "POST",
        "/trips/" + trip.get("id") + "/share",
        Map.of("enabled", true)
      )
      .get("token");
    var shared = new Browser().call("GET", "/share/" + token, null);
    assertThat(shared).doesNotContainKeys("favoriteIds", "favorites");
    user.call("DELETE", "/favorites/" + p.get("id"), null);
    // 개인 찜 해제는 이미 저장한 여행을 바꾸지 않습니다. 반면 오래된 선택으로 새로 저장하는 것은 재검사합니다.
    assertThat(
      user.call("GET", "/trips/" + trip.get("id"), null).get("items")
    ).isEqualTo(trip.get("items"));
    assertThat(
      user
        .send("POST", "/trips", Map.of("conditions", c, "items", items), true)
        .statusCode()
    ).isEqualTo(409);
  }

  @Test
  void selectionRejectsOtherMembersDuplicatesWrongRegionLodgingsAndTooMany()
    throws Exception {
    var user = new Browser().signup();
    var other = new Browser().signup();
    var seoul = places("서울");
    var p = seoul.getFirst();
    var lodging = seoul
      .stream()
      .filter(x -> "숙소".equals(x.get("category")))
      .findFirst()
      .orElseThrow();
    var busan = places("부산").getFirst();
    user.call("POST", "/favorites", saveInput(p));
    user.call("POST", "/favorites", saveInput(lodging));
    user.call("POST", "/favorites", saveInput(busan));
    String id = p.get("id").toString();
    assertThat(
      other
        .send("POST", "/recommendations", conditions(List.of(id)), true)
        .statusCode()
    ).isEqualTo(409);
    assertThat(
      user
        .send("POST", "/recommendations", conditions(List.of(id, id)), true)
        .statusCode()
    ).isEqualTo(400);
    assertThat(
      user
        .send(
          "POST",
          "/recommendations",
          conditions(List.of(busan.get("id").toString())),
          true
        )
        .statusCode()
    ).isEqualTo(400);
    assertThat(
      user
        .send(
          "POST",
          "/recommendations",
          conditions(List.of(lodging.get("id").toString())),
          true
        )
        .statusCode()
    ).isEqualTo(400);
    assertThat(
      user
        .send(
          "POST",
          "/recommendations",
          conditions(List.of("a", "b", "c", "d", "e", "f")),
          true
        )
        .statusCode()
    ).isEqualTo(400);
    user.call("DELETE", "/favorites/" + id, null);
    assertThat(
      user
        .send("POST", "/recommendations", conditions(List.of(id)), true)
        .statusCode()
    ).isEqualTo(409);
  }

  @Test
  void unavailableSavedPlaceFailsExplicitlyAndSameCategoryOmissionsAreVisible()
    throws Exception {
    var user = new Browser().signup();
    var p = places("서울").getFirst();
    user.call("POST", "/favorites", saveInput(p));
    var old = new HashMap<>(p);
    old.put("name", "현재 검색에는 없는 옛 장소");
    db.update(
      "UPDATE favorite_places SET place_json=? WHERE member_id=?",
      json.write(old),
      user.me()
    );
    assertThat(
      user
        .send(
          "POST",
          "/recommendations",
          conditions(List.of(p.get("id").toString())),
          true
        )
        .statusCode()
    ).isEqualTo(409);
    user.call("DELETE", "/favorites/" + p.get("id"), null);
    var museums = places("서울")
      .stream()
      .filter(x -> "전시".equals(x.get("category")))
      .limit(3)
      .toList();
    for (var museum : museums)
      user.call("POST", "/favorites", saveInput(museum));
    var r = user.send(
      "POST",
      "/recommendations",
      conditions(
        museums
          .stream()
          .map(x -> x.get("id").toString())
          .toList()
      ),
      true
    );
    assertThat(r.statusCode()).isEqualTo(200);
    for (var course : json.mapper().readTree(r.body()))
      assertThat(course.get("warnings").toString()).contains(
        "이 코스에 포함되지 않은 찜:"
      );
  }

  @Test
  void capacityRaceStaysWithinLimitAndWithdrawalErasesPrivateFavorites()
    throws Exception {
    var user = new Browser().signup();
    String member = user.me();
    var places = places("서울");
    try {
      for (int i = 0; i < 199; i++) db.update(
        "INSERT INTO favorite_places(member_id,place_id,place_json) VALUES(?,?,?)",
        member,
        "limit-test-" + i,
        json.write(places.getFirst())
      );
      try (var pool = Executors.newFixedThreadPool(2)) {
        var gate = new CountDownLatch(1);
        var a = pool.submit(() -> {
          gate.await();
          return user
            .send("POST", "/favorites", saveInput(places.get(0)), true)
            .statusCode();
        });
        var b = pool.submit(() -> {
          gate.await();
          return user
            .send("POST", "/favorites", saveInput(places.get(1)), true)
            .statusCode();
        });
        gate.countDown();
        assertThat(
          List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))
        ).containsExactlyInAnyOrder(200, 409);
      }
      assertThat(
        db.queryForObject(
          "SELECT COUNT(*) FROM favorite_places WHERE member_id=?",
          Integer.class,
          member
        )
      ).isEqualTo(200);
      var winner = places
        .stream()
        .filter(
          p ->
            db.queryForObject(
              "SELECT COUNT(*) FROM favorite_places WHERE member_id=? AND place_id=?",
              Integer.class,
              member,
              p.get("id")
            ) == 1
        )
        .findFirst()
        .orElseThrow();
      user.call("POST", "/favorites", saveInput(winner));
      user.call(
        "DELETE",
        "/auth/account",
        Map.of("password", "Test-only-Password12!", "confirmed", true)
      );
      assertThat(
        db.queryForObject(
          "SELECT COUNT(*) FROM favorite_places WHERE member_id=?",
          Integer.class,
          member
        )
      ).isZero();
      assertThat(
        user.send("GET", "/favorites", null, false).statusCode()
      ).isEqualTo(401);
    } finally {
      db.update("DELETE FROM favorite_places WHERE member_id=?", member);
    }
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
