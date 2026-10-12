package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.util.*;
import kr.yeohaengpick.common.JsonCodec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 테스트 전용 메모리 DB와 실제 HTTP 서버를 사용합니다.
 * 필터·세션·CSRF·MyBatis·트랜잭션을 모두 통과시켜 화면으로만 확인하기 어려운
 * 권한 침해, 동시 편집, 공유 정보 노출을 재현합니다. 외부 유료 API는 호출하지 않습니다.
 */
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "spring.datasource.url=jdbc:h2:mem:flows;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.sample-data=true",
    "app.kakao-rest-key=",
    "app.gemini-key=",
    "app.mail-enabled=false",
  }
)
class TravelFlowTest {

  @LocalServerPort
  int port;

  @Autowired
  kr.yeohaengpick.auth.AuthRateLimit authRateLimit;

  @BeforeEach
  void isolateAuthenticationBudget() {
    // 모든 HTTP 테스트가 같은 루프백 IP를 사용합니다. 다른 시나리오의 가입 횟수가
    // 다음 테스트를 막지 않도록 테스트 서버의 메모리만 비웁니다. 요청 필터 자체는 계속 실행합니다.
    (
      (Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(
        authRateLimit,
        "attempts"
      )
    ).clear();
  }

  @Test
  void authenticationLimitStillAppliesWithinOneScenario() throws Exception {
    var visitor = new Browser();
    for (int i = 0; i < 30; i++) assertThat(
      visitor
        .request(
          "POST",
          "/auth/login",
          Map.of("email", visitor.email, "password", "Not-a-real-Password12!"),
          true
        )
        .statusCode()
    ).isEqualTo(401);
    var blocked = visitor.request(
      "POST",
      "/auth/login",
      Map.of("email", visitor.email, "password", "Not-a-real-Password12!"),
      true
    );
    assertThat(blocked.statusCode()).isEqualTo(429);
    assertThat(blocked.headers().firstValue("Retry-After")).contains("60");
  }

  @Test
  void transportChecksMembershipAndSavedEndpointsBeforeExternalCalls()
    throws Exception {
    var owner = new Browser().signup();
    var outsider = new Browser().signup();
    var trip = owner.trip();
    String base = "/trips/" + trip.get("id") + "/transport";
    var request = Map.of(
      "from",
      Map.of("kind", "ITEM", "id", items(trip).getFirst().get("id")),
      "to",
      Map.of("kind", "ITEM", "id", items(trip).getLast().get("id")),
      "mode",
      "TRANSIT"
    );
    assertThat(
      outsider.request("POST", base + "/routes", request, true).statusCode()
    ).isEqualTo(403);
    assertThat(
      outsider
        .request("POST", base + "/origins", Map.of("query", "서울역"), true)
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      owner
        .request(
          "POST",
          base + "/routes",
          Map.of(
            "from",
            Map.of("kind", "ITEM", "id", "other-trip-item"),
            "to",
            request.get("to"),
            "mode",
            "WALK"
          ),
          true
        )
        .statusCode()
    ).isEqualTo(400);
    assertThat(
      owner.request("POST", base + "/routes", request, true).statusCode()
    ).isEqualTo(503);
    assertThat(
      owner
        .request(
          "POST",
          base + "/routes",
          Map.of(
            "from",
            request.get("from"),
            "to",
            request.get("from"),
            "mode",
            "CAR"
          ),
          true
        )
        .statusCode()
    ).isEqualTo(400);
    assertThat(
      owner.call("GET", "/trips/" + trip.get("id"), null).get("version")
    ).isEqualTo(trip.get("version"));
  }

  @Autowired
  JsonCodec json;

  @Autowired
  kr.yeohaengpick.transport.TransportQuotes transportQuotes;

  /** 외부 호출 없이 공급자 조회 직후와 같은 서버 영수증을 발급합니다. 실제 HTTP 필터와 저장은 우회하지 않습니다. */
  Map<String, Object> routeRequest(
    Browser user,
    Map<String, Object> trip,
    int from,
    int to,
    int minutes
  ) throws Exception {
    var a = items(trip).get(from);
    var b = items(trip).get(to);
    String member = user.call("GET", "/auth/me", null).get("id").toString();
    var request = new kr.yeohaengpick.transport.TransportService.Request(
      new kr.yeohaengpick.transport.TransportService.Ref(
        "ITEM",
        a.get("id").toString()
      ),
      new kr.yeohaengpick.transport.TransportService.Ref(
        "ITEM",
        b.get("id").toString()
      ),
      "TRANSIT"
    );
    String token = transportQuotes.issue(
      trip.get("id").toString(),
      member,
      ((Number) trip.get("version")).intValue(),
      request,
      new kr.yeohaengpick.transport.TransportRoutes.Point(
        a.get("id").toString(),
        a.get("placeName").toString(),
        ((Number) a.get("latitude")).doubleValue(),
        ((Number) a.get("longitude")).doubleValue()
      ),
      new kr.yeohaengpick.transport.TransportRoutes.Point(
        b.get("id").toString(),
        b.get("placeName").toString(),
        ((Number) b.get("latitude")).doubleValue(),
        ((Number) b.get("longitude")).doubleValue()
      ),
      "2026-10-12T01:00:00Z",
      "KAKAO_MAP",
      List.of(
        new kr.yeohaengpick.transport.TransportRoutes.Option(
          "verified-route",
          "TRANSIT",
          2000,
          minutes,
          0,
          1500,
          null,
          3,
          List.of(),
          List.of()
        )
      )
    );
    return new HashMap<>(
      Map.of(
        "quoteToken",
        token,
        "optionId",
        "verified-route",
        "version",
        trip.get("version"),
        "bufferMinutes",
        20,
        "strategy",
        "DEPARTURE"
      )
    );
  }

  @Test
  void verifiedRouteApplyPersistsAndUndoCannotShortenSavedMovement()
    throws Exception {
    var user = new Browser().signup();
    var trip = user.trip();
    String base = "/trips/" + trip.get("id");
    var request = routeRequest(user, trip, 0, 1, 120);
    var preview = user.request(
      "POST",
      base + "/transport/timing/preview",
      request,
      true
    );
    assertThat(preview.statusCode()).isEqualTo(200);
    var plans = json.mapper().readTree(preview.body());
    assertThat(plans.get(0).path("canApply").asBoolean()).isTrue();
    assertThat(user.call("GET", base, null).get("version")).isEqualTo(0);
    // 분·좌표·방문 시각을 덧붙여도 입력 계약에 없는 값은 사용하지 않고 서버 조회값만 저장합니다.
    request.put("minutes", 1);
    request.put("items", List.of());
    var saved = user.call("POST", base + "/transport/timing/apply", request);
    assertThat(saved.get("version")).isEqualTo(1);
    assertThat(items(saved).get(1).get("startTime")).isEqualTo("13:50");
    var timing = json
      .mapper()
      .valueToTree(user.call("GET", base, null))
      .path("routeTimings")
      .get(0);
    assertThat(timing.path("minutes").asInt()).isEqualTo(120);
    assertThat(timing.path("bufferMinutes").asInt()).isEqualTo(20);
    assertThat(timing.path("queriedAt").asText()).isEqualTo(
      "2026-10-12T01:00:00Z"
    );
    assertThat(items(saved))
      .extracting(i -> i.get("cost"))
      .isEqualTo(
        items(trip)
          .stream()
          .map(i -> i.get("cost"))
          .toList()
      );
    assertThat(saved.get("expenses")).isEqualTo(trip.get("expenses"));
    String history = json
      .mapper()
      .valueToTree(saved)
      .path("history")
      .get(0)
      .path("id")
      .asText();
    assertThat(
      user
        .request(
          "POST",
          base + "/undo",
          Map.of("version", 1, "historyId", history),
          true
        )
        .statusCode()
    ).isEqualTo(409);
    assertThat(
      user
        .request("POST", base + "/transport/timing/apply", request, true)
        .statusCode()
    ).isEqualTo(409);
    String share = user
      .call("POST", base + "/share", Map.of("enabled", true))
      .get("token")
      .toString();
    assertThat(
      new Browser().call("GET", "/share/" + share, null)
    ).doesNotContainKeys("routeTimings");
    var removed = user.call(
      "POST",
      base + "/transport/timing/remove",
      Map.of("itemId", items(trip).get(1).get("id"), "version", 1)
    );
    assertThat(items(removed)).isEqualTo(items(saved));
    assertThat(removed.get("routeTimings")).isEqualTo(List.of());
    var restored = user.call(
      "POST",
      base + "/undo",
      Map.of("version", 2, "historyId", history)
    );
    assertThat(items(restored)).isEqualTo(items(trip));
  }

  @Test
  void routeTimingRejectsForeignReceiptStaleVersionForgeryAndNonAdjacentLegs()
    throws Exception {
    var user = new Browser().signup();
    var outsider = new Browser().signup();
    var trip = user.trip();
    String base = "/trips/" + trip.get("id");
    var request = routeRequest(user, trip, 0, 1, 30);
    assertThat(
      outsider
        .request("POST", base + "/transport/timing/preview", request, true)
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      outsider
        .request(
          "POST",
          base + "/transport/timing/remove",
          Map.of("itemId", items(trip).get(1).get("id"), "version", 0),
          true
        )
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      user
        .request("POST", base + "/transport/timing/apply", request, false)
        .statusCode()
    ).isEqualTo(403);
    var invalid = new HashMap<>(request);
    invalid.put("quoteToken", "forged");
    assertThat(
      user
        .request("POST", base + "/transport/timing/preview", invalid, true)
        .statusCode()
    ).isEqualTo(409);
    invalid = new HashMap<>(request);
    invalid.put("optionId", "made-up-route");
    assertThat(
      user
        .request("POST", base + "/transport/timing/apply", invalid, true)
        .statusCode()
    ).isEqualTo(400);
    invalid = new HashMap<>(request);
    invalid.put("bufferMinutes", 121);
    assertThat(
      user
        .request("POST", base + "/transport/timing/preview", invalid, true)
        .statusCode()
    ).isEqualTo(400);
    assertThat(
      user
        .request(
          "POST",
          base + "/transport/timing/preview",
          routeRequest(user, trip, 0, 2, 20),
          true
        )
        .statusCode()
    ).isEqualTo(400);
    user.call(
      "PUT",
      base + "/booking",
      Map.of(
        "itemId",
        items(trip).get(2).get("id"),
        "version",
        0,
        "status",
        "NONE",
        "reason",
        "",
        "fixed",
        true
      )
    );
    assertThat(
      user
        .request("POST", base + "/transport/timing/preview", request, true)
        .statusCode()
    ).isEqualTo(409);
  }

  @Test
  void placeReplacementInvalidatesOldRouteWhileFixedVisitsAndNewBookingsRemainProtected()
    throws Exception {
    var user = new Browser().signup();
    var trip = user.trip();
    String base = "/trips/" + trip.get("id");
    var saved = user.call(
      "POST",
      base + "/transport/timing/apply",
      routeRequest(user, trip, 0, 1, 120)
    );
    var booking = connectedBooking(trip, 1);
    booking.put("departure", "2026-11-01T12:00");
    booking.put("arrival", "2026-11-01T12:30");
    booking.put("beforeMinutes", 0);
    booking.put("afterMinutes", 0);
    saved = user.call("POST", base + "/external-bookings", booking);
    var report = json.mapper().valueToTree(saved).path("schedule");
    assertThat(report.path("issues").toString()).contains("ROUTE_OVERLAP");
    var blocked = user.request(
      "POST",
      base + "/transport/timing/preview",
      routeRequest(user, saved, 0, 1, 120),
      true
    );
    assertThat(
      json.mapper().readTree(blocked.body()).get(0).path("canApply").asBoolean()
    ).isFalse();
    // 예약 취소 후 장소를 변경하면 그 장소에 연결된 이전 경로가 삭제됩니다.
    booking.put(
      "id",
      json
        .mapper()
        .valueToTree(saved)
        .path("externalBookings")
        .get(0)
        .path("id")
        .asText()
    );
    booking.put("status", "CANCELLED");
    booking.put("version", 2);
    user.call("POST", base + "/external-bookings", booking);
    String item = items(trip).get(1).get("id").toString();
    String place = json
      .mapper()
      .readTree(
        user.request("GET", base + "/alternatives/" + item, null, false).body()
      )
      .get(0)
      .path("id")
      .asText();
    var replaced = user.call(
      "POST",
      base + "/apply",
      Map.of("itemId", item, "placeId", place, "version", 3)
    );
    assertThat(replaced.get("routeTimings")).isEqualTo(List.of());
    var locked = user.call(
      "PUT",
      base + "/booking",
      Map.of(
        "itemId",
        item,
        "version",
        4,
        "status",
        "USER_CONFIRMED",
        "reason",
        "확정",
        "fixed",
        true
      )
    );
    var request = routeRequest(user, locked, 0, 1, 600);
    var plans = json
      .mapper()
      .readTree(
        user
          .request("POST", base + "/transport/timing/preview", request, true)
          .body()
      );
    assertThat(plans.get(0).path("canApply").asBoolean()).isFalse();
    assertThat(plans.get(1).path("canApply").asBoolean()).isFalse();
    assertThat(
      user
        .request("POST", base + "/transport/timing/apply", request, true)
        .statusCode()
    ).isEqualTo(409);
  }

  @Autowired
  kr.yeohaengpick.auth.MemberMapper members;

  @Test
  void lodgingReceiptCanPreserveFirstVisitAndSaveCalculatedDeparture()
    throws Exception {
    var user = new Browser().signup();
    var conditionsWithHotel = new HashMap<>(conditions);
    conditionsWithHotel.put("endDate", "2026-11-02");
    conditionsWithHotel.put("lodgingIds", List.of("sample-11"));
    var courses = json
      .mapper()
      .readTree(
        user
          .request("POST", "/recommendations", conditionsWithHotel, true)
          .body()
      );
    var trip = user.call(
      "POST",
      "/trips",
      Map.of(
        "conditions",
        conditionsWithHotel,
        "items",
        json.read(courses.get(0).path("items").toString(), List.class)
      )
    );
    var hotel = json.mapper().valueToTree(trip).path("lodgings").get(0);
    var first = items(trip).getFirst();
    String member = user.call("GET", "/auth/me", null).get("id").toString();
    String token = transportQuotes.issue(
      trip.get("id").toString(),
      member,
      0,
      new kr.yeohaengpick.transport.TransportService.Request(
        new kr.yeohaengpick.transport.TransportService.Ref(
          "LODGING",
          hotel.path("placeId").asText()
        ),
        new kr.yeohaengpick.transport.TransportService.Ref(
          "ITEM",
          first.get("id").toString()
        ),
        "CAR"
      ),
      new kr.yeohaengpick.transport.TransportRoutes.Point(
        hotel.path("placeId").asText(),
        hotel.path("name").asText(),
        hotel.path("latitude").asDouble(),
        hotel.path("longitude").asDouble()
      ),
      new kr.yeohaengpick.transport.TransportRoutes.Point(
        first.get("id").toString(),
        first.get("placeName").toString(),
        ((Number) first.get("latitude")).doubleValue(),
        ((Number) first.get("longitude")).doubleValue()
      ),
      "2026-10-12T01:00:00Z",
      "KAKAO_MOBILITY",
      List.of(
        new kr.yeohaengpick.transport.TransportRoutes.Option(
          "hotel-route",
          "CAR",
          10000,
          30,
          null,
          null,
          0,
          null,
          List.of(),
          List.of()
        )
      )
    );
    String base = "/trips/" + trip.get("id") + "/transport/timing";
    var request = new HashMap<String, Object>(
      Map.of(
        "quoteToken",
        token,
        "optionId",
        "hotel-route",
        "version",
        0,
        "bufferMinutes",
        10,
        "departureTime",
        "09:00",
        "strategy",
        "ARRIVAL"
      )
    );
    var invalid = new HashMap<>(request);
    invalid.put("departureTime", "invalid");
    assertThat(
      user.request("POST", base + "/preview", invalid, true).statusCode()
    ).isEqualTo(400);
    var saved = user.call("POST", base + "/apply", request);
    assertThat(saved.get("items")).isEqualTo(trip.get("items"));
    var timing = json.mapper().valueToTree(saved).path("routeTimings").get(0);
    assertThat(timing.path("originDeparture").asText()).isEqualTo(
      java.time.LocalTime.parse(first.get("startTime").toString())
        .minusMinutes(40)
        .toString()
    );
    assertThat(timing.path("source").asText()).isEqualTo("KAKAO_MOBILITY");
  }

  @Test
  void companionCanApplyOwnReceiptButCannotReuseOwnersOrOverwriteNewerEdit()
    throws Exception {
    var owner = new Browser().signup();
    var companion = new Browser().signup();
    var trip = owner.trip();
    String base = "/trips/" + trip.get("id");
    owner.call(
      "POST",
      "/invitations",
      Map.of("tripId", trip.get("id"), "email", companion.email)
    );
    String invite = json
      .mapper()
      .readTree(companion.request("GET", "/invitations", null, false).body())
      .get(0)
      .path("id")
      .asText();
    companion.call(
      "POST",
      "/invitations/" + invite + "/respond",
      Map.of("accept", true)
    );
    trip = owner.call("GET", base, null);
    var ownerRequest = routeRequest(owner, trip, 0, 1, 120);
    assertThat(
      companion
        .request("POST", base + "/transport/timing/preview", ownerRequest, true)
        .statusCode()
    ).isEqualTo(409);
    var companionRequest = routeRequest(companion, trip, 0, 1, 120);
    companionRequest.put("strategy", "ARRIVAL");
    var changed = companion.call(
      "POST",
      base + "/transport/timing/apply",
      companionRequest
    );
    assertThat(items(changed).get(1).get("startTime")).isEqualTo(
      items(trip).get(1).get("startTime")
    );
    assertThat(
      owner
        .request("POST", base + "/transport/timing/apply", ownerRequest, true)
        .statusCode()
    ).isEqualTo(409);
    assertThat(owner.call("GET", base, null).get("items")).isEqualTo(
      changed.get("items")
    );
  }

  @Autowired
  kr.yeohaengpick.auth.EmailAccountService emailAccounts;

  @Test
  void passwordResetTokenIsSingleUseAndInvalidatesOldSessions()
    throws Exception {
    var user = new Browser().signup();
    String id = (String) user.call("GET", "/auth/me", null).get("id");
    String token = "a".repeat(64);
    String hash = HexFormat.of().formatHex(
      java.security.MessageDigest.getInstance("SHA-256").digest(
        token.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      )
    );
    // 발송 서버 없이 테스트용 해시를 넣습니다. 인증 링크 원문이 DB에 저장되지 않는 계약을 검증합니다.
    members.token(
      hash,
      id,
      "RESET",
      java.time.LocalDateTime.now().plusMinutes(30)
    );
    emailAccounts.consume(token, "RESET", "Changed-only-Password12!");
    assertThat(
      user.request("GET", "/auth/me", null, false).statusCode()
    ).isEqualTo(401);
    assertThatThrownBy(() ->
      emailAccounts.consume(token, "RESET", "Another-password12!")
    ).isInstanceOf(kr.yeohaengpick.common.DomainException.class);
    user.call(
      "POST",
      "/auth/login",
      Map.of("email", user.email, "password", "Changed-only-Password12!")
    );
  }

  @Test
  void comparisonCanBeAppliedAndRestoredWithoutLosingBookingMetadata()
    throws Exception {
    var user = new Browser().signup();
    var trip = user.trip();
    String id = (String) trip.get("id");
    String item = (String) items(trip).getFirst().get("id");
    String original = (String) items(trip).getFirst().get("placeId");
    var alternatives = json
      .mapper()
      .readTree(
        user
          .request("GET", "/trips/" + id + "/alternatives/" + item, null, false)
          .body()
      );
    String replacement = alternatives.get(0).path("id").asText();
    var preview = user.call(
      "POST",
      "/trips/" + id + "/preview",
      Map.of("itemId", item, "placeId", replacement, "version", 0)
    );
    assertThat(preview.get("canApply")).isEqualTo(true);
    var changed = user.call(
      "POST",
      "/trips/" + id + "/apply",
      Map.of("itemId", item, "placeId", replacement, "version", 0)
    );
    assertThat(items(changed).getFirst().get("placeId")).isEqualTo(replacement);
    var histories = json.mapper().valueToTree(changed.get("history"));
    var restored = user.call(
      "POST",
      "/trips/" + id + "/undo",
      Map.of("historyId", histories.get(0).path("id").asText(), "version", 1)
    );
    assertThat(items(restored).getFirst().get("placeId")).isEqualTo(original);
    assertThat(restored.get("version")).isEqualTo(2);
  }

  @Test
  void lodgingAndExternalBookingResultsArePersistedButNeverPubliclyShared()
    throws Exception {
    var user = new Browser().signup();
    var request = new HashMap<>(conditions);
    request.put("endDate", "2026-11-02");
    request.put("lodgingIds", List.of("sample-11"));
    var courses = json
      .mapper()
      .readTree(user.request("POST", "/recommendations", request, true).body());
    assertThat(courses.get(0).path("lodgingEstimate").asInt()).isEqualTo(
      260000
    );
    var trip = user.call(
      "POST",
      "/trips",
      Map.of(
        "conditions",
        request,
        "items",
        json.read(courses.get(0).path("items").toString(), List.class)
      )
    );
    String id = (String) trip.get("id");
    assertThat(
      json.mapper().valueToTree(trip).path("lodgings").size()
    ).isEqualTo(1);
    var saved = user.call(
      "POST",
      "/trips/" + id + "/external-bookings",
      Map.of(
        "label",
        "매진된 KTX",
        "category",
        "TRAIN",
        "departure",
        "2026-11-01T09:00",
        "amount",
        50000,
        "status",
        "FAILED",
        "reason",
        "매진",
        "version",
        0
      )
    );
    assertThat(
      json
        .mapper()
        .valueToTree(saved)
        .path("externalBookings")
        .get(0)
        .path("status")
        .asText()
    ).isEqualTo("FAILED");
    String token = (String) user
      .call("POST", "/trips/" + id + "/share", Map.of("enabled", true))
      .get("token");
    assertThat(
      new Browser().call("GET", "/share/" + token, null)
    ).doesNotContainKeys("lodgings", "externalBookings");
  }

  /** 시간은 테스트 여행의 첫 방문에서 파생하므로 추천 정렬이 개선되어도 예약 충돌 상황은 유지됩니다. */
  Map<String, Object> connectedBooking(Map<String, Object> trip, int version) {
    var time = java.time.LocalTime.parse(
      items(trip).getFirst().get("startTime").toString()
    );
    var b = new HashMap<String, Object>();
    b.putAll(
      Map.of(
        "label",
        "예약 시간 검증 KTX",
        "category",
        "TRAIN",
        "departure",
        "2026-11-01T" + time.plusMinutes(30),
        "arrival",
        "2026-11-01T" + time.plusMinutes(90),
        "status",
        "USER_CONFIRMED",
        "reason",
        "직접 확인",
        "version",
        version,
        "scheduleLinked",
        true
      )
    );
    b.putAll(
      Map.of(
        "departurePlace",
        "비공개 출발역",
        "arrivalPlace",
        "비공개 도착역",
        "beforeMinutes",
        30,
        "afterMinutes",
        30
      )
    );
    return b;
  }

  @Test
  void transportScheduleCanCompareApplyAndProtectRestoreAndConcurrentChanges()
    throws Exception {
    var user = new Browser().signup();
    var trip = user.trip();
    String base = "/trips/" + trip.get("id");
    var saved = user.call(
      "POST",
      base + "/external-bookings",
      connectedBooking(trip, 0)
    );
    assertThat(
      json.mapper().valueToTree(saved).path("schedule").path("issues").size()
    ).isPositive();
    var preview = user.request(
      "POST",
      base + "/schedule/preview",
      Map.of("version", 1),
      true
    );
    assertThat(preview.statusCode()).as(preview.body()).isEqualTo(200);
    var plans = json.mapper().readTree(preview.body());
    var possible = java.util.stream.StreamSupport.stream(
      plans.spliterator(),
      false
    )
      .filter(p -> p.path("canApply").asBoolean())
      .findFirst()
      .orElseThrow();
    // 비교만으로 DB와 version이 달라지지 않아야 합니다.
    assertThat(user.call("GET", base, null).get("version")).isEqualTo(1);
    var applied = user.call(
      "POST",
      base + "/schedule/apply",
      Map.of("version", 1, "strategy", possible.path("strategy").asText())
    );
    var tree = json.mapper().valueToTree(applied);
    assertThat(tree.path("schedule").path("issues").size()).isZero();
    assertThat(tree.path("externalBookings")).isEqualTo(
      json.mapper().valueToTree(saved).path("externalBookings")
    );
    assertThat(items(applied))
      .extracting(i -> i.get("placeId"))
      .containsExactlyElementsOf(
        items(trip)
          .stream()
          .map(i -> i.get("placeId"))
          .toList()
      );
    String history = tree.path("history").get(0).path("id").asText();
    assertThat(
      user
        .request(
          "POST",
          base + "/undo",
          Map.of("version", 2, "historyId", history),
          true
        )
        .statusCode()
    ).isEqualTo(409);
    assertThat(
      user
        .request(
          "POST",
          base + "/schedule/apply",
          Map.of("version", 1, "strategy", "LATER"),
          true
        )
        .statusCode()
    ).isEqualTo(409);
    var cancel = connectedBooking(trip, 2);
    cancel.put("id", tree.path("externalBookings").get(0).path("id").asText());
    cancel.put("status", "CANCELLED");
    user.call("POST", base + "/external-bookings", cancel);
    var restored = user.call(
      "POST",
      base + "/undo",
      Map.of("version", 3, "historyId", history)
    );
    assertThat(items(restored).getFirst().get("startTime")).isEqualTo(
      items(trip).getFirst().get("startTime")
    );
    assertThat(
      json
        .mapper()
        .valueToTree(restored)
        .path("externalBookings")
        .get(0)
        .path("status")
        .asText()
    ).isEqualTo("CANCELLED");
  }

  @Test
  void fixedVisitAndBookingOverlapCannotBeSilentlyReplannedOrShared()
    throws Exception {
    var user = new Browser().signup();
    var outsider = new Browser().signup();
    var trip = user.trip();
    String base = "/trips/" + trip.get("id");
    user.call(
      "PUT",
      base + "/booking",
      Map.of(
        "itemId",
        items(trip).getFirst().get("id"),
        "status",
        "USER_CONFIRMED",
        "reason",
        "확정 전시",
        "fixed",
        true,
        "version",
        0
      )
    );
    var saved = user.call(
      "POST",
      base + "/external-bookings",
      connectedBooking(trip, 1)
    );
    String mutableItem = items(trip).get(1).get("id").toString();
    var placeOptions = json
      .mapper()
      .readTree(
        user
          .request("GET", base + "/alternatives/" + mutableItem, null, false)
          .body()
      );
    var placeChange = Map.of(
      "itemId",
      mutableItem,
      "placeId",
      placeOptions.get(0).path("id").asText(),
      "version",
      2
    );
    assertThat(
      user.call("POST", base + "/preview", placeChange).get("canApply")
    ).isEqualTo(false);
    assertThat(
      user.request("POST", base + "/apply", placeChange, true).statusCode()
    ).isEqualTo(409);
    var plans = json
      .mapper()
      .readTree(
        user
          .request(
            "POST",
            base + "/schedule/preview",
            Map.of("version", 2),
            true
          )
          .body()
      );
    assertThat(
      java.util.stream.StreamSupport.stream(plans.spliterator(), false)
    ).noneMatch(p -> p.path("canApply").asBoolean());
    assertThat(
      user
        .request(
          "POST",
          base + "/schedule/apply",
          Map.of("version", 2, "strategy", "EARLIER"),
          true
        )
        .statusCode()
    ).isEqualTo(409);
    assertThat(
      outsider
        .request("POST", base + "/schedule/preview", Map.of("version", 2), true)
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      outsider
        .request(
          "POST",
          base + "/schedule/apply",
          Map.of("version", 2, "strategy", "LATER"),
          true
        )
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      outsider
        .request(
          "POST",
          base + "/external-bookings",
          connectedBooking(trip, 2),
          true
        )
        .statusCode()
    ).isEqualTo(403);
    var stale = connectedBooking(trip, 1);
    stale.put(
      "id",
      json
        .mapper()
        .valueToTree(saved)
        .path("externalBookings")
        .get(0)
        .path("id")
        .asText()
    );
    stale.put("arrival", "2026-11-01T19:00");
    assertThat(
      user
        .request("POST", base + "/external-bookings", stale, true)
        .statusCode()
    ).isEqualTo(409);
    assertThat(user.call("GET", base, null).get("externalBookings")).isEqualTo(
      saved.get("externalBookings")
    );
    String token = (String) user
      .call("POST", base + "/share", Map.of("enabled", true))
      .get("token");
    assertThat(
      new Browser().call("GET", "/share/" + token, null)
    ).doesNotContainKeys("schedule", "externalBookings");
  }

  @Test
  void transportArrivalAndMarginsAreValidatedAndOldUnlinkedRecordsStayUsable()
    throws Exception {
    var user = new Browser().signup();
    var trip = user.trip();
    String base = "/trips/" + trip.get("id") + "/external-bookings";
    var b = connectedBooking(trip, 0);
    b.remove("arrival");
    assertThat(user.request("POST", base, b, true).statusCode()).isEqualTo(400);
    b.put("arrival", b.get("departure"));
    assertThat(user.request("POST", base, b, true).statusCode()).isEqualTo(400);
    b.put("arrival", "2026-11-02T08:00");
    assertThat(user.request("POST", base, b, true).statusCode()).isEqualTo(400);
    b = connectedBooking(trip, 0);
    b.put("beforeMinutes", 361);
    assertThat(user.request("POST", base, b, true).statusCode()).isEqualTo(400);
    b = connectedBooking(trip, 0);
    b.put("status", "PARTNER_CONFIRMED");
    assertThat(user.request("POST", base, b, true).statusCode()).isEqualTo(400);
    b = connectedBooking(trip, 0);
    b.remove("arrival");
    b.put("scheduleLinked", false);
    assertThat(user.request("POST", base, b, false).statusCode()).isEqualTo(
      403
    );
    var saved = user.call("POST", base, b);
    assertThat(
      json
        .mapper()
        .valueToTree(saved)
        .path("schedule")
        .path("unlinkedCount")
        .asInt()
    ).isEqualTo(1);
    assertThat(saved.get("version")).isEqualTo(1);
  }

  @Test
  void acceptedCompanionCanConnectTransportButCannotOverwriteAnotherEdit()
    throws Exception {
    var owner = new Browser().signup();
    var companion = new Browser().signup();
    var trip = owner.trip();
    String base = "/trips/" + trip.get("id");
    owner.call(
      "POST",
      "/invitations",
      Map.of("tripId", trip.get("id"), "email", companion.email)
    );
    var invitation = json
      .mapper()
      .readTree(companion.request("GET", "/invitations", null, false).body())
      .get(0)
      .path("id")
      .asText();
    companion.call(
      "POST",
      "/invitations/" + invitation + "/respond",
      Map.of("accept", true)
    );
    int version = (
      (Number) owner.call("GET", base, null).get("version")
    ).intValue();
    var saved = companion.call(
      "POST",
      base + "/external-bookings",
      connectedBooking(trip, version)
    );
    assertThat(
      json.mapper().valueToTree(saved).path("schedule").path("windows").size()
    ).isEqualTo(1);
    assertThat(
      owner
        .request(
          "POST",
          base + "/schedule/preview",
          Map.of("version", version),
          true
        )
        .statusCode()
    ).isEqualTo(409);
    assertThat(
      owner
        .request(
          "POST",
          base + "/external-bookings",
          connectedBooking(trip, version),
          true
        )
        .statusCode()
    ).isEqualTo(409);
    assertThat(owner.call("GET", base, null).get("externalBookings")).isEqualTo(
      saved.get("externalBookings")
    );
  }

  final Map<String, Object> conditions = Map.of(
    "title",
    "권한 검증 여행",
    "regions",
    List.of("서울"),
    "startDate",
    "2026-11-01",
    "endDate",
    "2026-11-01",
    "origin",
    "비공개 출발 주소",
    "partySize",
    2,
    "budget",
    300000,
    "transport",
    "TRANSIT",
    "preferences",
    List.of("전시", "맛집"),
    "lodging",
    "비공개 숙소 메모"
  );

  class Browser {

    final String email = UUID.randomUUID() + "@example.test";
    final HttpClient client = HttpClient.newBuilder()
      .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
      .build();

    HttpResponse<String> request(
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
        (String) map(request("GET", "/auth/csrf", null, false)).get("token")
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

    Map<String, Object> call(String method, String path, Object body)
      throws Exception {
      var response = request(method, path, body, !method.equals("GET"));
      assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
      return response.body().startsWith("{") ? map(response) : Map.of();
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
          "테스트 여행자"
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
      var response = request("POST", "/recommendations", conditions, true);
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      var courses = json.mapper().readTree(response.body());
      assertThat(courses.size()).isEqualTo(3);
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

  @SuppressWarnings("unchecked")
  Map<String, Object> map(HttpResponse<String> response) {
    return json.read(response.body(), Map.class);
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> items(Map<String, Object> trip) {
    return (List<Map<String, Object>>) trip.get("items");
  }

  @Test
  void mutationsRequireCsrfAndPrivateRoutesRequireSession() throws Exception {
    var visitor = new Browser();
    assertThat(
      visitor.request("GET", "/trips", null, false).statusCode()
    ).isEqualTo(401);
    assertThat(
      visitor
        .request(
          "POST",
          "/auth/register",
          Map.of(
            "email",
            visitor.email,
            "password",
            "Test-only-Password12!",
            "nickname",
            "테스터"
          ),
          false
        )
        .statusCode()
    ).isEqualTo(403);
    var user = new Browser().signup();
    var me = user.call("GET", "/auth/me", null);
    assertThat(me).doesNotContainKeys("passwordHash", "password_hash");
    assertThat(
      user.request("GET", "/admin/reports", null, false).statusCode()
    ).isEqualTo(403);
  }

  @Test
  void sharedViewExcludesPrivateDetailsAndCanBeRevoked() throws Exception {
    var owner = new Browser().signup();
    var trip = owner.trip();
    String id = (String) trip.get("id");
    String token = (String) owner
      .call("POST", "/trips/" + id + "/share", Map.of("enabled", true))
      .get("token");
    assertThat(token).hasSize(64);
    var shared = new Browser().call("GET", "/share/" + token, null);
    assertThat(shared).doesNotContainKeys(
      "origin",
      "lodging",
      "members",
      "expenses",
      "ownerId",
      "shareToken"
    );
    assertThat(items(shared).getFirst()).doesNotContainKeys(
      "bookingStatus",
      "bookingReason",
      "tripId",
      "id"
    );
    owner.call("POST", "/trips/" + id + "/share", Map.of("enabled", false));
    assertThat(
      new Browser().request("GET", "/share/" + token, null, false).statusCode()
    ).isEqualTo(404);
  }

  @Test
  void outsiderCannotReadOrModifyTrip() throws Exception {
    var owner = new Browser().signup();
    var other = new Browser().signup();
    var trip = owner.trip();
    String id = (String) trip.get("id");
    assertThat(
      other.request("GET", "/trips/" + id, null, false).statusCode()
    ).isEqualTo(403);
    assertThat(
      other
        .request(
          "POST",
          "/trips/" + id + "/tasks",
          Map.of("content", "침입"),
          true
        )
        .statusCode()
    ).isEqualTo(403);
  }

  @Test
  void confirmedBookingsAndStaleVersionsAreProtected() throws Exception {
    var owner = new Browser().signup();
    var trip = owner.trip();
    String id = (String) trip.get("id");
    String item = (String) items(trip).getFirst().get("id");
    var alternatives = owner.request(
      "GET",
      "/trips/" + id + "/alternatives/" + item,
      null,
      false
    );
    String place = json
      .mapper()
      .readTree(alternatives.body())
      .get(0)
      .path("id")
      .asText();
    var changed = owner.call(
      "PUT",
      "/trips/" + id + "/booking",
      Map.of(
        "itemId",
        item,
        "status",
        "USER_CONFIRMED",
        "reason",
        "외부 예약 완료",
        "fixed",
        false,
        "version",
        0
      )
    );
    assertThat(items(changed).getFirst().get("fixed")).isEqualTo(true);
    assertThat(
      owner
        .request(
          "POST",
          "/trips/" + id + "/preview",
          Map.of("itemId", item, "placeId", place, "version", 0),
          true
        )
        .statusCode()
    ).isEqualTo(409);
    assertThat(
      owner
        .request(
          "POST",
          "/trips/" + id + "/apply",
          Map.of("itemId", item, "placeId", place, "version", 1),
          true
        )
        .statusCode()
    ).isEqualTo(409);
  }

  @Test
  void invitationRequiresAcceptanceThenBothCanEdit() throws Exception {
    var owner = new Browser().signup();
    var companion = new Browser().signup();
    var trip = owner.trip();
    String id = (String) trip.get("id");
    companion.call(
      "PUT",
      "/auth/profile",
      Map.of(
        "nickname",
        "동행",
        "preferences",
        List.of("자연"),
        "dmScope",
        "ALL"
      )
    );
    owner.call(
      "POST",
      "/invitations",
      Map.of("tripId", id, "email", companion.email)
    );
    assertThat(
      companion.request("GET", "/trips/" + id, null, false).statusCode()
    ).isEqualTo(403);
    var invitations = json
      .mapper()
      .readTree(companion.request("GET", "/invitations", null, false).body());
    String invite = invitations.get(0).path("id").asText();
    companion.call(
      "POST",
      "/invitations/" + invite + "/respond",
      Map.of("accept", true)
    );
    assertThat(
      companion
        .call("GET", "/trips/" + id, null)
        .get("preferences")
        .toString()
    ).contains("자연", "전시");
    companion.call(
      "POST",
      "/trips/" + id + "/tasks",
      Map.of("content", "함께 준비한 우산")
    );
    assertThat(
      companion
        .request(
          "POST",
          "/invitations/" + invite + "/respond",
          Map.of("accept", true),
          true
        )
        .statusCode()
    ).isEqualTo(409);
  }

  @Test
  void dmRequestNeedsAcceptanceAndBlockingStopsFurtherMessages()
    throws Exception {
    var sender = new Browser().signup();
    var receiver = new Browser().signup();
    var outsider = new Browser().signup();
    String senderId = (String) sender.call("GET", "/auth/me", null).get("id");
    var room = sender.call(
      "POST",
      "/messages",
      Map.of("email", receiver.email, "body", "여행 관련 대화 요청")
    );
    String id = (String) room.get("id");
    assertThat(
      sender
        .request(
          "POST",
          "/messages/" + id,
          Map.of("body", "수락 전 추가 발송"),
          true
        )
        .statusCode()
    ).isEqualTo(409);
    assertThat(
      outsider.request("GET", "/messages/" + id, null, false).statusCode()
    ).isEqualTo(403);
    receiver.call(
      "POST",
      "/messages/" + id + "/respond",
      Map.of("accept", true)
    );
    sender.call("POST", "/messages/" + id, Map.of("body", "반갑습니다"));
    receiver.call(
      "PUT",
      "/messages/blocks/" + senderId,
      Map.of("enabled", true)
    );
    assertThat(
      sender
        .request(
          "POST",
          "/messages/" + id,
          Map.of("body", "차단 후 발송"),
          true
        )
        .statusCode()
    ).isEqualTo(403);
  }

  @Test
  void nestedTripConditionsAreValidatedBeforePersistence() throws Exception {
    var owner = new Browser().signup();
    assertThat(
      owner
        .request(
          "POST",
          "/trips",
          Map.of("conditions", Map.of("title", ""), "items", List.of()),
          true
        )
        .statusCode()
    ).isEqualTo(400);
  }
}
