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

/** 실제 세션·CSRF·SQL·트랜잭션을 통과해 공개 범위, 독립 복사, 중복 재시도와 공유 종료 경합을 검사합니다. */
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
class TripImportFlowTest extends HttpFlowDatabase {

  @LocalServerPort
  int port;

  @Autowired
  JsonCodec json;

  @Autowired
  JdbcTemplate db;

  @Autowired
  kr.yeohaengpick.auth.AuthRateLimit rateLimit;

  @Autowired
  kr.yeohaengpick.trip.TripMapper trips;

  @BeforeEach
  void resetBudget() {
    (
      (Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(
        rateLimit,
        "attempts"
      )
    ).clear();
  }

  Map<String, Object> conditions() {
    var c = new HashMap<String, Object>();
    c.put("title", "공유 원본 여행");
    c.put("regions", List.of("서울"));
    c.put("startDate", "2026-11-01");
    c.put("endDate", "2026-11-02");
    c.put("origin", "비공개 출발 주소");
    c.put("partySize", 4);
    c.put("budget", 555555);
    c.put("transport", "TRANSIT");
    c.put("preferences", List.of("맛집", "전시"));
    c.put("lodging", "비공개 숙소 메모");
    return c;
  }

  record Source(Browser owner, String id, String token) {}

  Source source() throws Exception {
    var owner = new Browser().signup();
    var response = owner.send("POST", "/recommendations", conditions(), true);
    var courses = json.mapper().readTree(response.body());
    var created = owner.call(
      "POST",
      "/trips",
      Map.of(
        "conditions",
        conditions(),
        "items",
        json.read(courses.get(0).get("items").toString(), List.class)
      )
    );
    String id = created.get("id").toString();
    String token = owner
      .call("POST", "/trips/" + id + "/share", Map.of("enabled", true))
      .get("token")
      .toString();
    return new Source(owner, id, token);
  }

  Map<String, Object> request(Source s) throws Exception {
    var own = conditions();
    own.put("title", "내가 가져온 여행");
    own.put("origin", "내 출발지");
    own.put("partySize", 2);
    own.put("budget", 300000);
    own.put("transport", "CAR");
    own.put("preferences", List.of("자연"));
    own.put("startDate", "2027-12-31");
    own.put("endDate", "2028-01-01");
    own.put("lodging", "");
    var r = new HashMap<String, Object>();
    r.put("token", s.token);
    r.put(
      "snapshot",
      new Browser().call("GET", "/share/" + s.token, null).get("snapshot")
    );
    r.put("requestId", UUID.randomUUID().toString());
    r.put("conditions", own);
    return r;
  }

  @Test
  void copiesOnlyPublicPlacesIntoIndependentEditableTrip() throws Exception {
    var s = source();
    var recipient = new Browser().signup();
    var first = trips.items(s.id).getFirst();
    String secret = "원본만의 비공개 기록";
    boolean oracle;
    try (var connection = db.getDataSource().getConnection()) {
      oracle = connection
        .getMetaData()
        .getDatabaseProductName()
        .equals("Oracle");
    }
    db.update(
      "UPDATE itinerary_items SET fixed=" +
        (oracle ? "1" : "TRUE") +
        ",booking_status='USER_CONFIRMED',booking_reason=?,visit_start='10:00',visit_end='22:00',visit_cost=177777,visit_note=? WHERE id=?",
      secret,
      secret,
      first.get("id")
    );
    trips.addTask(UUID.randomUUID().toString(), s.id, secret);
    trips.expense(
      UUID.randomUUID().toString(),
      s.id,
      s.owner.me(),
      secret,
      12345
    );
    var input = request(s);
    var publicView = new Browser().call("GET", "/share/" + s.token, null);
    assertThat(publicView).containsOnlyKeys(
      "title",
      "startDate",
      "endDate",
      "transport",
      "regions",
      "items",
      "snapshot"
    );
    assertThat(json.write(publicView)).doesNotContain(
      secret,
      "177777",
      "비공개 출발",
      "비공개 숙소"
    );
    var publicItems = items(publicView);
    assertThat(publicItems.getFirst()).containsOnlyKeys(
      "dayIndex",
      "sortOrder",
      "placeId",
      "placeName",
      "region",
      "category",
      "startTime",
      "duration",
      "latitude",
      "longitude",
      "placeUrl",
      "indoor",
      "source",
      "cost"
    );
    String id = recipient
      .call("POST", "/trips/import", input)
      .get("id")
      .toString();
    var copy = recipient.call("GET", "/trips/" + id, null);
    assertThat(copy)
      .containsEntry("ownerId", recipient.me())
      .containsEntry("title", "내가 가져온 여행")
      .containsEntry("origin", "내 출발지")
      .containsEntry("partySize", 2)
      .containsEntry("budget", 300000)
      .containsEntry("transport", "CAR")
      .containsEntry("startDate", "2027-12-31")
      .containsEntry("endDate", "2028-01-01")
      .containsEntry("version", 0);
    assertThat(copy.get("preferences")).isEqualTo(List.of("자연"));
    for (String key : List.of(
      "expenses",
      "lodgings",
      "routeTimings",
      "externalBookings",
      "history",
      "ownershipHistory"
    ))
      assertThat((List<?>) copy.get(key)).isEmpty();
    assertThat((List<?>) copy.get("members")).hasSize(1);
    assertThat(json.write(copy)).doesNotContain(
      secret,
      "비공개 출발",
      "비공개 숙소"
    );
    assertThat(items(copy)).hasSize(publicItems.size());
    for (int n = 0; n < publicItems.size(); n++) {
      var item = items(copy).get(n);
      assertThat(item)
        .containsAllEntriesOf(publicItems.get(n))
        .containsEntry("fixed", false)
        .containsEntry("bookingStatus", "NONE");
      assertThat(item.get("id")).isNotEqualTo(
        trips.items(s.id).get(n).get("id")
      );
      assertThat(item.get("visitStart")).isNull();
      assertThat(item.get("visitEnd")).isNull();
      assertThat(item.get("visitCost")).isNull();
      assertThat(item.get("visitNote")).isEqualTo("");
    }
    assertThat(
      s.owner.send("GET", "/trips/" + id, null, false).statusCode()
    ).isEqualTo(403);
    assertThat(
      recipient.send("GET", "/trips/" + s.id, null, false).statusCode()
    ).isEqualTo(403);
    recipient.call(
      "PUT",
      "/trips/" + id + "/booking",
      Map.of(
        "version",
        0,
        "itemId",
        items(copy).getFirst().get("id"),
        "status",
        "USER_CONFIRMED",
        "fixed",
        true,
        "reason",
        "내 예약"
      )
    );
    assertThat(trips.items(s.id).getFirst().get("bookingReason")).isEqualTo(
      secret
    );
    s.owner.call("DELETE", "/trips/" + s.id, null);
    assertThat(
      recipient.send("GET", "/trips/" + id, null, false).statusCode()
    ).isEqualTo(200);
  }

  @Test
  void requiresLoginCsrfAndValidOwnConditions() throws Exception {
    var s = source();
    var input = request(s);
    var guest = new Browser();
    var user = new Browser().signup();
    assertThat(
      guest.send("POST", "/trips/import", input, true).statusCode()
    ).isEqualTo(401);
    assertThat(
      user.send("POST", "/trips/import", input, false).statusCode()
    ).isEqualTo(403);
    for (var bad : List.of(
      Map.of("partySize", 0),
      Map.of("budget", 0),
      Map.of("title", " "),
      Map.of("origin", " "),
      Map.of("regions", List.of("부산")),
      Map.of("endDate", "2028-01-02"),
      Map.of("lodging", "원본 숙소"),
      Map.of("lodgingIds", List.of("place"))
    )) {
      var changed = new HashMap<>(input);
      var c = new HashMap<>((Map<String, Object>) input.get("conditions"));
      c.putAll(bad);
      changed.put("conditions", c);
      assertThat(user.send("POST", "/trips/import", changed, true).statusCode())
        .as(bad.toString())
        .isEqualTo(400);
    }
    for (String key : List.of("token", "snapshot", "requestId")) {
      var changed = new HashMap<>(input);
      changed.put(key, "invalid");
      assertThat(
        user.send("POST", "/trips/import", changed, true).statusCode()
      ).isEqualTo(400);
    }
    assertThat(trips.list(user.me())).isEmpty();
  }

  @Test
  void publicChangesRequireReviewWhilePrivateChangesDoNot() throws Exception {
    var s = source();
    var user = new Browser().signup();
    var input = request(s);
    db.update(
      "UPDATE trips SET origin='다른 비공개 주소',version=version+1 WHERE id=?",
      s.id
    );
    assertThat(
      new Browser().call("GET", "/share/" + s.token, null).get("snapshot")
    ).isEqualTo(input.get("snapshot"));
    user.call("POST", "/trips/import", input);
    var stale = request(s);
    db.update(
      "UPDATE itinerary_items SET start_time='08:15' WHERE id=?",
      trips.items(s.id).getFirst().get("id")
    );
    assertThat(
      user.send("POST", "/trips/import", stale, true).statusCode()
    ).isEqualTo(409);
    stale.put(
      "snapshot",
      new Browser().call("GET", "/share/" + s.token, null).get("snapshot")
    );
    user.call("POST", "/trips/import", stale);
  }

  @Test
  void revokedRotatedAndDeletedLinksCannotCreateTrips() throws Exception {
    var user = new Browser().signup();
    var s = source();
    var input = request(s);
    s.owner.call("POST", "/trips/" + s.id + "/share", Map.of("enabled", true));
    assertThat(
      user.send("POST", "/trips/import", input, true).statusCode()
    ).isEqualTo(404);
    String next = s.owner
      .call("POST", "/trips/" + s.id + "/share", Map.of("enabled", true))
      .get("token")
      .toString();
    var rotated = new Source(s.owner, s.id, next);
    input = request(rotated);
    s.owner.call("POST", "/trips/" + s.id + "/share", Map.of("enabled", false));
    assertThat(
      user.send("POST", "/trips/import", input, true).statusCode()
    ).isEqualTo(404);
    s = source();
    input = request(s);
    s.owner.call("DELETE", "/trips/" + s.id, null);
    assertThat(
      user.send("POST", "/trips/import", input, true).statusCode()
    ).isEqualTo(404);
    assertThat(trips.list(user.me())).isEmpty();
  }

  @Test
  void concurrentRetriesReturnOneTripAndNeverResurrectDeletedCopy()
    throws Exception {
    var s = source();
    var user = new Browser().signup();
    var input = request(s);
    // 같은 세션의 CSRF 초기 발급 경쟁은 미리 끝내고 두 실제 생성 요청만 동시에 보냅니다.
    user.call("GET", "/auth/csrf", null);
    String id;
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      var a = pool.submit(() -> {
        gate.await();
        return user.call("POST", "/trips/import", input);
      });
      var b = pool.submit(() -> {
        gate.await();
        return user.call("POST", "/trips/import", input);
      });
      gate.countDown();
      id = a.get(15, TimeUnit.SECONDS).get("id").toString();
      assertThat(b.get(15, TimeUnit.SECONDS).get("id")).isEqualTo(id);
    }
    assertThat(trips.list(user.me())).hasSize(1);
    var different = new HashMap<>(input);
    different.put("snapshot", "a".repeat(64));
    assertThat(
      user.send("POST", "/trips/import", different, true).statusCode()
    ).isEqualTo(409);
    var another = new Browser().signup();
    assertThat(
      another.call("POST", "/trips/import", input).get("id")
    ).isNotEqualTo(id);
    s.owner.call("POST", "/trips/" + s.id + "/share", Map.of("enabled", false));
    assertThat(user.call("POST", "/trips/import", input).get("id")).isEqualTo(
      id
    );
    user.call("DELETE", "/trips/" + id, null);
    assertThat(
      user.send("POST", "/trips/import", input, true).statusCode()
    ).isEqualTo(410);
    assertThat(trips.list(user.me())).isEmpty();
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM trip_imports WHERE member_id=?",
        Integer.class,
        user.me()
      )
    ).isEqualTo(1);
    user.call(
      "DELETE",
      "/auth/account",
      Map.of("password", "Test-only-Password12!", "confirmed", true)
    );
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM trip_imports WHERE request_id=? AND trip_id IS NULL",
        Integer.class,
        input.get("requestId")
      )
    ).isZero();
  }

  @Test
  void concurrentRevokeAndImportHaveOnlyAtomicOutcomes() throws Exception {
    var s = source();
    var user = new Browser().signup();
    var input = request(s);
    user.call("GET", "/auth/csrf", null);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      var copy = pool.submit(() -> {
        gate.await();
        return user.send("POST", "/trips/import", input, true);
      });
      var close = pool.submit(() -> {
        gate.await();
        return s.owner.call(
          "POST",
          "/trips/" + s.id + "/share",
          Map.of("enabled", false)
        );
      });
      gate.countDown();
      var response = copy.get(15, TimeUnit.SECONDS);
      close.get(15, TimeUnit.SECONDS);
      assertThat(response.statusCode()).isIn(200, 404);
      assertThat(trips.list(user.me())).hasSize(
        response.statusCode() == 200 ? 1 : 0
      );
      input.put("requestId", UUID.randomUUID().toString());
      assertThat(
        user.send("POST", "/trips/import", input, true).statusCode()
      ).isEqualTo(404);
    }
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> items(Map<String, Object> t) {
    return (List<Map<String, Object>>) t.get("items");
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
      var builder = HttpRequest.newBuilder(
        URI.create("http://127.0.0.1:" + port + "/api" + path)
      ).header("Content-Type", "application/json");
      if (csrf) builder.header(
        "X-XSRF-TOKEN",
        call("GET", "/auth/csrf", null).get("token").toString()
      );
      return client.send(
        builder
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
          "가져오기 검증 여행자"
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
