package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import kr.yeohaengpick.auth.AuthRateLimit;
import kr.yeohaengpick.common.JsonCodec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 알림 센터가 실제 HTTP 세션·CSRF·여행 참여 권한·DB 정리 경계를 함께 지키는지 검증합니다.
 * 1차 알림 범위는 동행 여행의 투표 생성과 수동 마감이며, 알림 응답에는 화면에 필요한 공개 문맥만 포함해야 합니다.
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
class NotificationFlowTest extends HttpFlowDatabase {

  private static final String PASSWORD = "Notification-Test-Only12!";

  @LocalServerPort
  int port;

  @Autowired
  JsonCodec json;

  @Autowired
  JdbcTemplate db;

  @Autowired
  AuthRateLimit authRateLimit;

  @BeforeEach
  void isolateAuthenticationBudget() {
    // 모든 가상 브라우저가 같은 루프백 IP를 사용하므로 시나리오 사이의 가입 횟수 제한만 초기화합니다.
    (
      (Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(
        authRateLimit,
        "attempts"
      )
    ).clear();
  }

  @Test
  void sessionCsrfAndNotificationOwnershipProtectEveryRoute() throws Exception {
    var visitor = new Browser("방문자");
    var owner = new Browser("여행장").signup();
    var recipient = new Browser("동행자").signup();
    var outsider = new Browser("외부인").signup();
    String trip = owner.trip().get("id").toString();
    join(owner, recipient, trip);
    createPoll(owner, trip, "접근 권한 확인");
    String notification = firstNotification(recipient).get("id").toString();

    assertStatus(visitor, "GET", "/notifications", null, false, 401);
    assertStatus(
      visitor,
      "GET",
      "/notifications/unread-count",
      null,
      false,
      401
    );
    assertStatus(owner, "PUT", "/notifications/read-all", null, false, 403);
    assertStatus(
      recipient,
      "PUT",
      "/notifications/" + notification + "/read",
      null,
      false,
      403
    );

    // 알림 UUID를 우연히 알더라도 수신자가 아니면 존재 여부까지 감춘 404로 응답해야 합니다.
    assertStatus(
      outsider,
      "PUT",
      "/notifications/" + notification + "/read",
      null,
      true,
      404
    );
    assertThat(firstNotification(recipient).get("readAt")).isNull();
  }

  @Test
  void pollCreationFansOutOnlyToCurrentCompanionsAndExposesNoPrivateIdentifiers()
    throws Exception {
    var owner = new Browser("여행장").signup();
    var creator = new Browser("투표 만든 동행").signup();
    var companion = new Browser("알림 받을 동행").signup();
    String trip = owner.trip().get("id").toString();
    join(owner, creator, trip);
    join(owner, companion, trip);

    var poll = createPoll(creator, trip, "우리만 아는 투표 제목");
    String pollId = poll.get("id").toString();
    companion.call(
      "PUT",
      "/trips/" + trip + "/polls/" + pollId + "/ballot",
      Map.of("optionIds", List.of(firstOptionId(poll)))
    );
    var ownerPage = notifications(owner, 1, 20);
    var companionPage = notifications(companion, 1, 20);

    assertThat(ownerPage)
      .containsEntry("total", 1)
      .containsEntry("unreadCount", 1);
    assertThat(companionPage)
      .containsEntry("total", 1)
      .containsEntry("unreadCount", 1);
    assertThat(notifications(creator, 1, 20))
      .containsEntry("total", 0)
      .containsEntry("unreadCount", 0);

    var item = items(ownerPage).getFirst();
    assertThat(item)
      .containsEntry("type", "TRIP_POLL_CREATED")
      .containsEntry("actorName", creator.nickname)
      .containsEntry("tripId", trip)
      .containsEntry("pollId", pollId)
      .containsEntry("pollTitle", "우리만 아는 투표 제목")
      .containsKeys("id", "tripTitle", "createdAt")
      .containsEntry("readAt", null)
      .doesNotContainKeys("recipientId", "actorId", "email", "optionIds");

    String response = json.write(ownerPage);
    assertThat(response)
      .doesNotContain(owner.id, creator.id, companion.id)
      .doesNotContain(owner.email, creator.email, companion.email)
      .doesNotContain("비공개 후보 A", "비공개 후보 B")
      .doesNotContain("selectedByMe", "myOptionIds");

    assertThat(
      db.queryForList(
        "SELECT recipient_id FROM member_notifications WHERE poll_id=? ORDER BY recipient_id",
        String.class,
        pollId
      )
    ).containsExactlyInAnyOrder(owner.id, companion.id);
  }

  @Test
  void readingOneIsOwnedAndIdempotentAndReadingAllClearsTheUnreadCount()
    throws Exception {
    var owner = new Browser("여행장").signup();
    var recipient = new Browser("동행자").signup();
    String trip = owner.trip().get("id").toString();
    join(owner, recipient, trip);
    var poll = createPoll(owner, trip, "읽음 처리 투표");
    closePoll(owner, trip, poll.get("id").toString(), 0);

    var before = notifications(recipient, 1, 20);
    assertThat(before)
      .containsEntry("total", 2)
      .containsEntry("unreadCount", 2);
    String first = items(before).getFirst().get("id").toString();

    assertStatus(
      recipient,
      "PUT",
      "/notifications/" + first + "/read",
      null,
      true,
      204
    );
    assertThat(unreadCount(recipient)).isEqualTo(1);

    // 같은 알림을 다시 읽어도 오류나 중복 변경 없이 이미 읽은 상태를 유지합니다.
    assertStatus(
      recipient,
      "PUT",
      "/notifications/" + first + "/read",
      null,
      true,
      204
    );
    assertThat(unreadCount(recipient)).isEqualTo(1);
    assertThat(
      items(notifications(recipient, 1, 20))
        .stream()
        .filter(item -> first.equals(item.get("id")))
        .findFirst()
        .orElseThrow()
        .get("readAt")
    ).isNotNull();

    assertStatus(recipient, "PUT", "/notifications/read-all", null, true, 204);
    assertThat(unreadCount(recipient)).isZero();
    assertThat(items(notifications(recipient, 1, 20))).allSatisfy(item ->
      assertThat(item.get("readAt")).isNotNull()
    );
  }

  @Test
  void unreadFilterAppliesBeforePaginationAndPreservesRecipientAccess()
    throws Exception {
    var owner = new Browser("여행장").signup();
    var recipient = new Browser("미확인 동행").signup();
    var outsider = new Browser("외부 회원").signup();
    String trip = owner.trip().get("id").toString();
    join(owner, recipient, trip);
    createPoll(owner, trip, "첫 알림");
    createPoll(owner, trip, "둘째 알림");
    createPoll(owner, trip, "셋째 알림");
    String readId = firstNotification(recipient).get("id").toString();
    assertStatus(
      recipient,
      "PUT",
      "/notifications/" + readId + "/read",
      null,
      true,
      204
    );

    // 미확인 조건을 페이지 제한 전에 적용해야 읽은 항목 때문에 페이지가 비거나 건수가 틀리지 않습니다.
    var filtered = recipient.call(
      "GET",
      "/notifications?unreadOnly=true&page=99&pageSize=1",
      null
    );
    assertThat(filtered)
      .containsEntry("total", 2)
      .containsEntry("page", 2)
      .containsEntry("totalPages", 2)
      .containsEntry("unreadCount", 2);
    assertThat(items(filtered))
      .hasSize(1)
      .allSatisfy(item -> {
        assertThat(item.get("readAt")).isNull();
        assertThat(item.get("id")).isNotEqualTo(readId);
      });
    assertThat(notifications(recipient, 1, 20)).containsEntry("total", 3);
    assertThat(
      recipient.call("GET", "/notifications?unreadOnly=false", null)
    ).containsEntry("total", 3);
    assertThat(
      outsider.call("GET", "/notifications?unreadOnly=true", null)
    ).containsEntry("total", 0);
    assertStatus(
      recipient,
      "GET",
      "/notifications?unreadOnly=invalid",
      null,
      false,
      400
    );

    assertStatus(recipient, "PUT", "/notifications/read-all", null, true, 204);
    var empty = recipient.call(
      "GET",
      "/notifications?unreadOnly=true&page=99",
      null
    );
    assertThat(empty)
      .containsEntry("total", 0)
      .containsEntry("page", 1)
      .containsEntry("totalPages", 0);
    assertThat(items(empty)).isEmpty();
    assertThat(notifications(recipient, 1, 20)).containsEntry("total", 3);
  }

  @Test
  void listingValidatesBoundsAndCorrectsAnEmptyRequestedPage()
    throws Exception {
    var owner = new Browser("여행장").signup();
    var recipient = new Browser("동행자").signup();
    String trip = owner.trip().get("id").toString();
    join(owner, recipient, trip);
    createPoll(owner, trip, "첫 번째 알림");
    createPoll(owner, trip, "두 번째 알림");
    createPoll(owner, trip, "세 번째 알림");

    var lastPage = notifications(recipient, 99, 2);
    assertThat(lastPage)
      .containsEntry("total", 3)
      .containsEntry("page", 2)
      .containsEntry("pageSize", 2)
      .containsEntry("totalPages", 2)
      .containsEntry("unreadCount", 3);
    assertThat(items(lastPage)).hasSize(1);
    assertStatus(
      recipient,
      "GET",
      "/notifications?page=0&pageSize=20",
      null,
      false,
      400
    );
    assertStatus(
      recipient,
      "GET",
      "/notifications?page=1&pageSize=31",
      null,
      false,
      400
    );
  }

  @Test
  void leavingDeletesReceivedNotificationsAndRejoiningDoesNotRestoreHistory()
    throws Exception {
    var owner = new Browser("여행장").signup();
    var companion = new Browser("떠날 동행").signup();
    String trip = owner.trip().get("id").toString();
    join(owner, companion, trip);
    var poll = createPoll(owner, trip, "나가기 전 알림");
    assertThat(unreadCount(companion)).isEqualTo(1);

    int version = (
      (Number) owner.call("GET", "/trips/" + trip, null).get("version")
    ).intValue();
    companion.call(
      "POST",
      "/trips/" + trip + "/leave",
      Map.of("version", version)
    );
    assertThat(notificationRows(companion.id, trip)).isZero();
    assertThat(notifications(companion, 1, 20)).containsEntry("total", 0);

    join(owner, companion, trip);
    assertThat(notifications(companion, 1, 20)).containsEntry("total", 0);
    closePoll(owner, trip, poll.get("id").toString(), 0);
    assertThat(firstNotification(companion))
      .containsEntry("type", "TRIP_POLL_CLOSED")
      .containsEntry("pollId", poll.get("id"));
  }

  @Test
  void ownerRemovalAndWithdrawalDeleteOnlyTheRecipientsStoredNotifications()
    throws Exception {
    var owner = new Browser("여행장").signup();
    var removed = new Browser("내보낼 동행").signup();
    var remaining = new Browser("남은 동행").signup();
    String trip = owner.trip().get("id").toString();
    join(owner, removed, trip);
    join(owner, remaining, trip);
    createPoll(owner, trip, "동행 정리 알림");
    assertThat(notificationRows(removed.id, trip)).isEqualTo(1);
    assertThat(notificationRows(remaining.id, trip)).isEqualTo(1);

    int version = (
      (Number) owner.call("GET", "/trips/" + trip, null).get("version")
    ).intValue();
    owner.call(
      "POST",
      "/trips/" + trip + "/members/" + removed.id + "/remove",
      Map.of("version", version)
    );
    assertThat(notificationRows(removed.id, trip)).isZero();
    assertThat(notificationRows(remaining.id, trip)).isEqualTo(1);

    remaining.call(
      "DELETE",
      "/auth/account",
      Map.of("password", PASSWORD, "confirmed", true)
    );
    assertThat(notificationRows(remaining.id, trip)).isZero();
    assertStatus(remaining, "GET", "/notifications", null, false, 401);
  }

  @Test
  void pollCreationAndCompanionRemovalCannotLeaveAFormerMembersNotification()
    throws Exception {
    var owner = new Browser("여행장").signup();
    var departing = new Browser("동시에 내보낼 동행").signup();
    String trip = owner.trip().get("id").toString();
    join(owner, departing, trip);
    int version = (
      (Number) owner.call("GET", "/trips/" + trip, null).get("version")
    ).intValue();
    String csrf = owner.csrf();

    // 두 요청이 같은 여행 행 잠금을 기준으로 직렬화됩니다. 생성이 먼저면 제거가 알림을 지우고,
    // 제거가 먼저면 생성 시점의 현재 동행 목록에서 제외되므로 어느 순서에도 잔여 알림이 없어야 합니다.
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var gate = new CyclicBarrier(2);
      var creation = pool.submit(() -> {
        gate.await();
        return owner
          .requestWithToken(
            "POST",
            "/trips/" + trip + "/polls",
            pollRequest("동시 생성 투표"),
            csrf
          )
          .statusCode();
      });
      var removal = pool.submit(() -> {
        gate.await();
        return owner
          .requestWithToken(
            "POST",
            "/trips/" + trip + "/members/" + departing.id + "/remove",
            Map.of("version", version),
            csrf
          )
          .statusCode();
      });
      assertThat(creation.get(20, TimeUnit.SECONDS)).isBetween(200, 299);
      assertThat(removal.get(20, TimeUnit.SECONDS)).isBetween(200, 299);
    }

    assertThat(notificationRows(departing.id, trip)).isZero();
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM trip_members WHERE trip_id=? AND member_id=?",
        Integer.class,
        trip,
        departing.id
      )
    ).isZero();
    assertThat(notifications(departing, 1, 20)).containsEntry("total", 0);
  }

  @Test
  void deletingPollAndTripCascadesTheirNotifications() throws Exception {
    var owner = new Browser("여행장").signup();
    var companion = new Browser("동행자").signup();
    String trip = owner.trip().get("id").toString();
    join(owner, companion, trip);
    var first = createPoll(owner, trip, "삭제할 투표");
    String firstId = first.get("id").toString();
    closePoll(owner, trip, firstId, 0);
    assertThat(notificationRowsForPoll(firstId)).isEqualTo(2);

    owner.call(
      "DELETE",
      "/trips/" + trip + "/polls/" + firstId,
      Map.of("version", 1)
    );
    assertThat(notificationRowsForPoll(firstId)).isZero();

    var second = createPoll(owner, trip, "여행과 함께 삭제할 투표");
    String secondId = second.get("id").toString();
    assertThat(notificationRowsForPoll(secondId)).isEqualTo(1);
    owner.call("DELETE", "/trips/" + trip, null);
    assertThat(notificationRowsForPoll(secondId)).isZero();
    assertThat(notificationRows(companion.id, trip)).isZero();
  }

  @Test
  void aFailedDuplicateCloseDoesNotCreateAnotherNotification()
    throws Exception {
    var owner = new Browser("여행장").signup();
    var companion = new Browser("동행자").signup();
    String trip = owner.trip().get("id").toString();
    join(owner, companion, trip);
    var poll = createPoll(owner, trip, "한 번만 마감할 투표");
    String pollId = poll.get("id").toString();
    closePoll(owner, trip, pollId, 0);
    assertThat(notificationRowsForPoll(pollId)).isEqualTo(2);

    assertStatus(
      owner,
      "POST",
      "/trips/" + trip + "/polls/" + pollId + "/close",
      Map.of("version", 1),
      true,
      409
    );
    assertThat(notificationRowsForPoll(pollId)).isEqualTo(2);
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM member_notifications WHERE poll_id=? AND event_type='TRIP_POLL_CLOSED'",
        Integer.class,
        pollId
      )
    ).isEqualTo(1);
  }

  Map<String, Object> createPoll(Browser actor, String trip, String title)
    throws Exception {
    return actor.call("POST", "/trips/" + trip + "/polls", pollRequest(title));
  }

  Map<String, Object> pollRequest(String title) {
    return Map.of(
      "title",
      title,
      "selectionType",
      "SINGLE",
      "options",
      List.of(
        Map.of("label", "비공개 후보 A"),
        Map.of("label", "비공개 후보 B")
      )
    );
  }

  void closePoll(Browser actor, String trip, String poll, int version)
    throws Exception {
    actor.call(
      "POST",
      "/trips/" + trip + "/polls/" + poll + "/close",
      Map.of("version", version)
    );
  }

  @SuppressWarnings("unchecked")
  String firstOptionId(Map<String, Object> poll) {
    // 다른 동행이 실제로 선택한 후보가 알림 응답에 섞이지 않는지 검증할 때만 사용합니다.
    return ((List<Map<String, Object>>) poll.get("options"))
      .getFirst()
      .get("id")
      .toString();
  }

  void join(Browser owner, Browser companion, String trip) throws Exception {
    owner.call(
      "POST",
      "/invitations",
      Map.of("tripId", trip, "email", companion.email)
    );
    String invitation = companion
      .list("/invitations")
      .getFirst()
      .get("id")
      .toString();
    companion.call(
      "POST",
      "/invitations/" + invitation + "/respond",
      Map.of("accept", true)
    );
  }

  Map<String, Object> notifications(Browser browser, int page, int pageSize)
    throws Exception {
    return browser.call(
      "GET",
      "/notifications?page=" + page + "&pageSize=" + pageSize,
      null
    );
  }

  Map<String, Object> firstNotification(Browser browser) throws Exception {
    return items(notifications(browser, 1, 20)).getFirst();
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> items(Map<String, Object> page) {
    return (List<Map<String, Object>>) page.get("items");
  }

  int unreadCount(Browser browser) throws Exception {
    return (
      (Number) browser
        .call("GET", "/notifications/unread-count", null)
        .get("unreadCount")
    ).intValue();
  }

  int notificationRows(String recipient, String trip) {
    return db.queryForObject(
      "SELECT COUNT(*) FROM member_notifications WHERE recipient_id=? AND trip_id=?",
      Integer.class,
      recipient,
      trip
    );
  }

  int notificationRowsForPoll(String poll) {
    return db.queryForObject(
      "SELECT COUNT(*) FROM member_notifications WHERE poll_id=?",
      Integer.class,
      poll
    );
  }

  void assertStatus(
    Browser browser,
    String method,
    String path,
    Object body,
    boolean csrf,
    int expected
  ) throws Exception {
    var response = browser.request(method, path, body, csrf);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
  }

  class Browser {

    final String email = UUID.randomUUID() + "@example.test";
    final String nickname;
    String id;
    final HttpClient client = HttpClient.newBuilder()
      .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
      .build();

    Browser(String nickname) {
      this.nickname = nickname;
    }

    HttpResponse<String> request(
      String method,
      String path,
      Object body,
      boolean csrf
    ) throws Exception {
      return requestWithToken(method, path, body, csrf ? csrf() : null);
    }

    String csrf() throws Exception {
      return map(requestWithToken("GET", "/auth/csrf", null, null))
        .get("token")
        .toString();
    }

    /** 미리 받은 CSRF 토큰으로 동시 요청의 시작 시점을 맞춥니다. */
    HttpResponse<String> requestWithToken(
      String method,
      String path,
      Object body,
      String csrf
    ) throws Exception {
      var builder = HttpRequest.newBuilder(
        URI.create("http://127.0.0.1:" + port + "/api" + path)
      ).header("Content-Type", "application/json");
      if (csrf != null) builder.header("X-XSRF-TOKEN", csrf);
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

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list(String path) throws Exception {
      var response = request("GET", path, null, false);
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      return json.read(response.body(), List.class);
    }

    Browser signup() throws Exception {
      call(
        "POST",
        "/auth/register",
        Map.of("email", email, "password", PASSWORD, "nickname", nickname)
      );
      id = call(
        "POST",
        "/auth/login",
        Map.of("email", email, "password", PASSWORD)
      )
        .get("id")
        .toString();
      return this;
    }

    Map<String, Object> trip() throws Exception {
      var conditions = Map.ofEntries(
        Map.entry("title", "알림 센터 검증 여행"),
        Map.entry("regions", List.of("서울")),
        Map.entry("startDate", "2026-11-01"),
        Map.entry("endDate", "2026-11-01"),
        Map.entry("origin", "서울역"),
        Map.entry("partySize", 3),
        Map.entry("budget", 300000),
        Map.entry("transport", "TRANSIT"),
        Map.entry("preferences", List.of("관광", "전시")),
        Map.entry("lodging", "")
      );
      var response = request("POST", "/recommendations", conditions, true);
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      var courses = json.mapper().readTree(response.body());
      Object selectedItems = json.read(
        courses.get(1).path("items").toString(),
        List.class
      );
      return call(
        "POST",
        "/trips",
        Map.of("conditions", conditions, "items", selectedItems)
      );
    }
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> map(HttpResponse<String> response) {
    return json.read(response.body(), Map.class);
  }
}
