package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.util.*;
import kr.yeohaengpick.common.JsonCodec;
import kr.yeohaengpick.transport.TransportRoutes;
import kr.yeohaengpick.transport.TransportService;
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
    "app.tour-api-key=",
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

  /** 외부 API 대신 검증용 두 구간 영수증을 발급합니다. 적용·권한·트랜잭션은 실제 HTTP로 검사합니다. */
  Map<String, Object> overnightRequest(Browser user, Map<String, Object> trip)
    throws Exception {
    var from = items(trip)
      .stream()
      .filter(i -> ((Number) i.get("dayIndex")).intValue() == 0)
      .toList()
      .getLast();
    var to = items(trip)
      .stream()
      .filter(i -> ((Number) i.get("dayIndex")).intValue() == 1)
      .findFirst()
      .orElseThrow();
    var hotel = json.mapper().valueToTree(trip).path("lodgings").get(0);
    var member = user.call("GET", "/auth/me", null).get("id").toString();
    var hotelPoint = new TransportRoutes.Point(
      hotel.path("placeId").asText(),
      hotel.path("name").asText(),
      hotel.path("latitude").asDouble(),
      hotel.path("longitude").asDouble()
    );
    String[] tokens = new String[2];
    for (int n = 0; n < 2; n++) {
      var visit = n == 0 ? from : to;
      var visitPoint = new TransportRoutes.Point(
        visit.get("id").toString(),
        visit.get("placeName").toString(),
        ((Number) visit.get("latitude")).doubleValue(),
        ((Number) visit.get("longitude")).doubleValue()
      );
      var itemRef = new TransportService.Ref(
        "ITEM",
        visit.get("id").toString()
      );
      var hotelRef = new TransportService.Ref(
        "LODGING",
        hotel.path("placeId").asText()
      );
      tokens[n] = transportQuotes.issue(
        trip.get("id").toString(),
        member,
        ((Number) trip.get("version")).intValue(),
        new TransportService.Request(
          n == 0 ? itemRef : hotelRef,
          n == 0 ? hotelRef : itemRef,
          "TRANSIT"
        ),
        n == 0 ? visitPoint : hotelPoint,
        n == 0 ? hotelPoint : visitPoint,
        "2026-10-12T01:00:00Z",
        "KAKAO_MAP",
        List.of(
          new TransportRoutes.Option(
            "overnight-leg",
            "TRANSIT",
            1000,
            n == 0 ? 30 : 20,
            0,
            1500,
            null,
            3,
            List.of(),
            List.of()
          )
        )
      );
    }
    return new HashMap<>(
      Map.of(
        "inboundToken",
        tokens[0],
        "outboundToken",
        tokens[1],
        "inboundOption",
        "overnight-leg",
        "outboundOption",
        "overnight-leg",
        "version",
        trip.get("version"),
        "bufferMinutes",
        10,
        "minimumStayMinutes",
        480,
        "notBefore",
        "12:00",
        "strategy",
        "DEPARTURE"
      )
    );
  }

  Map<String, Object> hotelConditions() {
    var c = new HashMap<>(conditions);
    c.put("endDate", "2026-11-02");
    c.put("lodgingIds", List.of("sample-11"));
    return c;
  }

  Map<String, Object> stayRequest(int version) {
    return new HashMap<>(
      Map.of(
        "version",
        version,
        "checkInDate",
        "2026-11-01",
        "checkOutDate",
        "2026-11-02",
        "guestCount",
        2,
        "roomCount",
        2,
        "totalAmount",
        180000,
        "checkInTime",
        "15:00",
        "checkOutTime",
        "13:00"
      )
    );
  }

  String lodgingCandidate(Browser user, String base) throws Exception {
    var response = user.request(
      "GET",
      base +
        "/lodgings/candidates?region=" +
        java.net.URLEncoder.encode(
          "서울",
          java.nio.charset.StandardCharsets.UTF_8
        ),
      null,
      false
    );
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    var choices = json.mapper().readTree(response.body());
    assertThat(choices.isArray() && !choices.isEmpty()).isTrue();
    return choices.get(0).path("id").asText();
  }

  Map<String, Object> choiceRequest(String candidate, int version) {
    return new HashMap<>(
      Map.of(
        "region",
        "서울",
        "query",
        "",
        "placeId",
        candidate,
        "version",
        version
      )
    );
  }

  @Test
  void lodgingCanBeAddedAfterSavingWithoutChangingVisitsPaymentsOrSharing()
    throws Exception {
    var owner = new Browser().signup();
    var c = hotelConditions();
    c.put("lodgingIds", List.of());
    var trip = owner.trip(c);
    String base = "/trips/" + trip.get("id");
    String candidate = lodgingCandidate(owner, base);
    var request = choiceRequest(candidate, 0);
    var comparison = owner.call("POST", base + "/lodgings/preview", request);
    assertThat(comparison.get("beforeMinutes")).isNull();
    assertThat(comparison.get("minutesDelta")).isNull();
    assertThat(comparison.get("canApply")).isEqualTo(true);
    assertThat(owner.call("GET", base, null))
      .containsEntry("version", 0)
      .containsEntry("lodgings", List.of());
    request.put("name", "위조 숙소");
    request.put("totalAmount", 1);
    request.put("bookingStatus", "PARTNER_CONFIRMED");
    var saved = owner.call("POST", base + "/lodgings/apply", request);
    var hotel = json.mapper().valueToTree(saved).path("lodgings").get(0);
    assertThat(hotel.path("placeId").asText()).isEqualTo(candidate);
    assertThat(hotel.path("name").asText()).isNotEqualTo("위조 숙소");
    assertThat(hotel.path("bookingStatus").asText()).isEqualTo("NONE");
    assertThat(hotel.hasNonNull("totalAmount")).isFalse();
    assertThat(saved)
      .containsEntry("version", 1)
      .containsEntry("items", trip.get("items"))
      .containsEntry("expenses", trip.get("expenses"));
    assertThat(owner.call("GET", base, null).get("lodgings")).isEqualTo(
      saved.get("lodgings")
    );
    String token = owner
      .call("POST", base + "/share", Map.of("enabled", true))
      .get("token")
      .toString();
    assertThat(
      new Browser().call("GET", "/share/" + token, null)
    ).doesNotContainKeys("lodgings", "routeTimings");
  }

  @Test
  void lodgingReplacementClearsOnlyOldHotelRoutesAndConditionsAndUndoDoesNotResurrectThem()
    throws Exception {
    var owner = new Browser().signup();
    var trip = owner.trip(hotelConditions());
    String base = "/trips/" + trip.get("id");
    trip = owner.call("PUT", base + "/lodgings/sample-11/stay", stayRequest(0));
    trip = owner.call(
      "POST",
      base + "/transport/overnight/apply",
      overnightRequest(owner, trip)
    );
    // 숙소와 무관한 방문 간 조회 기록은 교체 후에도 남겨야 합니다.
    trip = owner.call(
      "POST",
      base + "/transport/timing/apply",
      routeRequest(
        owner,
        trip,
        items(trip).size() - 2,
        items(trip).size() - 1,
        30
      )
    );
    var unrelated = json.mapper().valueToTree(trip).path("routeTimings");
    var remaining = new ArrayList<Object>();
    unrelated.forEach(t -> {
      if (!t.hasNonNull("overnight")) remaining.add(t);
    });
    assertThat(remaining).hasSize(1);
    var unchangedVisits = trip.get("items");
    var comparisonRequest = choiceRequest(lodgingCandidate(owner, base), 3);
    var preview = owner.call(
      "POST",
      base + "/lodgings/preview",
      comparisonRequest
    );
    assertThat(preview)
      .containsEntry("clearedRouteCount", 1)
      .containsEntry("resetsStay", true)
      .containsEntry("canApply", true);
    assertThat(preview.get("beforeMinutes")).isNotNull();
    var saved = owner.call("POST", base + "/lodgings/apply", comparisonRequest);
    assertThat(saved)
      .containsEntry("version", 4)
      .containsEntry("items", unchangedVisits);
    assertThat(json.mapper().valueToTree(saved).path("routeTimings")).isEqualTo(
      json.mapper().valueToTree(remaining)
    );
    var hotel = json.mapper().valueToTree(saved).path("lodgings").get(0);
    assertThat(hotel.path("placeId").asText()).isNotEqualTo("sample-11");
    assertThat(hotel.hasNonNull("checkInDate")).isFalse();
    assertThat(hotel.hasNonNull("totalAmount")).isFalse();
    assertThat(hotel.path("bookingStatus").asText()).isEqualTo("NONE");
    String history = json
      .mapper()
      .valueToTree(saved)
      .path("history")
      .get(0)
      .path("id")
      .asText();
    var restored = owner.call(
      "POST",
      base + "/undo",
      Map.of("historyId", history, "version", 4)
    );
    assertThat(restored.get("lodgings")).isEqualTo(saved.get("lodgings"));
    assertThat(restored.get("routeTimings")).isEqualTo(
      saved.get("routeTimings")
    );
  }

  @Test
  void confirmedLodgingCannotBeReplacedAndStaleChoiceIsRejectedAfterCancellationRecord()
    throws Exception {
    var owner = new Browser().signup();
    var trip = owner.trip(hotelConditions());
    String base = "/trips/" + trip.get("id");
    String candidate = lodgingCandidate(owner, base);
    owner.call(
      "PUT",
      base + "/lodging-booking",
      Map.of(
        "itemId",
        "sample-11",
        "status",
        "USER_CONFIRMED",
        "reason",
        "가상 예약 상태 검증",
        "fixed",
        false,
        "version",
        0
      )
    );
    var request = choiceRequest(candidate, 1);
    var preview = owner.call("POST", base + "/lodgings/preview", request);
    assertThat(preview.get("canApply")).isEqualTo(false);
    assertThat(preview.get("conflicts").toString()).contains("예약 완료");
    assertThat(
      owner
        .request("POST", base + "/lodgings/apply", request, true)
        .statusCode()
    ).isEqualTo(409);
    owner.call(
      "PUT",
      base + "/lodging-booking",
      Map.of(
        "itemId",
        "sample-11",
        "status",
        "CANCELLED",
        "reason",
        "외부 예약 없는 테스트 기록",
        "fixed",
        false,
        "version",
        1
      )
    );
    assertThat(
      owner
        .request("POST", base + "/lodgings/preview", request, true)
        .statusCode()
    ).isEqualTo(409);
    assertThat(
      owner
        .request("POST", base + "/lodgings/apply", request, true)
        .statusCode()
    ).isEqualTo(409);
    request.put("version", 2);
    assertThat(
      owner.call("POST", base + "/lodgings/apply", request).get("version")
    ).isEqualTo(3);
  }

  @Test
  void lodgingChoiceValidatesMembershipCsrfRegionCategoryQueryAndDayTrip()
    throws Exception {
    var owner = new Browser().signup();
    var other = new Browser().signup();
    var trip = owner.trip(hotelConditions());
    String base = "/trips/" + trip.get("id");
    var request = choiceRequest(lodgingCandidate(owner, base), 0);
    assertThat(
      other
        .request(
          "GET",
          base +
            "/lodgings/candidates?region=" +
            java.net.URLEncoder.encode(
              "서울",
              java.nio.charset.StandardCharsets.UTF_8
            ),
          null,
          false
        )
        .statusCode()
    ).isEqualTo(403);
    for (String endpoint : List.of("preview", "apply")) {
      assertThat(
        other
          .request("POST", base + "/lodgings/" + endpoint, request, true)
          .statusCode()
      ).isEqualTo(403);
      assertThat(
        owner
          .request("POST", base + "/lodgings/" + endpoint, request, false)
          .statusCode()
      ).isEqualTo(403);
    }
    for (var invalid : List.of(
      Map.entry("region", "부산"),
      Map.entry("placeId", "sample-0"),
      Map.entry("placeId", "sample-11"),
      Map.entry("query", "x".repeat(101)),
      Map.entry("query", "존재하지않는호텔")
    )) {
      var bad = new HashMap<>(request);
      bad.put(invalid.getKey(), invalid.getValue());
      assertThat(
        owner.request("POST", base + "/lodgings/apply", bad, true).statusCode()
      ).isEqualTo(400);
    }
    var dayTrip = owner.trip();
    assertThat(
      owner
        .request(
          "POST",
          "/trips/" + dayTrip.get("id") + "/lodgings/apply",
          request,
          true
        )
        .statusCode()
    ).isEqualTo(400);
    assertThat(owner.call("GET", base, null).get("version")).isEqualTo(0);
  }

  @Test
  void acceptedCompanionCanChooseLodgingAndConcurrentOwnerChoiceFails()
    throws Exception {
    var owner = new Browser().signup();
    var companion = new Browser().signup();
    var trip = owner.trip(hotelConditions());
    String base = "/trips/" + trip.get("id");
    var request = choiceRequest(lodgingCandidate(owner, base), 0);
    owner.call(
      "POST",
      "/invitations",
      Map.of("tripId", trip.get("id"), "email", companion.email)
    );
    assertThat(
      companion
        .request("POST", base + "/lodgings/apply", request, true)
        .statusCode()
    ).isEqualTo(403);
    var invites = json
      .mapper()
      .readTree(companion.request("GET", "/invitations", null, false).body());
    companion.call(
      "POST",
      "/invitations/" + invites.get(0).path("id").asText() + "/respond",
      Map.of("accept", true)
    );
    var current = owner.call("GET", base, null);
    request.put("version", current.get("version"));
    companion.call("POST", base + "/lodgings/apply", request);
    assertThat(
      owner
        .request("POST", base + "/lodgings/apply", request, true)
        .statusCode()
    ).isEqualTo(409);
  }

  @Test
  void lodgingChoiceKeepsOtherRegionsAndRejectsRegionVisitedOnlyOnLastDay()
    throws Exception {
    var owner = new Browser().signup();
    var c = hotelConditions();
    c.put("regions", List.of("서울", "부산", "강릉"));
    c.put("endDate", "2026-11-03");
    c.put("lodgingIds", List.of("sample-11", "sample-23"));
    var trip = owner.trip(c);
    String base = "/trips/" + trip.get("id");
    var hotels = json.mapper().valueToTree(trip).path("lodgings");
    Object busan = null;
    for (var h : hotels)
      if (h.path("region").asText().equals("부산")) busan = h;
    assertThat(busan).isNotNull();
    var request = choiceRequest(lodgingCandidate(owner, base), 0);
    var preview = owner.call("POST", base + "/lodgings/preview", request);
    var legs = json.mapper().valueToTree(preview).path("legs");
    assertThat(legs.size()).isEqualTo(2);
    assertThat(legs.get(0).path("dayIndex").asInt()).isEqualTo(0);
    assertThat(legs.get(1).path("dayIndex").asInt()).isEqualTo(1);
    var saved = owner.call("POST", base + "/lodgings/apply", request);
    var savedHotels = json.mapper().valueToTree(saved).path("lodgings");
    assertThat(savedHotels.size()).isEqualTo(2);
    for (var h : savedHotels)
      if (h.path("region").asText().equals("부산")) assertThat(h).isEqualTo(
        busan
      );
    assertThat(
      owner
        .request(
          "GET",
          base +
            "/lodgings/candidates?region=" +
            java.net.URLEncoder.encode(
              "강릉",
              java.nio.charset.StandardCharsets.UTF_8
            ),
          null,
          false
        )
        .statusCode()
    ).isEqualTo(400);
  }

  @Test
  void lodgingStayPersistsWithoutMarkingPaidAndProtectsAccessVersionAndSharing()
    throws Exception {
    var owner = new Browser().signup();
    var outsider = new Browser().signup();
    var trip = owner.trip(hotelConditions());
    String base = "/trips/" + trip.get("id"),
      path = base + "/lodgings/sample-11/stay";
    var request = stayRequest(0);
    request.put("totalAmount", 0);
    request.put("bookingStatus", "PARTNER_CONFIRMED");
    assertThat(
      outsider.request("PUT", path, request, true).statusCode()
    ).isEqualTo(403);
    assertThat(
      owner.request("PUT", path, request, false).statusCode()
    ).isEqualTo(403);
    var saved = owner.call("PUT", path, request);
    var hotel = json.mapper().valueToTree(saved).path("lodgings").get(0);
    assertThat(hotel.path("checkInDate").asText()).isEqualTo("2026-11-01");
    assertThat(hotel.path("totalAmount").asInt()).isZero();
    assertThat(hotel.path("roomCount").asInt()).isEqualTo(2);
    assertThat(hotel.path("bookingStatus").asText()).isEqualTo("NONE");
    assertThat(saved.get("items")).isEqualTo(trip.get("items"));
    assertThat(saved.get("expenses")).isEqualTo(trip.get("expenses"));
    assertThat(saved.get("version")).isEqualTo(1);
    assertThat(owner.call("GET", base, null).get("lodgings")).isEqualTo(
      saved.get("lodgings")
    );
    assertThat(
      owner.request("PUT", path, request, true).statusCode()
    ).isEqualTo(409);
    String token = owner
      .call("POST", base + "/share", Map.of("enabled", true))
      .get("token")
      .toString();
    assertThat(
      outsider.call("GET", "/share/" + token, null)
    ).doesNotContainKeys("lodgings", "totalAmount", "roomCount", "guestCount");
    var reset = owner.call("PUT", path, Map.of("version", 1));
    assertThat(
      json
        .mapper()
        .valueToTree(reset)
        .path("lodgings")
        .get(0)
        .hasNonNull("totalAmount")
    ).isFalse();
    assertThat(reset.get("items")).isEqualTo(trip.get("items"));
  }

  @Test
  void lodgingStayRejectsInvalidDatesCountsAndFractionalAmountsWithoutMutation()
    throws Exception {
    var owner = new Browser().signup();
    var trip = owner.trip(hotelConditions());
    String base = "/trips/" + trip.get("id"),
      path = base + "/lodgings/sample-11/stay";
    for (var bad : List.of(
      Map.entry("checkInDate", (Object) "2026-10-31"),
      Map.entry("checkOutDate", (Object) "2026-11-01"),
      Map.entry("checkOutDate", (Object) "2026-11-03"),
      Map.entry("checkInDate", (Object) "2026-02-30"),
      Map.entry("checkInTime", (Object) "25:00"),
      Map.entry("guestCount", (Object) 3),
      Map.entry("roomCount", (Object) 0),
      Map.<String, Object>entry("totalAmount", -1),
      Map.entry("totalAmount", (Object) 1.5),
      Map.entry("guestCount", (Object) 1.5)
    )) {
      var request = stayRequest(0);
      request.put(bad.getKey(), bad.getValue());
      var response = owner.request("PUT", path, request, true);
      assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
    }
    assertThat(
      owner
        .request("PUT", path, Map.of("version", 0, "guestCount", 2), true)
        .statusCode()
    ).isEqualTo(400);
    assertThat(
      owner
        .request(
          "PUT",
          base + "/lodgings/another-hotel/stay",
          stayRequest(0),
          true
        )
        .statusCode()
    ).isEqualTo(404);
    assertThat(owner.call("GET", base, null).get("version")).isEqualTo(0);
  }

  @Test
  void lodgingConditionsBlockNewOvernightPlansAndCannotBreakAppliedConnection()
    throws Exception {
    var owner = new Browser().signup();
    var trip = owner.trip(hotelConditions());
    String base = "/trips/" + trip.get("id"),
      path = base + "/lodgings/sample-11/stay";
    var request = stayRequest(0);
    request.put("checkOutTime", "11:00");
    trip = owner.call("PUT", path, request);
    var route = overnightRequest(owner, trip);
    var plans = json
      .mapper()
      .readTree(
        owner
          .request("POST", base + "/transport/overnight/preview", route, true)
          .body()
      );
    assertThat(plans.get(0).path("canApply").asBoolean()).isFalse();
    assertThat(plans.get(0).path("conflicts").toString()).contains("체크아웃");
    assertThat(
      owner
        .request("POST", base + "/transport/overnight/apply", route, true)
        .statusCode()
    ).isEqualTo(409);
    request = stayRequest(1);
    trip = owner.call("PUT", path, request);
    route = overnightRequest(owner, trip);
    var applied = owner.call(
      "POST",
      base + "/transport/overnight/apply",
      route
    );
    request = stayRequest(3);
    request.put("checkOutTime", "11:00");
    assertThat(
      owner.request("PUT", path, request, true).statusCode()
    ).isEqualTo(409);
    var unchanged = owner.call("GET", base, null);
    assertThat(unchanged.get("version")).isEqualTo(3);
    assertThat(unchanged.get("routeTimings")).isEqualTo(
      applied.get("routeTimings")
    );
    var cleared = owner.call("PUT", path, Map.of("version", 3));
    assertThat(cleared.get("routeTimings")).isEqualTo(
      applied.get("routeTimings")
    );
  }

  @Test
  void acceptedCompanionCanEditStayAndOwnerStaleEditIsRejected()
    throws Exception {
    var owner = new Browser().signup();
    var companion = new Browser().signup();
    var trip = owner.trip(hotelConditions());
    String id = trip.get("id").toString(),
      path = "/trips/" + id + "/lodgings/sample-11/stay";
    owner.call(
      "POST",
      "/invitations",
      Map.of("tripId", id, "email", companion.email)
    );
    assertThat(
      companion.request("PUT", path, stayRequest(0), true).statusCode()
    ).isEqualTo(403);
    var invitations = json
      .mapper()
      .readTree(companion.request("GET", "/invitations", null, false).body());
    companion.call(
      "POST",
      "/invitations/" + invitations.get(0).path("id").asText() + "/respond",
      Map.of("accept", true)
    );
    var current = owner.call("GET", "/trips/" + id, null);
    var request = stayRequest(((Number) current.get("version")).intValue());
    companion.call("PUT", path, request);
    assertThat(
      owner.request("PUT", path, request, true).statusCode()
    ).isEqualTo(409);
  }

  @Test
  void overnightApplyPersistsBothLegsWithoutCountingStayAsTravelOrSharingIt()
    throws Exception {
    var user = new Browser().signup();
    var outsider = new Browser().signup();
    var trip = user.trip(hotelConditions());
    String base = "/trips/" + trip.get("id");
    var request = overnightRequest(user, trip);
    var preview = user.request(
      "POST",
      base + "/transport/overnight/preview",
      request,
      true
    );
    assertThat(preview.statusCode()).as(preview.body()).isEqualTo(200);
    var plans = json.mapper().readTree(preview.body());
    assertThat(plans.get(0).path("canApply").asBoolean()).isTrue();
    assertThat(plans.get(1).path("canApply").asBoolean()).isFalse();
    assertThat(user.call("GET", base, null).get("items")).isEqualTo(
      trip.get("items")
    );
    request.put("minutes", 1);
    request.put("overnight", Map.of("minimumStayMinutes", 0));
    assertThat(
      user
        .request("POST", base + "/transport/overnight/apply", request, false)
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      outsider
        .request("POST", base + "/transport/overnight/preview", request, true)
        .statusCode()
    ).isEqualTo(403);
    var saved = user.call("POST", base + "/transport/overnight/apply", request);
    var timing = json.mapper().valueToTree(saved).path("routeTimings").get(0);
    assertThat(saved.get("version")).isEqualTo(1);
    assertThat(timing.path("minutes").asInt()).isEqualTo(50);
    assertThat(
      timing.path("overnight").path("minimumStayMinutes").asInt()
    ).isEqualTo(480);
    assertThat(
      timing.path("overnight").path("inbound").path("minutes").asInt()
    ).isEqualTo(30);
    assertThat(
      timing.path("overnight").path("outbound").path("minutes").asInt()
    ).isEqualTo(20);
    assertThat(timing.path("overnight").path("departure").asText()).isEqualTo(
      "2026-11-02T12:00"
    );
    assertThat(user.call("GET", base, null).get("routeTimings")).isEqualTo(
      saved.get("routeTimings")
    );
    assertThat(
      items(saved)
        .stream()
        .mapToInt(i -> ((Number) i.get("cost")).intValue())
        .sum()
    ).isEqualTo(
      items(trip)
        .stream()
        .mapToInt(i -> ((Number) i.get("cost")).intValue())
        .sum()
    );
    assertThat(
      user
        .request("POST", base + "/transport/overnight/apply", request, true)
        .statusCode()
    ).isEqualTo(409);
    var history = json
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
          Map.of("historyId", history, "version", 1),
          true
        )
        .statusCode()
    ).isEqualTo(409);
    String token = user
      .call("POST", base + "/share", Map.of("enabled", true))
      .get("token")
      .toString();
    var shared = outsider.call("GET", "/share/" + token, null);
    assertThat(shared).doesNotContainKeys("routeTimings", "lodgings");
    var removed = user.call(
      "POST",
      base + "/transport/timing/remove",
      Map.of("itemId", timing.path("toItemId").asText(), "version", 1)
    );
    assertThat(removed.get("routeTimings")).asList().isEmpty();
    assertThat(removed.get("items")).isEqualTo(saved.get("items"));
  }

  @Test
  void overnightRejectsInvalidConditionsAndMixedReceiptsAndBookingOverlap()
    throws Exception {
    var user = new Browser().signup();
    var trip = user.trip(hotelConditions());
    String base = "/trips/" + trip.get("id");
    var request = overnightRequest(user, trip);
    for (var bad : List.of(
      Map.of("bufferMinutes", -1),
      Map.of("bufferMinutes", 121),
      Map.of("minimumStayMinutes", 0),
      Map.of("minimumStayMinutes", 61),
      Map.of("minimumStayMinutes", 961),
      Map.of("notBefore", "05:59"),
      Map.of("notBefore", "22:00"),
      Map.of("inboundOption", "forged"),
      Map.of("inboundToken", request.get("outboundToken"))
    )) {
      var invalid = new HashMap<>(request);
      invalid.putAll(bad);
      assertThat(
        user
          .request("POST", base + "/transport/overnight/preview", invalid, true)
          .statusCode()
      )
        .as(bad.toString())
        .isEqualTo(400);
    }
    var reserved = user.call(
      "POST",
      base + "/external-bookings",
      Map.ofEntries(
        Map.entry("label", "가상 야간 예약"),
        Map.entry("category", "TRAIN"),
        Map.entry("departure", "2026-11-02T03:00"),
        Map.entry("arrival", "2026-11-02T04:00"),
        Map.entry("beforeMinutes", 0),
        Map.entry("afterMinutes", 0),
        Map.entry("scheduleLinked", true),
        Map.entry("status", "USER_CONFIRMED"),
        Map.entry("reason", "테스트 가상 예약"),
        Map.entry("version", 0)
      )
    );
    assertThat(
      user
        .request("POST", base + "/transport/overnight/preview", request, true)
        .statusCode()
    ).isEqualTo(409);
    request = overnightRequest(user, reserved);
    request.put("notBefore", "08:00");
    var plans = json
      .mapper()
      .readTree(
        user
          .request("POST", base + "/transport/overnight/preview", request, true)
          .body()
      );
    assertThat(plans.get(0).path("canApply").asBoolean()).isFalse();
    assertThat(plans.get(1).path("canApply").asBoolean()).isFalse();
    assertThat(
      user
        .request("POST", base + "/transport/overnight/apply", request, true)
        .statusCode()
    ).isEqualTo(409);
    assertThat(user.call("GET", base, null).get("version")).isEqualTo(1);
  }

  @Test
  void overnightCompanionMustUseTwoOwnReceiptsAndCannotOverwriteNewVersion()
    throws Exception {
    var owner = new Browser().signup();
    var companion = new Browser().signup();
    var trip = owner.trip(hotelConditions());
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
    // 초대 수락도 공동 취향과 여행 버전을 바꾸므로 최신 여행에서 두 영수증을 발급합니다.
    trip = owner.call("GET", base, null);
    var own = overnightRequest(companion, trip);
    var other = overnightRequest(owner, trip);
    var mixed = new HashMap<>(own);
    mixed.put("outboundToken", other.get("outboundToken"));
    assertThat(
      companion
        .request("POST", base + "/transport/overnight/preview", mixed, true)
        .statusCode()
    ).isEqualTo(409);
    var saved = companion.call(
      "POST",
      base + "/transport/overnight/apply",
      own
    );
    assertThat(saved.get("version")).isEqualTo(
      ((Number) trip.get("version")).intValue() + 1
    );
    assertThat(
      owner
        .request("POST", base + "/transport/overnight/apply", other, true)
        .statusCode()
    ).isEqualTo(409);
  }

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
  void crossDayRoutePersistsDatesAndProtectsVersionsPermissionsAndUndo()
    throws Exception {
    var user = new Browser().signup();
    var multi = new HashMap<>(conditions);
    multi.put("endDate", "2026-11-02");
    var trip = user.trip(multi);
    int target = 0;
    while (((Number) items(trip).get(target).get("dayIndex")).intValue() == 0)
      target++;
    String base = "/trips/" + trip.get("id");
    var request = routeRequest(user, trip, target - 1, target, 120);
    request.put("departureTime", "2026-11-02T09:00");
    var preview = user.request(
      "POST",
      base + "/transport/timing/preview",
      request,
      true
    );
    assertThat(preview.statusCode()).as(preview.body()).isEqualTo(200);
    assertThat(
      json.mapper().readTree(preview.body()).get(0).path("canApply").asBoolean()
    ).isTrue();
    assertThat(user.call("GET", base, null).get("version")).isEqualTo(0);
    assertThat(
      new Browser()
        .signup()
        .request("POST", base + "/transport/timing/preview", request, true)
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      user
        .request("POST", base + "/transport/timing/apply", request, false)
        .statusCode()
    ).isEqualTo(403);
    request.put("minutes", 1);
    var saved = user.call("POST", base + "/transport/timing/apply", request);
    var timing = json
      .mapper()
      .valueToTree(user.call("GET", base, null))
      .path("routeTimings")
      .get(0);
    assertThat(timing.path("dayIndex").asInt()).isEqualTo(1);
    assertThat(timing.path("originDeparture").asText()).isEqualTo(
      "2026-11-02T09:00"
    );
    assertThat(timing.path("minutes").asInt()).isEqualTo(120);
    assertThat(items(saved).get(target).get("startTime")).isEqualTo("11:20");
    assertThat(items(saved).subList(0, target)).isEqualTo(
      items(trip).subList(0, target)
    );
    assertThat(saved.get("expenses")).isEqualTo(trip.get("expenses"));
    assertThat(
      user
        .request("POST", base + "/transport/timing/apply", request, true)
        .statusCode()
    ).isEqualTo(409);
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
      Map.of("version", 1, "itemId", items(saved).get(target).get("id"))
    );
    assertThat(removed.get("routeTimings")).isEqualTo(List.of());
    assertThat(items(removed)).isEqualTo(items(saved));
  }

  @Test
  void crossDayRouteValidatesDepartureDatesAndReportsNewBookingOverlap()
    throws Exception {
    var user = new Browser().signup();
    var multi = new HashMap<>(conditions);
    multi.put("endDate", "2026-11-02");
    var trip = user.trip(multi);
    int target = 0;
    while (((Number) items(trip).get(target).get("dayIndex")).intValue() == 0)
      target++;
    String base = "/trips/" + trip.get("id");
    var request = routeRequest(user, trip, target - 1, target, 120);
    for (String departure : List.of(
      "09:00",
      "2026-10-31T09:00",
      "2026-11-03T09:00",
      "2026-11-02T09:00:01"
    )) {
      request.put("departureTime", departure);
      assertThat(
        user
          .request("POST", base + "/transport/timing/preview", request, true)
          .statusCode()
      )
        .as(departure)
        .isEqualTo(400);
    }
    request.put("departureTime", "2026-11-01T06:00");
    assertThat(
      user
        .request("POST", base + "/transport/timing/apply", request, true)
        .statusCode()
    ).isEqualTo(409);
    request.put("departureTime", "2026-11-02T09:00");
    var saved = user.call("POST", base + "/transport/timing/apply", request);
    var booking = connectedBooking(saved, 1);
    booking.put("departure", "2026-11-02T09:30");
    booking.put("arrival", "2026-11-02T10:00");
    booking.put("beforeMinutes", 0);
    booking.put("afterMinutes", 0);
    var reserved = user.call("POST", base + "/external-bookings", booking);
    assertThat(
      json
        .mapper()
        .valueToTree(reserved)
        .path("schedule")
        .path("issues")
        .toString()
    ).contains("ROUTE_OVERLAP");
    var latest = routeRequest(user, reserved, target - 1, target, 120);
    latest.put("departureTime", "2026-11-02T09:00");
    assertThat(
      user
        .request("POST", base + "/transport/timing/apply", latest, true)
        .statusCode()
    ).isEqualTo(409);
    assertThat(user.call("GET", base, null).get("version")).isEqualTo(2);
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
    // 숙소에서 출발하는 단일 조회 구간도 이후 숙박 조건 수정으로 무효화할 수 없습니다.
    var stay = stayRequest(1);
    String stayPath = "/trips/" + trip.get("id") + "/lodgings/sample-11/stay";
    assertThat(
      user.request("PUT", stayPath, stay, true).statusCode()
    ).isEqualTo(409);
    stay.put("checkInTime", "06:00");
    var compatible = user.call("PUT", stayPath, stay);
    assertThat(compatible.get("routeTimings")).isEqualTo(
      saved.get("routeTimings")
    );
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

  Map<String, Object> wholeRequest(int version, String strategy) {
    return new HashMap<>(
      Map.of(
        "version",
        version,
        "strategy",
        strategy,
        "startTime",
        "08:00",
        "endTime",
        "21:00",
        "bufferMinutes",
        20
      )
    );
  }

  @Test
  void wholeScheduleComparesAllDaysRecomputesPayloadAndKeepsLodgingAndExpenses()
    throws Exception {
    var user = new Browser().signup();
    var trip = user.trip(hotelConditions());
    String base = "/trips/" + trip.get("id");
    trip = user.call("PUT", base + "/lodgings/sample-11/stay", stayRequest(0));
    user.call(
      "POST",
      base + "/expenses",
      Map.of("label", "간식", "amount", 10000)
    );
    trip = user.call("GET", base, null);
    var request = wholeRequest(1, "EARLY");
    var preview = user.request(
      "POST",
      base + "/schedule/whole/preview",
      request,
      true
    );
    assertThat(preview.statusCode()).as(preview.body()).isEqualTo(200);
    var plans = json.mapper().readTree(preview.body());
    assertThat(plans.get(0).path("days").size()).isEqualTo(2);
    assertThat(plans.get(0).path("canApply").asBoolean()).isTrue();
    assertThat(user.call("GET", base, null)).isEqualTo(trip);
    // 조작된 시각·장소를 보내도 서버가 다시 계산한 방문 시각만 저장해야 합니다.
    request.put(
      "items",
      List.of(Map.of("placeId", "forged", "startTime", "01:00", "cost", 0))
    );
    var saved = user.call("POST", base + "/schedule/whole/apply", request);
    assertThat(json.mapper().valueToTree(saved).path("items")).isEqualTo(
      plans.get(0).path("items")
    );
    assertThat(saved.get("lodgings")).isEqualTo(trip.get("lodgings"));
    assertThat(saved.get("expenses")).isEqualTo(trip.get("expenses"));
    assertThat(saved.get("version")).isEqualTo(2);
    assertThat(
      user
        .request("POST", base + "/schedule/whole/apply", request, true)
        .statusCode()
    ).isEqualTo(409);
    String history = json
      .mapper()
      .valueToTree(saved)
      .path("history")
      .get(0)
      .path("id")
      .asText();
    var restored = user.call(
      "POST",
      base + "/undo",
      Map.of("version", 2, "historyId", history)
    );
    assertThat(items(restored)).isEqualTo(items(trip));
    assertThat(restored.get("lodgings")).isEqualTo(trip.get("lodgings"));
    assertThat(restored.get("expenses")).isEqualTo(trip.get("expenses"));
  }

  @Test
  void wholeScheduleKeepsAppliedRouteAndFixedVisitAndConfirmedBooking()
    throws Exception {
    var user = new Browser().signup();
    var trip = user.trip();
    String base = "/trips/" + trip.get("id");
    trip = user.call(
      "POST",
      base + "/transport/timing/apply",
      routeRequest(user, trip, 0, 1, 45)
    );
    trip = user.call(
      "PUT",
      base + "/booking",
      Map.of(
        "version",
        1,
        "itemId",
        items(trip).get(0).get("id"),
        "status",
        "USER_CONFIRMED",
        "reason",
        "예약 방문",
        "fixed",
        true
      )
    );
    var booking = connectedBooking(trip, 2);
    booking.put("departure", "2026-11-01T06:00");
    booking.put("arrival", "2026-11-01T07:00");
    booking.put("beforeMinutes", 0);
    booking.put("afterMinutes", 0);
    trip = user.call("POST", base + "/external-bookings", booking);
    var request = wholeRequest(3, "LATE");
    var preview = user.request(
      "POST",
      base + "/schedule/whole/preview",
      request,
      true
    );
    var plan = json.mapper().readTree(preview.body()).get(1);
    assertThat(plan.path("canApply").asBoolean()).as(plan.toString()).isTrue();
    var saved = user.call("POST", base + "/schedule/whole/apply", request);
    assertThat(items(saved).get(0)).isEqualTo(items(trip).get(0));
    assertThat(saved.get("routeTimings")).isEqualTo(trip.get("routeTimings"));
    assertThat(saved.get("externalBookings")).isEqualTo(
      trip.get("externalBookings")
    );
    assertThat(
      json.mapper().valueToTree(saved).path("schedule").path("issues")
    ).isEmpty();
  }

  @Test
  void wholeScheduleKeepsStoredOvernightAnchorsAndStayConditions()
    throws Exception {
    var user = new Browser().signup();
    var trip = user.trip(hotelConditions());
    String base = "/trips/" + trip.get("id");
    trip = user.call("PUT", base + "/lodgings/sample-11/stay", stayRequest(0));
    trip = user.call(
      "POST",
      base + "/transport/overnight/apply",
      overnightRequest(user, trip)
    );
    var request = wholeRequest(2, "LATE");
    var preview = user.request(
      "POST",
      base + "/schedule/whole/preview",
      request,
      true
    );
    var plans = json.mapper().readTree(preview.body());
    assertThat(plans.get(1).path("canApply").asBoolean())
      .as(plans.toString())
      .isTrue();
    var saved = user.call("POST", base + "/schedule/whole/apply", request);
    assertThat(saved.get("routeTimings")).isEqualTo(trip.get("routeTimings"));
    assertThat(saved.get("lodgings")).isEqualTo(trip.get("lodgings"));
    assertThat(
      json.mapper().valueToTree(saved).path("schedule").path("issues")
    ).isEmpty();
    assertThat(user.call("GET", base, null).get("routeTimings")).isEqualTo(
      trip.get("routeTimings")
    );
  }

  @Test
  void wholeScheduleRejectsUnauthorizedCsrfInvalidInputAndConcurrentCompanionEdit()
    throws Exception {
    var owner = new Browser().signup();
    var companion = new Browser().signup();
    var trip = owner.trip();
    String base = "/trips/" + trip.get("id");
    var request = wholeRequest(0, "EARLY");
    assertThat(
      new Browser()
        .request("POST", base + "/schedule/whole/preview", request, true)
        .statusCode()
    ).isEqualTo(401);
    assertThat(
      companion
        .request("POST", base + "/schedule/whole/preview", request, true)
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      owner
        .request("POST", base + "/schedule/whole/apply", request, false)
        .statusCode()
    ).isEqualTo(403);
    for (String key : List.of(
      "startTime",
      "endTime",
      "bufferMinutes",
      "strategy"
    )) {
      var invalid = wholeRequest(0, "EARLY");
      invalid.put(key, key.equals("bufferMinutes") ? 1.5 : "invalid");
      assertThat(
        owner
          .request("POST", base + "/schedule/whole/apply", invalid, true)
          .statusCode()
      ).isEqualTo(400);
    }
    owner.call(
      "POST",
      "/invitations",
      Map.of("tripId", trip.get("id"), "email", companion.email)
    );
    var invitations = json
      .mapper()
      .readTree(companion.request("GET", "/invitations", null, false).body());
    companion.call(
      "POST",
      "/invitations/" + invitations.get(0).path("id").asText() + "/respond",
      Map.of("accept", true)
    );
    // 초대 수락도 여행 version을 갱신하므로 최신 버전으로 비교를 시작합니다.
    int version = (
      (Number) owner.call("GET", base, null).get("version")
    ).intValue();
    request.put("version", version);
    var preview = companion.request(
      "POST",
      base + "/schedule/whole/preview",
      request,
      true
    );
    assertThat(preview.statusCode()).isEqualTo(200);
    var changed = owner.call(
      "POST",
      base + "/external-bookings",
      connectedBooking(trip, version)
    );
    assertThat(
      companion
        .request("POST", base + "/schedule/whole/apply", request, true)
        .statusCode()
    ).isEqualTo(409);
    assertThat(
      companion
        .request("POST", base + "/schedule/whole/preview", request, true)
        .statusCode()
    ).isEqualTo(409);
    assertThat(owner.call("GET", base, null)).isEqualTo(changed);
    // 고정 방문과 겹치는 예약은 두 방향 모두 차단하고 DB를 그대로 보존해야 합니다.
    var fixed = owner.call(
      "PUT",
      base + "/booking",
      Map.of(
        "version",
        version + 1,
        "itemId",
        items(trip).getFirst().get("id"),
        "status",
        "USER_CONFIRMED",
        "reason",
        "고정 방문",
        "fixed",
        true
      )
    );
    assertThat(
      companion
        .request(
          "POST",
          base + "/schedule/whole/apply",
          wholeRequest(version + 2, "EARLY"),
          true
        )
        .statusCode()
    ).isEqualTo(409);
    assertThat(
      companion
        .request(
          "POST",
          base + "/schedule/whole/apply",
          wholeRequest(version + 2, "LATE"),
          true
        )
        .statusCode()
    ).isEqualTo(409);
    assertThat(owner.call("GET", base, null)).isEqualTo(fixed);
  }

  @Test
  void publicTourismReadsValidateInputsAndMissingKeyWithoutChangingTrips()
    throws Exception {
    var visitor = new Browser();
    var config = visitor.call("GET", "/config", null);
    assertThat(config.get("tourismConfigured")).isEqualTo(false);
    assertThat(config).doesNotContainKeys(
      "tourApiKey",
      "tour-api-key",
      "serviceKey"
    );
    for (var path : List.of(
      "/catalog/tourism/search?region=invalid&query=palace",
      "/catalog/tourism/search?region=invalid&query=palace&page=51",
      "/catalog/tourism/not-a-content-id"
    ))
      assertThat(
        visitor.request("GET", path, null, false).statusCode()
      ).isEqualTo(400);
    var unavailable = visitor.request(
      "GET",
      "/catalog/tourism/search?region=" +
        java.net.URLEncoder.encode(
          "서울",
          java.nio.charset.StandardCharsets.UTF_8
        ) +
        "&query=palace",
      null,
      false
    );
    assertThat(unavailable.statusCode()).isEqualTo(503);
    assertThat(unavailable.body()).contains("연결을 준비 중");
    var owner = new Browser().signup();
    var trip = owner.trip();
    assertThat(
      owner.request("GET", "/catalog/tourism/123", null, false).statusCode()
    ).isEqualTo(503);
    assertThat(owner.call("GET", "/trips/" + trip.get("id"), null)).isEqualTo(
      trip
    );
  }

  /** 조건은 결제/방문 시간을 자동 변경하지 않고, 이후 비교와 복원에서 동일하게 보호합니다. */
  @Test
  void visitConditionsPersistAndWholeScheduleHonorsWindowAndUndoKeepsLatestConditions()
    throws Exception {
    var user = new Browser().signup();
    var trip = user.trip();
    String base = "/trips/" + trip.get("id");
    String itemId = items(trip).getFirst().get("id").toString();
    var request = visitRequest(0, "12:00", "20:00", 35000);
    var saved = user.call(
      "PUT",
      base + "/items/" + itemId + "/conditions",
      request
    );
    assertThat(saved.get("version")).isEqualTo(1);
    assertThat(saved.get("history")).isEqualTo(trip.get("history"));
    assertThat(saved.get("expenses")).isEqualTo(trip.get("expenses"));
    assertThat(items(saved).getFirst().get("startTime")).isEqualTo(
      items(trip).getFirst().get("startTime")
    );
    assertThat(items(saved).getFirst().get("visitCost")).isEqualTo(35000);
    assertThat(items(saved).getFirst().get("bookingStatus")).isEqualTo("NONE");
    assertThat((List<?>) saved.get("visitConditionIssues")).isNotEmpty();
    var plansResponse = user.request(
      "POST",
      base + "/schedule/whole/preview",
      wholeRequest(1, "EARLY"),
      true
    );
    assertThat(plansResponse.statusCode())
      .as(plansResponse.body())
      .isEqualTo(200);
    var plans = json.mapper().readTree(plansResponse.body());
    assertThat(plans.get(0).path("canApply").asBoolean()).isTrue();
    assertThat(
      plans.get(0).path("items").get(0).path("startTime").asText()
    ).isEqualTo("12:00");
    var applied = user.call(
      "POST",
      base + "/schedule/whole/apply",
      wholeRequest(1, "EARLY")
    );
    assertThat(items(applied).getFirst().get("visitCost")).isEqualTo(35000);
    assertThat((List<?>) applied.get("visitConditionIssues")).isEmpty();
    String history = json
      .mapper()
      .valueToTree(applied)
      .path("history")
      .get(0)
      .path("id")
      .asText();
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
    var cleared = user.call(
      "PUT",
      base + "/items/" + itemId + "/conditions",
      visitRequest(2, null, null, 0)
    );
    var restored = user.call(
      "POST",
      base + "/undo",
      Map.of("version", 3, "historyId", history)
    );
    assertThat(items(restored).getFirst().get("visitCost")).isEqualTo(0);
    assertThat(items(restored).getFirst().get("visitStart")).isNull();
    assertThat(restored.get("expenses")).isEqualTo(cleared.get("expenses"));
    var share = user.call("POST", base + "/share", Map.of("enabled", true));
    String publicJson = new Browser()
      .request("GET", "/shared/" + share.get("token"), null, false)
      .body();
    assertThat(publicJson).doesNotContain(
      "visitStart",
      "visitEnd",
      "visitCost",
      "visitNote",
      "직접 확인 메모"
    );
  }

  @Test
  void visitConditionsValidateMembershipCsrfWindowAndExactMoneyWithoutMutation()
    throws Exception {
    var user = new Browser().signup();
    var outsider = new Browser().signup();
    var trip = user.trip();
    String base = "/trips/" + trip.get("id");
    String path =
      base + "/items/" + items(trip).getFirst().get("id") + "/conditions";
    var valid = visitRequest(0, "09:00", "21:00", 0);
    assertThat(
      new Browser().request("PUT", path, valid, true).statusCode()
    ).isEqualTo(401);
    assertThat(
      outsider.request("PUT", path, valid, true).statusCode()
    ).isEqualTo(403);
    assertThat(user.request("PUT", path, valid, false).statusCode()).isEqualTo(
      403
    );
    assertThat(
      user
        .request("PUT", base + "/items/foreign/conditions", valid, true)
        .statusCode()
    ).isEqualTo(404);
    for (var invalid : List.of(
      visitRequest(0, "09:00", null, 0),
      visitRequest(0, "21:00", "09:00", 0),
      visitRequest(0, "09:00", "09:01", 0),
      visitRequest(0, "05:00", "22:00", 0),
      visitRequest(0, null, null, 1.5),
      visitRequest(0, null, null, -1),
      visitRequest(0, null, null, 500001),
      visitRequest(-1, null, null, 0)
    )) {
      assertThat(user.request("PUT", path, invalid, true).statusCode())
        .as(invalid.toString())
        .isEqualTo(400);
    }
    var longNote = visitRequest(0, null, null, null);
    longNote.put("note", "가".repeat(501));
    assertThat(
      user.request("PUT", path, longNote, true).statusCode()
    ).isEqualTo(400);
    assertThat(user.call("GET", base, null)).isEqualTo(trip);
    var saved = user.call("PUT", path, valid);
    assertThat(user.request("PUT", path, valid, true).statusCode()).isEqualTo(
      409
    );
    assertThat(user.call("GET", base, null)).isEqualTo(saved);
  }

  @Test
  void visitConditionsResetOnPlaceChangeAndPreviewUsesEnteredCost()
    throws Exception {
    var user = new Browser().signup();
    var trip = user.trip();
    String base = "/trips/" + trip.get("id");
    String itemId = items(trip).getFirst().get("id").toString();
    var saved = user.call(
      "PUT",
      base + "/items/" + itemId + "/conditions",
      visitRequest(0, "09:00", "21:00", 450000)
    );
    var alternatives = json
      .mapper()
      .readTree(
        user
          .request("GET", base + "/alternatives/" + itemId, null, false)
          .body()
      );
    var change = Map.of(
      "version",
      1,
      "itemId",
      itemId,
      "placeId",
      alternatives.get(0).path("id").asText()
    );
    var preview = user.call("POST", base + "/preview", change);
    var target = items(preview).getFirst();
    assertThat(target.get("visitStart")).isNull();
    assertThat(target.get("visitCost")).isNull();
    assertThat(target.get("visitNote")).isEqualTo("");
    int newCost = ((Number) target.get("cost")).intValue();
    assertThat(preview.get("costDelta")).isEqualTo((newCost - 450000) * 2);
    assertThat(preview.get("canApply")).isEqualTo(true);
    var applied = user.call("POST", base + "/apply", change);
    assertThat(items(applied).getFirst().get("visitCost")).isNull();
    String history = json
      .mapper()
      .valueToTree(applied)
      .path("history")
      .get(0)
      .path("id")
      .asText();
    var restored = user.call(
      "POST",
      base + "/undo",
      Map.of("version", 2, "historyId", history)
    );
    assertThat(items(restored).getFirst().get("placeId")).isEqualTo(
      items(saved).getFirst().get("placeId")
    );
    assertThat(items(restored).getFirst().get("visitCost")).isNull();
  }

  @Test
  void companionCanEditVisitConditionsButCannotOverwriteNewerVersion()
    throws Exception {
    var owner = new Browser().signup();
    var companion = new Browser().signup();
    var trip = owner.trip();
    String base = "/trips/" + trip.get("id");
    String path =
      base + "/items/" + items(trip).getFirst().get("id") + "/conditions";
    owner.call(
      "POST",
      "/invitations",
      Map.of("tripId", trip.get("id"), "email", companion.email)
    );
    var invites = json
      .mapper()
      .readTree(companion.request("GET", "/invitations", null, false).body());
    companion.call(
      "POST",
      "/invitations/" + invites.get(0).path("id").asText() + "/respond",
      Map.of("accept", true)
    );
    int version = (
      (Number) owner.call("GET", base, null).get("version")
    ).intValue();
    var request = visitRequest(version, "09:00", "21:00", null);
    var saved = companion.call("PUT", path, request);
    assertThat(
      owner.request("PUT", path, request, true).statusCode()
    ).isEqualTo(409);
    assertThat(owner.call("GET", base, null)).isEqualTo(saved);
  }

  @Test
  void fixedVisitAndRouteChangesCannotBypassEnteredVisitWindow()
    throws Exception {
    var user = new Browser().signup();
    var trip = user.trip();
    String base = "/trips/" + trip.get("id");
    String itemId = items(trip).getFirst().get("id").toString();
    var fixed = user.call(
      "PUT",
      base + "/booking",
      Map.of(
        "version",
        0,
        "itemId",
        itemId,
        "status",
        "USER_CONFIRMED",
        "reason",
        "고정",
        "fixed",
        true
      )
    );
    var saved = user.call(
      "PUT",
      base + "/items/" + itemId + "/conditions",
      visitRequest(1, "12:00", "20:00", 10000)
    );
    var plans = json
      .mapper()
      .readTree(
        user
          .request(
            "POST",
            base + "/schedule/whole/preview",
            wholeRequest(2, "EARLY"),
            true
          )
          .body()
      );
    assertThat(plans.get(0).path("canApply").asBoolean()).isFalse();
    assertThat(plans.get(1).path("canApply").asBoolean()).isFalse();
    assertThat(
      user
        .request(
          "POST",
          base + "/schedule/whole/apply",
          wholeRequest(2, "EARLY"),
          true
        )
        .statusCode()
    ).isEqualTo(409);
    // 고정을 해제해도 방문 가능 시간 자체가 경로 비교의 최종 검사에 남아야 합니다.
    saved = user.call(
      "PUT",
      base + "/booking",
      Map.of(
        "version",
        2,
        "itemId",
        itemId,
        "status",
        "CANCELLED",
        "reason",
        "검증용 고정 해제",
        "fixed",
        false
      )
    );
    var route = routeRequest(user, saved, 0, 1, 45);
    var routePreview = user.request(
      "POST",
      base + "/transport/timing/preview",
      route,
      true
    );
    assertThat(routePreview.statusCode())
      .as(routePreview.body())
      .isEqualTo(200);
    var routePlans = json.mapper().readTree(routePreview.body());
    assertThat(routePreview.body()).contains("직접 입력한 가능 시간");
    assertThat(routePlans.get(0).path("canApply").asBoolean()).isFalse();
    assertThat(routePlans.get(1).path("canApply").asBoolean()).isFalse();
    assertThat(user.call("GET", base, null)).isEqualTo(saved);
  }

  /** 초대는 항상 실제 HTTP로 수락합니다. DB에 참여자를 직접 넣으면 초대 상태·취향 병합을 놓치게 됩니다. */
  String join(Browser owner, Browser companion, String trip) throws Exception {
    owner.call(
      "POST",
      "/invitations",
      Map.of("tripId", trip, "email", companion.email)
    );
    var invitations = json
      .mapper()
      .readTree(companion.request("GET", "/invitations", null, false).body());
    String invite = invitations.get(0).path("id").asText();
    companion.call(
      "POST",
      "/invitations/" + invite + "/respond",
      Map.of("accept", true)
    );
    return invite;
  }

  @Test
  void leavingRevokesPrivateTripAndCreatorGroupAccessButPreservesRecords()
    throws Exception {
    var owner = new Browser().signup();
    var companion = new Browser().signup();
    var trip = owner.trip();
    String id = trip.get("id").toString(),
      base = "/trips/" + id;
    String invite = join(owner, companion, id);
    // 나가는 동행이 방을 만들었습니다. requester_id로 목록 접근이 남는 회귀를 재현합니다.
    var room = companion.call("POST", "/messages/trip/" + id, Map.of());
    String roomId = room.get("id").toString();
    companion.call(
      "POST",
      "/messages/" + roomId,
      Map.of("body", "출발 전에 남긴 기록")
    );
    companion.call(
      "POST",
      base + "/expenses",
      Map.of("label", "함께 준비한 간식", "amount", 12000)
    );
    owner.call(
      "PUT",
      base + "/booking",
      Map.of(
        "version",
        1,
        "itemId",
        items(trip).getFirst().get("id"),
        "status",
        "USER_CONFIRMED",
        "reason",
        "직접 확인한 예약 기록 보존",
        "fixed",
        true
      )
    );
    String companionId = companion
      .call("GET", "/auth/me", null)
      .get("id")
      .toString();
    String token = owner
      .call("POST", base + "/share", Map.of("enabled", true))
      .get("token")
      .toString();
    var saved = owner.call("GET", base, null);
    int version = ((Number) saved.get("version")).intValue();
    assertThat(
      companion
        .request("POST", base + "/leave", Map.of("version", version), false)
        .statusCode()
    ).isEqualTo(403);
    companion.call("POST", base + "/leave", Map.of("version", version));
    var after = owner.call("GET", base, null);
    assertThat(after.get("version")).isEqualTo(version + 1);
    for (String key : List.of(
      "items",
      "tasks",
      "expenses",
      "history",
      "lodgings",
      "externalBookings",
      "partySize",
      "budget",
      "preferences"
    ))
      assertThat(after.get(key))
        .as(key + " is preserved")
        .isEqualTo(saved.get(key));
    assertThat(json.write(after.get("members"))).doesNotContain(companionId);
    assertThat(
      json.mapper().valueToTree(after.get("members")).size()
    ).isEqualTo(1);
    assertThat(
      companion.request("GET", base, null, false).statusCode()
    ).isEqualTo(403);
    assertThat(
      companion.request("GET", "/trips", null, false).body()
    ).doesNotContain(id);
    assertThat(
      companion.request("GET", "/messages", null, false).body()
    ).doesNotContain(roomId);
    assertThat(
      companion.request("GET", "/messages/" + roomId, null, false).statusCode()
    ).isEqualTo(403);
    assertThat(
      companion
        .request(
          "POST",
          "/messages/" + roomId,
          Map.of("body", "나간 뒤 전송"),
          true
        )
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      companion
        .request("POST", "/messages/trip/" + id, Map.of(), true)
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      companion
        .request("POST", base + "/leave", Map.of("version", version + 1), true)
        .statusCode()
    ).isEqualTo(403);
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
    assertThat(
      json.write(owner.call("GET", "/messages/" + roomId, null))
    ).contains("출발 전에 남긴 기록");
    var shared = new Browser().call("GET", "/share/" + token, null);
    assertThat(shared).doesNotContainKeys(
      "members",
      "expenses",
      "origin",
      "ownerId"
    );
    // 새 초대를 수락해야만 같은 방과 여행 기록에 다시 접근할 수 있습니다.
    assertThat(join(owner, companion, id)).isNotEqualTo(invite);
    assertThat(
      companion.call("POST", "/messages/trip/" + id, Map.of()).get("id")
    ).isEqualTo(roomId);
    assertThat(
      companion.request("GET", "/messages", null, false).body()
    ).contains(roomId);
    assertThat(companion.call("GET", base, null).get("expenses")).isEqualTo(
      saved.get("expenses")
    );
  }

  @Test
  void onlyOwnerCanRemoveCurrentCompanionAndStaleChoicesCannotRevokeAccess()
    throws Exception {
    var owner = new Browser().signup();
    var companion = new Browser().signup();
    var remaining = new Browser().signup();
    var trip = owner.trip();
    String id = trip.get("id").toString(),
      base = "/trips/" + id;
    join(owner, companion, id);
    join(owner, remaining, id);
    String target = companion
      .call("GET", "/auth/me", null)
      .get("id")
      .toString();
    String ownerId = trip.get("ownerId").toString();
    var saved = owner.call("GET", base, null);
    int version = ((Number) saved.get("version")).intValue();
    var route = routeRequest(companion, saved, 0, 1, 60);
    assertThat(
      companion
        .request("POST", base + "/transport/timing/preview", route, true)
        .statusCode()
    ).isEqualTo(200);
    String remove = base + "/members/" + target + "/remove";
    assertThat(
      companion
        .request("POST", remove, Map.of("version", version), true)
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      owner
        .request("POST", base + "/leave", Map.of("version", version), true)
        .statusCode()
    ).isEqualTo(409);
    assertThat(
      owner
        .request(
          "POST",
          base + "/members/" + ownerId + "/remove",
          Map.of("version", version),
          true
        )
        .statusCode()
    ).isEqualTo(409);
    assertThat(
      owner.request("POST", remove, Map.of("version", -1), true).statusCode()
    ).isEqualTo(400);
    assertThat(
      owner
        .request("POST", remove, Map.of("version", version - 1), true)
        .statusCode()
    ).isEqualTo(409);
    assertThat(
      companion
        .request("POST", base + "/leave", Map.of("version", version - 1), true)
        .statusCode()
    ).isEqualTo(409);
    assertThat(companion.call("GET", base, null)).isEqualTo(saved);
    var changed = owner.call("POST", remove, Map.of("version", version));
    assertThat(changed.get("version")).isEqualTo(version + 1);
    assertThat(
      json.mapper().valueToTree(changed.get("members")).size()
    ).isEqualTo(2);
    assertThat(
      remaining.request("GET", base, null, false).statusCode()
    ).isEqualTo(200);
    // 미리 열어 둔 비교·예약·준비물 양식도 다음 요청에서 권한을 새로 검사합니다.
    assertThat(
      companion
        .request("POST", base + "/transport/timing/apply", route, true)
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      companion
        .request(
          "POST",
          base + "/schedule/whole/preview",
          wholeRequest(version, "EARLIER"),
          true
        )
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      companion
        .request(
          "POST",
          base + "/tasks",
          Map.of("content", "나간 뒤 준비물"),
          true
        )
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      companion
        .request(
          "POST",
          base + "/expenses",
          Map.of("label", "나간 뒤 지출", "amount", 1),
          true
        )
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      companion
        .request(
          "PUT",
          base + "/booking",
          Map.of(
            "version",
            version,
            "itemId",
            items(saved).getFirst().get("id"),
            "status",
            "USER_CONFIRMED",
            "reason",
            "검증",
            "fixed",
            true
          ),
          true
        )
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      companion
        .request(
          "PUT",
          base + "/items/" + items(saved).getFirst().get("id") + "/conditions",
          visitRequest(version, "10:00", "20:00", 0),
          true
        )
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      owner
        .request("POST", remove, Map.of("version", version + 1), true)
        .statusCode()
    ).isEqualTo(404);
    assertThat(owner.call("GET", base, null)).isEqualTo(changed);
  }

  @Test
  void leavingKeepsAcceptedDmButEndsCompanionOnlyEligibilityForNewRequests()
    throws Exception {
    var owner = new Browser().signup();
    var companion = new Browser().signup();
    var another = new Browser().signup();
    var trip = owner.trip();
    String id = trip.get("id").toString(),
      base = "/trips/" + id;
    join(owner, companion, id);
    join(owner, another, id);
    owner.call(
      "PUT",
      "/auth/profile",
      Map.of(
        "nickname",
        "소유자",
        "preferences",
        List.of(),
        "dmScope",
        "COMPANIONS"
      )
    );
    another.call(
      "PUT",
      "/auth/profile",
      Map.of(
        "nickname",
        "다른 동행",
        "preferences",
        List.of(),
        "dmScope",
        "COMPANIONS"
      )
    );
    var dm = companion.call(
      "POST",
      "/messages",
      Map.of("email", owner.email, "body", "개인 대화 요청")
    );
    String room = dm.get("id").toString();
    owner.call(
      "POST",
      "/messages/" + room + "/respond",
      Map.of("accept", true)
    );
    var saved = companion.call("GET", base, null);
    companion.call(
      "POST",
      base + "/leave",
      Map.of("version", saved.get("version"))
    );
    assertThat(
      companion
        .call(
          "POST",
          "/messages/" + room,
          Map.of("body", "개인 대화는 별도 유지")
        )
        .get("id")
    ).isEqualTo(room);
    assertThat(
      companion.request("GET", "/messages", null, false).body()
    ).contains(room);
    assertThat(
      companion
        .request(
          "POST",
          "/messages",
          Map.of("email", another.email, "body", "새 대화 요청"),
          true
        )
        .statusCode()
    ).isEqualTo(403);
    join(owner, companion, id);
    assertThat(
      companion
        .request(
          "POST",
          "/messages",
          Map.of("email", another.email, "body", "다시 동행으로 대화 요청"),
          true
        )
        .statusCode()
    ).isEqualTo(200);
  }

  @Test
  void concurrentRemovalAndGroupSendSerializeAndLaterWritesAreDenied()
    throws Exception {
    var owner = new Browser().signup();
    var companion = new Browser().signup();
    var trip = owner.trip();
    String id = trip.get("id").toString(),
      base = "/trips/" + id;
    join(owner, companion, id);
    String target = companion
      .call("GET", "/auth/me", null)
      .get("id")
      .toString();
    String room = companion
      .call("POST", "/messages/trip/" + id, Map.of())
      .get("id")
      .toString();
    var saved = owner.call("GET", base, null);
    var barrier = new java.util.concurrent.CyclicBarrier(2);
    var send = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
      try {
        barrier.await();
        return companion.request(
          "POST",
          "/messages/" + room,
          Map.of("body", "동시 전송 기록"),
          true
        );
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    var remove = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
      try {
        barrier.await();
        return owner.request(
          "POST",
          base + "/members/" + target + "/remove",
          Map.of("version", saved.get("version")),
          true
        );
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    var removal = remove.get(20, java.util.concurrent.TimeUnit.SECONDS);
    var sent = send.get(20, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(removal.statusCode()).as(removal.body()).isEqualTo(200);
    // 먼저 잠근 전송만 완료할 수 있습니다. 해제 이후 잠금을 얻은 전송은 403이어야 합니다.
    assertThat(sent.statusCode()).as(sent.body()).isIn(200, 403);
    assertThat(
      companion
        .request(
          "POST",
          "/messages/" + room,
          Map.of("body", "해제 완료 뒤 전송"),
          true
        )
        .statusCode()
    ).isEqualTo(403);
    var messages = owner.call("GET", "/messages/" + room, null).get("messages");
    assertThat(json.write(messages)).doesNotContain("해제 완료 뒤 전송");
    assertThat(json.mapper().valueToTree(messages).size()).isEqualTo(
      sent.statusCode() == 200 ? 1 : 0
    );
  }

  Map<String, Object> visitRequest(
    int version,
    String start,
    String end,
    Object cost
  ) {
    var result = new HashMap<String, Object>();
    result.put("version", version);
    result.put("startTime", start);
    result.put("endTime", end);
    result.put("cost", cost);
    result.put("note", "직접 확인 메모");
    return result;
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

  @Test
  void ownershipTransferPreservesTripAndMovesOnlyManagementRights()
    throws Exception {
    var owner = new Browser().signup();
    var next = new Browser().signup();
    var pending = new Browser().signup();
    var trip = owner.trip(hotelConditions());
    String id = trip.get("id").toString(),
      base = "/trips/" + id;
    join(owner, next, id);
    String nextId = next.call("GET", "/auth/me", null).get("id").toString();
    owner.call(
      "PUT",
      base + "/booking",
      Map.of(
        "version",
        1,
        "itemId",
        items(trip).getFirst().get("id"),
        "status",
        "USER_CONFIRMED",
        "reason",
        "직접 확인한 예약 보존",
        "fixed",
        true
      )
    );
    owner.call(
      "POST",
      "/invitations",
      Map.of("tripId", id, "email", pending.email)
    );
    var invitations = json
      .mapper()
      .readTree(pending.request("GET", "/invitations", null, false).body());
    String oldInvite = invitations.get(0).path("id").asText();
    next.call(
      "POST",
      base + "/expenses",
      Map.of("label", "남겨 둘 경비", "amount", 12000)
    );
    String room = owner
      .call("POST", "/messages/trip/" + id, Map.of())
      .get("id")
      .toString();
    owner.call(
      "POST",
      "/messages/" + room,
      Map.of("body", "소유권 이전 전 단체 대화")
    );
    String token = owner
      .call("POST", base + "/share", Map.of("enabled", true))
      .get("token")
      .toString();
    var saved = owner.call("GET", base, null);
    int version = ((Number) saved.get("version")).intValue();
    var after = owner.call(
      "POST",
      base + "/ownership/transfer",
      Map.of("memberId", nextId, "version", version)
    );
    assertThat(after)
      .containsEntry("ownerId", nextId)
      .containsEntry("version", version + 1);
    for (String key : List.of(
      "members",
      "items",
      "lodgings",
      "externalBookings",
      "routeTimings",
      "tasks",
      "expenses",
      "history",
      "partySize",
      "preferences",
      "budget"
    ))
      assertThat(after.get(key))
        .as(key + " preserved")
        .isEqualTo(saved.get(key));
    var log = json.mapper().valueToTree(after.get("ownershipHistory"));
    assertThat(log.size()).isEqualTo(1);
    assertThat(log.get(0).path("previousOwnerId").asText()).isEqualTo(
      saved.get("ownerId")
    );
    assertThat(log.get(0).path("newOwnerId").asText()).isEqualTo(nextId);
    assertThat(log.get(0).path("revokedInvitations").asInt()).isEqualTo(1);
    assertThat(log.get(0).path("version").asInt()).isEqualTo(version + 1);
    assertThat(next.call("GET", base, null)).isEqualTo(after);
    assertThat(
      pending
        .request(
          "POST",
          "/invitations/" + oldInvite + "/respond",
          Map.of("accept", true),
          true
        )
        .statusCode()
    ).isEqualTo(409);
    assertThat(
      pending.request("GET", base, null, false).statusCode()
    ).isEqualTo(403);
    assertThat(
      json
        .mapper()
        .readTree(pending.request("GET", "/invitations", null, false).body())
        .size()
    ).isZero();
    assertThat(
      owner
        .request(
          "POST",
          "/invitations",
          Map.of("tripId", id, "email", pending.email),
          true
        )
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      owner
        .request("POST", base + "/share", Map.of("enabled", false), true)
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      owner.request("DELETE", base, null, true).statusCode()
    ).isEqualTo(403);
    assertThat(
      owner
        .request(
          "POST",
          base + "/members/" + nextId + "/remove",
          Map.of("version", version + 1),
          true
        )
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      owner
        .request(
          "POST",
          base + "/ownership/transfer",
          Map.of("memberId", saved.get("ownerId"), "version", version + 1),
          true
        )
        .statusCode()
    ).isEqualTo(403);
    owner.call(
      "POST",
      base + "/tasks",
      Map.of("content", "이전 소유자의 공동 편집")
    );
    next.call(
      "POST",
      "/messages/" + room,
      Map.of("body", "새 소유자도 같은 방 유지")
    );
    owner.call(
      "POST",
      "/messages/" + room,
      Map.of("body", "이전 소유자도 동행으로 유지")
    );
    var shared = new Browser().call("GET", "/share/" + token, null);
    assertThat(shared).doesNotContainKeys(
      "ownershipHistory",
      "ownerId",
      "members",
      "expenses"
    );
    // 새 소유자가 초대를 다시 발급한 뒤에만 기존 대기자가 참여할 수 있습니다.
    assertThat(join(next, pending, id)).isNotEqualTo(oldInvite);
    next.call("POST", base + "/share", Map.of("enabled", false));
    assertThat(
      new Browser().request("GET", "/share/" + token, null, false).statusCode()
    ).isEqualTo(404);
  }

  @Test
  void ownershipTransferRequiresOwnerCurrentMemberCsrfAndExplicitVersion()
    throws Exception {
    var owner = new Browser().signup();
    var other = new Browser().signup();
    var outsider = new Browser().signup();
    var trip = owner.trip();
    String id = trip.get("id").toString(),
      base = "/trips/" + id,
      path = base + "/ownership/transfer";
    join(owner, other, id);
    String target = other.call("GET", "/auth/me", null).get("id").toString();
    String external = outsider
      .call("GET", "/auth/me", null)
      .get("id")
      .toString();
    var valid = Map.of("version", 1, "memberId", target);
    assertThat(
      new Browser().request("POST", path, valid, true).statusCode()
    ).isEqualTo(401);
    assertThat(other.request("POST", path, valid, true).statusCode()).isEqualTo(
      403
    );
    assertThat(
      outsider.request("POST", path, valid, true).statusCode()
    ).isEqualTo(403);
    assertThat(
      owner.request("POST", path, valid, false).statusCode()
    ).isEqualTo(403);
    assertThat(
      owner.request("POST", path, Map.of("memberId", target), true).statusCode()
    ).isEqualTo(400);
    var nullVersion = new HashMap<String, Object>();
    nullVersion.put("version", null);
    nullVersion.put("memberId", target);
    assertThat(
      owner.request("POST", path, nullVersion, true).statusCode()
    ).isEqualTo(400);
    for (Object data : List.of(
      Map.of("version", -1, "memberId", target),
      Map.of("version", 1),
      Map.of("version", 1, "memberId", " "),
      Map.of("version", 1, "memberId", "x".repeat(37)),
      Map.of("version", 1, "memberId", trip.get("ownerId"))
    ))
      assertThat(
        owner.request("POST", path, data, true).statusCode()
      ).isEqualTo(400);
    assertThat(
      owner
        .request("POST", path, Map.of("version", 0, "memberId", target), true)
        .statusCode()
    ).isEqualTo(409);
    assertThat(
      owner
        .request("POST", path, Map.of("version", 1, "memberId", external), true)
        .statusCode()
    ).isEqualTo(404);
    // 초대 전/대기 중/나간 동행 모두 현재 참여자가 아닙니다.
    owner.call(
      "POST",
      "/invitations",
      Map.of("tripId", id, "email", outsider.email)
    );
    assertThat(
      owner
        .request("POST", path, Map.of("version", 1, "memberId", external), true)
        .statusCode()
    ).isEqualTo(404);
    other.call("POST", base + "/leave", Map.of("version", 1));
    assertThat(
      owner
        .request("POST", path, Map.of("version", 2, "memberId", target), true)
        .statusCode()
    ).isEqualTo(404);
    assertThat(
      json
        .mapper()
        .valueToTree(owner.call("GET", base, null).get("ownershipHistory"))
        .size()
    ).isZero();
  }

  @Test
  void simultaneousOwnershipTransfersHaveExactlyOneOwnerAndOneRecord()
    throws Exception {
    var owner = new Browser().signup();
    var a = new Browser().signup();
    var b = new Browser().signup();
    var trip = owner.trip();
    String id = trip.get("id").toString(),
      base = "/trips/" + id;
    join(owner, a, id);
    join(owner, b, id);
    String aId = a.call("GET", "/auth/me", null).get("id").toString(),
      bId = b.call("GET", "/auth/me", null).get("id").toString();
    var gate = new java.util.concurrent.CyclicBarrier(2);
    var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
      try {
        gate.await();
        return owner.request(
          "POST",
          base + "/ownership/transfer",
          Map.of("version", 2, "memberId", aId),
          true
        );
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
      try {
        gate.await();
        return owner.request(
          "POST",
          base + "/ownership/transfer",
          Map.of("version", 2, "memberId", bId),
          true
        );
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    var results = List.of(
      first.get(20, java.util.concurrent.TimeUnit.SECONDS),
      second.get(20, java.util.concurrent.TimeUnit.SECONDS)
    );
    assertThat(
      results.stream().map(HttpResponse::statusCode)
    ).containsExactlyInAnyOrder(200, 403);
    var after = owner.call("GET", base, null);
    assertThat(after.get("ownerId")).isIn(aId, bId);
    assertThat(after.get("version")).isEqualTo(3);
    assertThat(
      json.mapper().valueToTree(after.get("ownershipHistory")).size()
    ).isEqualTo(1);
    assertThat(
      json.mapper().valueToTree(after.get("members")).size()
    ).isEqualTo(3);
  }

  @Test
  void ownershipTransferAndTargetLeavingCannotCreateOwnerWithoutMembership()
    throws Exception {
    var owner = new Browser().signup();
    var target = new Browser().signup();
    var trip = owner.trip();
    String id = trip.get("id").toString(),
      base = "/trips/" + id;
    join(owner, target, id);
    String targetId = target.call("GET", "/auth/me", null).get("id").toString();
    var gate = new java.util.concurrent.CyclicBarrier(2);
    var transfer = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
      try {
        gate.await();
        return owner.request(
          "POST",
          base + "/ownership/transfer",
          Map.of("version", 1, "memberId", targetId),
          true
        );
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    var leave = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
      try {
        gate.await();
        return target.request(
          "POST",
          base + "/leave",
          Map.of("version", 1),
          true
        );
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    var moved = transfer.get(20, java.util.concurrent.TimeUnit.SECONDS);
    var left = leave.get(20, java.util.concurrent.TimeUnit.SECONDS);
    var after = owner.call("GET", base, null);
    assertThat(after.get("version")).isEqualTo(2);
    var members = json.mapper().valueToTree(after.get("members"));
    var log = json.mapper().valueToTree(after.get("ownershipHistory"));
    if (moved.statusCode() == 200) {
      assertThat(left.statusCode()).isEqualTo(409);
      assertThat(after.get("ownerId")).isEqualTo(targetId);
      assertThat(members.size()).isEqualTo(2);
      assertThat(log.size()).isEqualTo(1);
    } else {
      assertThat(moved.statusCode()).isEqualTo(404);
      assertThat(left.statusCode()).isEqualTo(200);
      assertThat(after.get("ownerId")).isEqualTo(trip.get("ownerId"));
      assertThat(members.size()).isEqualTo(1);
      assertThat(log.size()).isZero();
    }
  }

  @Test
  void visitHistoryRestoreNeverRestoresOldOwnershipAndFormerOwnerCanLeave()
    throws Exception {
    var owner = new Browser().signup();
    var next = new Browser().signup();
    var trip = owner.trip();
    String id = trip.get("id").toString(),
      base = "/trips/" + id;
    join(owner, next, id);
    String nextId = next.call("GET", "/auth/me", null).get("id").toString();
    var changed = owner.call(
      "PUT",
      base + "/booking",
      Map.of(
        "version",
        1,
        "itemId",
        items(trip).getFirst().get("id"),
        "status",
        "FAILED",
        "reason",
        "소유권 변경 뒤에도 보존할 예약",
        "fixed",
        false
      )
    );
    var history = json.mapper().valueToTree(changed.get("history"));
    String historyId = history.get(0).path("id").asText();
    var transferred = owner.call(
      "POST",
      base + "/ownership/transfer",
      Map.of("version", 2, "memberId", nextId)
    );
    // 방문 이력 복원은 원래 방문만 복원합니다. 소유자·권한 변경 기록은 되돌리지 않습니다.
    var restored = owner.call(
      "POST",
      base + "/undo",
      Map.of("version", 3, "historyId", historyId)
    );
    assertThat(restored.get("ownerId")).isEqualTo(nextId);
    assertThat(restored.get("ownershipHistory")).isEqualTo(
      transferred.get("ownershipHistory")
    );
    owner.call("POST", base + "/leave", Map.of("version", 4));
    var after = next.call("GET", base, null);
    assertThat(after.get("ownerId")).isEqualTo(nextId);
    assertThat(after.get("ownershipHistory")).isEqualTo(
      transferred.get("ownershipHistory")
    );
    assertThat(
      json.mapper().valueToTree(after.get("members")).size()
    ).isEqualTo(1);
    assertThat(owner.request("GET", base, null, false).statusCode()).isEqualTo(
      403
    );
  }

  @Test
  void v10AddsEmptyOwnershipHistoryWithoutChangingExistingTravelData()
    throws Exception {
    String url =
      "jdbc:h2:mem:ownership-legacy-" +
      UUID.randomUUID() +
      ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    org.flywaydb.core.Flyway.configure()
      .dataSource(url, "sa", "")
      .target("9")
      .load()
      .migrate();
    try (
      var c = java.sql.DriverManager.getConnection(url, "sa", "");
      var s = c.createStatement()
    ) {
      s.executeUpdate(
        "INSERT INTO members(id,email,password_hash,nickname) VALUES('old-owner','old@example.test','test','이전 소유자')"
      );
      s.executeUpdate(
        "INSERT INTO trips(id,owner_id,title,regions,start_date,end_date,origin,party_size,budget,transport,preferences,version) VALUES('old-trip','old-owner','기존 여행','[]',DATE '2026-11-01',DATE '2026-11-01','서울역',2,300000,'TRANSIT','[]',5)"
      );
      s.executeUpdate(
        "INSERT INTO trip_members(trip_id,member_id) VALUES('old-trip','old-owner')"
      );
    }
    org.flywaydb.core.Flyway.configure()
      .dataSource(url, "sa", "")
      .load()
      .migrate();
    try (
      var c = java.sql.DriverManager.getConnection(url, "sa", "");
      var s = c.createStatement();
      var row = s.executeQuery(
        "SELECT owner_id,title,version,(SELECT COUNT(*) FROM trip_members WHERE trip_id='old-trip') AS participants,(SELECT COUNT(*) FROM trip_ownership_history) AS changes FROM trips WHERE id='old-trip'"
      )
    ) {
      assertThat(row.next()).isTrue();
      assertThat(row.getString("owner_id")).isEqualTo("old-owner");
      assertThat(row.getString("title")).isEqualTo("기존 여행");
      assertThat(row.getInt("version")).isEqualTo(5);
      assertThat(row.getInt("participants")).isEqualTo(1);
      assertThat(row.getInt("changes")).isZero();
    }
  }

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
      return trip(conditions);
    }

    Map<String, Object> trip(Map<String, Object> conditions) throws Exception {
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
