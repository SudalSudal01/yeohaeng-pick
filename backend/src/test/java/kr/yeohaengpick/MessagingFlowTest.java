package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import kr.yeohaengpick.common.JsonCodec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** 실제 HTTP·세션·CSRF·DB를 통과시켜 회원 검색 개인정보와 메시지 권한·누락·동시 요청을 검증합니다. */
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
class MessagingFlowTest extends HttpFlowDatabase {

  @LocalServerPort
  int port;

  @Autowired
  JsonCodec json;

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  kr.yeohaengpick.auth.AuthRateLimit authenticationBudget;

  @Autowired
  kr.yeohaengpick.messaging.MessageRateLimit messageBudget;

  @BeforeEach
  void resetBudgets() {
    (
      (Map<?, ?>) ReflectionTestUtils.getField(authenticationBudget, "attempts")
    ).clear();
    (
      (Map<?, ?>) ReflectionTestUtils.getField(messageBudget, "windows")
    ).clear();
  }

  @Test
  void nicknameSearchIsAuthenticatedLiteralAndReturnsOnlyEligiblePublicIdentity()
    throws Exception {
    String prefix = "검색" + UUID.randomUUID().toString().substring(0, 6);
    var me = new Browser().signup(prefix + "본인");
    var open = new Browser().signup(prefix + "공개");
    var off = new Browser().signup(prefix + "닫음").scope("OFF");
    var onlyCompanions = new Browser()
      .signup(prefix + "동행")
      .scope("COMPANIONS");
    var blocked = new Browser().signup(prefix + "차단");
    var reverseBlocked = new Browser().signup(prefix + "상대차단");
    var departed = new Browser().signup(prefix + "탈퇴");
    me.call("PUT", "/messages/blocks/" + blocked.id, Map.of("enabled", true));
    reverseBlocked.call(
      "PUT",
      "/messages/blocks/" + me.id,
      Map.of("enabled", true)
    );
    jdbc.update(
      "UPDATE members SET deleted_at=CURRENT_TIMESTAMP WHERE id=?",
      departed.id
    );
    assertThat(
      new Browser().request("GET", searchPath(prefix), null, false).statusCode()
    ).isEqualTo(401);
    var result = me.call("GET", searchPath(prefix), null);
    var found = items(result);
    assertThat(found).hasSize(1);
    assertThat(found.getFirst())
      .containsOnlyKeys("id", "nickname")
      .containsEntry("id", open.id);
    assertThat(result).containsOnlyKeys("items", "hasMore");
    assertThat(json.write(result)).doesNotContain(
      "email",
      "password",
      "dmScope",
      "preferences",
      off.email
    );
    for (var unavailable : List.of(
      me,
      off,
      onlyCompanions,
      blocked,
      reverseBlocked,
      departed
    )) {
      assertThat(
        me
          .request("GET", "/messages/recipients/" + unavailable.id, null, false)
          .statusCode()
      ).isEqualTo(404);
    }
    assertThat(
      me.call("GET", "/messages/recipients/" + open.id, null)
    ).containsOnlyKeys("id", "nickname");
    assertThat(
      me.request("GET", searchPath("검"), null, false).statusCode()
    ).isEqualTo(400);
    assertThat(
      me.request("GET", searchPath("검".repeat(31)), null, false).statusCode()
    ).isEqualTo(400);
    var literal = new Browser().signup(prefix + "%_");
    assertThat(items(me.call("GET", searchPath(prefix + "%_"), null)))
      .extracting(row -> row.get("id"))
      .containsExactly(literal.id);
    assertThat(
      items(me.call("GET", searchPath(prefix + "!_"), null))
    ).isEmpty();
  }

  @Test
  void recipientsRequireCurrentCompanionshipAndLimitResultsWithoutTotal()
    throws Exception {
    String prefix = "동행" + UUID.randomUUID().toString().substring(0, 6);
    var owner = new Browser().signup(prefix + "요청");
    var other = new Browser().signup(prefix + "수신").scope("COMPANIONS");
    assertThat(items(owner.call("GET", searchPath(prefix), null))).isEmpty();
    String trip = owner.trip();
    var group = owner.call("POST", "/messages/trip/" + trip, Map.of());
    assertThat(group).containsEntry("tripTitle", "대화 동행 검증");
    assertThat(owner.list("/messages")).anySatisfy(room ->
      assertThat(room)
        .containsEntry("id", group.get("id"))
        .containsEntry("tripTitle", "대화 동행 검증")
    );
    owner.call(
      "POST",
      "/invitations",
      Map.of("tripId", trip, "email", other.email)
    );
    var invitation = other.list("/invitations").getFirst();
    other.call(
      "POST",
      "/invitations/" + invitation.get("id") + "/respond",
      Map.of("accept", true)
    );
    assertThat(items(owner.call("GET", searchPath(prefix), null)))
      .extracting(row -> row.get("id"))
      .contains(other.id);
    var detail = owner.call("GET", "/trips/" + trip, null);
    owner.call(
      "POST",
      "/trips/" + trip + "/members/" + other.id + "/remove",
      Map.of("version", detail.get("version"))
    );
    assertThat(items(owner.call("GET", searchPath(prefix), null))).isEmpty();
    // 정렬·최대 응답 수는 로그인 계정을 수십 개 만들지 않고 합성 DB 회원으로만 준비합니다.
    for (int i = 0; i < 23; i++) jdbc.update(
      "INSERT INTO members(id,email,password_hash,nickname,role) VALUES(?,?,?,?,?)",
      UUID.randomUUID().toString(),
      UUID.randomUUID() + "@example.test",
      "unused",
      prefix + "후보" + i,
      "USER"
    );
    var page = owner.call("GET", searchPath(prefix + "후보"), null);
    assertThat(items(page)).hasSize(20);
    assertThat(page)
      .containsEntry("hasMore", true)
      .doesNotContainKeys("total", "totalPages");
  }

  @Test
  void emailRequestCannotRevealAccountAndSendingRequiresAcceptAndCurrentBlockState()
    throws Exception {
    var a = new Browser().signup("대화 요청자");
    var b = new Browser().signup("대화 수신자");
    var outsider = new Browser().signup("다른 회원");
    for (String email : List.of(
      b.email,
      "missing-" + UUID.randomUUID() + "@example.test"
    )) {
      assertThat(
        a
          .request(
            "POST",
            "/messages",
            Map.of("email", email, "body", "이메일 요청"),
            true
          )
          .statusCode()
      ).isEqualTo(400);
    }
    assertThat(
      a
        .request(
          "POST",
          "/messages",
          Map.of("email", b.email, "recipientId", b.id, "body", "혼합 요청"),
          true
        )
        .statusCode()
    ).isEqualTo(400);
    assertThat(
      a
        .request(
          "POST",
          "/messages",
          Map.of("recipientId", a.id, "body", "본인"),
          true
        )
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      a
        .request(
          "POST",
          "/messages",
          Map.of("recipientId", b.id, "body", " "),
          true
        )
        .statusCode()
    ).isEqualTo(400);
    assertThat(
      a
        .request(
          "POST",
          "/messages",
          Map.of("recipientId", b.id, "body", "가".repeat(2001)),
          true
        )
        .statusCode()
    ).isEqualTo(400);
    var room = a.call(
      "POST",
      "/messages",
      Map.of("recipientId", b.id, "body", "첫 인사")
    );
    String id = room.get("id").toString();
    assertThat(room)
      .containsEntry("canSend", false)
      .containsEntry("blocked", false)
      .containsEntry("requesterName", a.nickname)
      .containsEntry("recipientName", b.nickname);
    assertThat(
      outsider.request("GET", "/messages/" + id, null, false).statusCode()
    ).isEqualTo(403);
    assertThat(
      outsider
        .request("POST", "/messages/" + id, Map.of("body", "침입"), true)
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      a
        .request("POST", "/messages/" + id, Map.of("body", "수락 전"), true)
        .statusCode()
    ).isEqualTo(409);
    b.call("PUT", "/messages/blocks/" + a.id, Map.of("enabled", true));
    assertThat(
      b
        .request(
          "POST",
          "/messages/" + id + "/respond",
          Map.of("accept", true),
          true
        )
        .statusCode()
    ).isEqualTo(403);
    assertThat(a.call("GET", "/messages/" + id, null))
      .containsEntry("status", "PENDING")
      .containsEntry("blocked", true)
      .containsEntry("canSend", false);
    b.call("PUT", "/messages/blocks/" + a.id, Map.of("enabled", false));
    b.call("POST", "/messages/" + id + "/respond", Map.of("accept", true));
    assertThat(
      a.call("POST", "/messages/" + id, Map.of("body", "수락 후"))
    ).containsEntry("canSend", true);
    b.scope("OFF"); // 수신 설정 변경은 이미 수락한 대화까지 종료하지 않습니다.
    assertThat(
      a.call("POST", "/messages/" + id, Map.of("body", "기존 대화 유지"))
    ).containsEntry("canSend", true);
    b.call("PUT", "/messages/blocks/" + a.id, Map.of("enabled", true));
    assertThat(a.call("GET", "/messages/" + id, null))
      .containsEntry("canSend", false)
      .containsEntry("blocked", true);
    assertThat(
      a
        .request("POST", "/messages/" + id, Map.of("body", "차단 후"), true)
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      a
        .request("POST", "/messages/" + id, Map.of("body", "CSRF 없음"), false)
        .statusCode()
    ).isEqualTo(403);
  }

  @Test
  void latestAndCursorPagesPreserveAllMessagesBeyond300IncludingTimestampTies()
    throws Exception {
    var a = new Browser().signup("긴 대화 요청자");
    var b = new Browser().signup("긴 대화 수신자");
    var stranger = new Browser().signup("다른 대화 회원");
    var room = a.call(
      "POST",
      "/messages",
      Map.of("recipientId", b.id, "body", "최초 인사")
    );
    String id = room.get("id").toString();
    b.call("POST", "/messages/" + id + "/respond", Map.of("accept", true));
    jdbc.update(
      "UPDATE messages SET created_at=? WHERE conversation_id=?",
      Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 0, 0)),
      id
    );
    Timestamp tie = Timestamp.valueOf(LocalDateTime.of(2026, 2, 1, 12, 0));
    String messagePrefix = UUID.randomUUID().toString().substring(0, 24);
    for (int i = 0; i < 350; i++) jdbc.update(
      "INSERT INTO messages(id,conversation_id,sender_id,body,created_at) VALUES(?,?,?,?,?)",
      String.format(messagePrefix + "%012d", i),
      id,
      a.id,
      "기록 " + i,
      tie
    );
    var page = a.call("GET", "/messages/" + id, null);
    assertThat(itemsNamed(page, "messages")).hasSize(50);
    assertThat(itemsNamed(page, "messages").getLast()).containsEntry(
      "body",
      "기록 349"
    );
    assertThat(page).containsEntry("hasOlder", true);
    var all = new ArrayList<Map<String, Object>>();
    while (true) {
      all.addAll(itemsNamed(page, "messages"));
      if (!Boolean.TRUE.equals(page.get("hasOlder"))) break;
      String before = page.get("olderCursor").toString();
      page = a.call("GET", "/messages/" + id + "?before=" + before, null);
    }
    assertThat(all).hasSize(351);
    assertThat(all)
      .extracting(row -> row.get("id"))
      .doesNotHaveDuplicates();
    assertThat(all)
      .extracting(row -> row.get("body"))
      .contains("최초 인사", "기록 0", "기록 349");
    var other = a.call(
      "POST",
      "/messages",
      Map.of("recipientId", stranger.id, "body", "다른 방")
    );
    String otherCursor = itemsNamed(other, "messages")
      .getFirst()
      .get("id")
      .toString();
    assertThat(
      a
        .request(
          "GET",
          "/messages/" + id + "?before=" + otherCursor,
          null,
          false
        )
        .statusCode()
    ).isEqualTo(400);
    assertThat(
      stranger
        .request(
          "GET",
          "/messages/" + id + "?before=" + otherCursor,
          null,
          false
        )
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      a
        .request("GET", "/messages/" + id + "?before=missing", null, false)
        .statusCode()
    ).isEqualTo(400);
  }

  @Test
  void simultaneousOppositeRequestsShareOnePendingRoomAndOneFirstMessage()
    throws Exception {
    var a = new Browser().signup("동시 요청 A");
    var b = new Browser().signup("동시 요청 B");
    var barrier = new CyclicBarrier(2);
    CompletableFuture<Map<String, Object>> first =
      CompletableFuture.supplyAsync(() -> {
        try {
          barrier.await();
          return a.call(
            "POST",
            "/messages",
            Map.of("recipientId", b.id, "body", "A 첫 인사")
          );
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
    CompletableFuture<Map<String, Object>> second =
      CompletableFuture.supplyAsync(() -> {
        try {
          barrier.await();
          return b.call(
            "POST",
            "/messages",
            Map.of("recipientId", a.id, "body", "B 첫 인사")
          );
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
    var left = first.get(15, TimeUnit.SECONDS);
    var right = second.get(15, TimeUnit.SECONDS);
    assertThat(left.get("id")).isEqualTo(right.get("id"));
    assertThat(
      List.of(left.get("requestCreated"), right.get("requestCreated"))
    ).containsExactlyInAnyOrder(true, false);
    assertThat(
      jdbc.queryForObject(
        "SELECT COUNT(*) FROM conversations WHERE trip_id IS NULL AND ((requester_id=? AND recipient_id=?) OR (requester_id=? AND recipient_id=?))",
        Integer.class,
        a.id,
        b.id,
        b.id,
        a.id
      )
    ).isEqualTo(1);
    assertThat(
      itemsNamed(a.call("GET", "/messages/" + left.get("id"), null), "messages")
    ).hasSize(1);
  }

  @Test
  void blockingAndSendingSerializeAndNoMessageCanCommitAfterCompletedBlock()
    throws Exception {
    var a = new Browser().signup("동시 발송");
    var b = new Browser().signup("동시 차단");
    var room = a.call(
      "POST",
      "/messages",
      Map.of("recipientId", b.id, "body", "인사")
    );
    String id = room.get("id").toString();
    b.call("POST", "/messages/" + id + "/respond", Map.of("accept", true));
    var barrier = new CyclicBarrier(2);
    var send = CompletableFuture.supplyAsync(() -> {
      try {
        barrier.await();
        return a.request(
          "POST",
          "/messages/" + id,
          Map.of("body", "경합 메시지"),
          true
        );
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    var block = CompletableFuture.supplyAsync(() -> {
      try {
        barrier.await();
        return b.request(
          "PUT",
          "/messages/blocks/" + a.id,
          Map.of("enabled", true),
          true
        );
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    assertThat(block.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    assertThat(send.get(15, TimeUnit.SECONDS).statusCode()).isIn(200, 403);
    int count = jdbc.queryForObject(
      "SELECT COUNT(*) FROM messages WHERE conversation_id=?",
      Integer.class,
      id
    );
    assertThat(
      a
        .request(
          "POST",
          "/messages/" + id,
          Map.of("body", "차단 완료 후"),
          true
        )
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      jdbc.queryForObject(
        "SELECT COUNT(*) FROM messages WHERE conversation_id=?",
        Integer.class,
        id
      )
    ).isEqualTo(count);
  }

  @Test
  void retryingSameMessageKeyIsIdempotentAndCannotBeMovedToAnotherBodySenderOrRoom()
    throws Exception {
    var a = new Browser().signup("재시도 작성자");
    var b = new Browser().signup("재시도 수신자");
    var c = new Browser().signup("다른 방 수신자");
    String firstRoom = a
      .call("POST", "/messages", Map.of("recipientId", b.id, "body", "첫 인사"))
      .get("id")
      .toString();
    b.call(
      "POST",
      "/messages/" + firstRoom + "/respond",
      Map.of("accept", true)
    );
    String key = UUID.randomUUID().toString();
    var payload = Map.of("body", "한 번만 저장할 내용", "requestId", key);
    a.call("POST", "/messages/" + firstRoom, payload);
    a.call("POST", "/messages/" + firstRoom, payload);
    assertThat(
      jdbc.queryForObject(
        "SELECT COUNT(*) FROM messages WHERE id=?",
        Integer.class,
        key
      )
    ).isEqualTo(1);
    assertThat(
      a
        .request(
          "POST",
          "/messages/" + firstRoom,
          Map.of("body", "바꾼 내용", "requestId", key),
          true
        )
        .statusCode()
    ).isEqualTo(409);
    assertThat(
      b.request("POST", "/messages/" + firstRoom, payload, true).statusCode()
    ).isEqualTo(409);
    String otherRoom = a
      .call(
        "POST",
        "/messages",
        Map.of("recipientId", c.id, "body", "다른 방 인사")
      )
      .get("id")
      .toString();
    c.call(
      "POST",
      "/messages/" + otherRoom + "/respond",
      Map.of("accept", true)
    );
    assertThat(
      a.request("POST", "/messages/" + otherRoom, payload, true).statusCode()
    ).isEqualTo(409);
    assertThat(
      a
        .request(
          "POST",
          "/messages/" + otherRoom,
          Map.of("body", "잘못된 키", "requestId", "not-a-uuid"),
          true
        )
        .statusCode()
    ).isEqualTo(400);
    assertThat(
      jdbc.queryForObject(
        "SELECT COUNT(*) FROM messages WHERE id=?",
        Integer.class,
        key
      )
    ).isEqualTo(1);
  }

  @Test
  void authenticatedSearchAndNewRequestBudgetsAreBounded() throws Exception {
    var a = new Browser().signup("횟수 요청자");
    var b = new Browser().signup("횟수 수신자");
    for (int i = 0; i < 60; i++) assertThat(
      a.request("GET", searchPath("없는 닉네임"), null, false).statusCode()
    ).isEqualTo(200);
    assertThat(
      a.request("GET", searchPath("없는 닉네임"), null, false).statusCode()
    ).isEqualTo(429);
    for (int i = 0; i < 12; i++) assertThat(
      a
        .request(
          "POST",
          "/messages",
          Map.of("recipientId", b.id, "body", "반복 요청"),
          true
        )
        .statusCode()
    ).isEqualTo(200);
    assertThat(
      a
        .request(
          "POST",
          "/messages",
          Map.of("recipientId", b.id, "body", "과다 요청"),
          true
        )
        .statusCode()
    ).isEqualTo(429);
  }

  String searchPath(String value) {
    return (
      "/messages/recipients?query=" +
      URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
    );
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> items(Map<String, Object> result) {
    return (List<Map<String, Object>>) result.get("items");
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> itemsNamed(Map<String, Object> result, String key) {
    return (List<Map<String, Object>>) result.get(key);
  }

  class Browser {

    final String email = UUID.randomUUID() + "@example.test";
    String id, nickname;
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
        json
          .read(request("GET", "/auth/csrf", null, false).body(), Map.class)
          .get("token")
          .toString()
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
      var r = request(method, path, body, !method.equals("GET"));
      assertThat(r.statusCode()).as(r.body()).isBetween(200, 299);
      return r.body().startsWith("{")
        ? json.read(r.body(), Map.class)
        : Map.of();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list(String path) throws Exception {
      var r = request("GET", path, null, false);
      assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
      return json.read(r.body(), List.class);
    }

    Browser signup(String name) throws Exception {
      nickname = name;
      var result = call(
        "POST",
        "/auth/register",
        Map.of(
          "email",
          email,
          "password",
          "Messaging-Test-Only12!",
          "nickname",
          name
        )
      );
      id = result.get("id").toString();
      call(
        "POST",
        "/auth/login",
        Map.of("email", email, "password", "Messaging-Test-Only12!")
      );
      return this;
    }

    Browser scope(String scope) throws Exception {
      call(
        "PUT",
        "/auth/profile",
        Map.of("nickname", nickname, "preferences", List.of(), "dmScope", scope)
      );
      return this;
    }

    String trip() throws Exception {
      var conditions = Map.of(
        "title",
        "대화 동행 검증",
        "regions",
        List.of("서울"),
        "startDate",
        "2026-12-01",
        "endDate",
        "2026-12-01",
        "origin",
        "서울역",
        "partySize",
        2,
        "budget",
        300000,
        "transport",
        "TRANSIT",
        "preferences",
        List.of("관광"),
        "lodging",
        ""
      );
      var r = request("POST", "/recommendations", conditions, true);
      assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
      var courses = json.read(r.body(), List.class);
      var course = (Map<?, ?>) courses.getFirst();
      return call(
        "POST",
        "/trips",
        Map.of("conditions", conditions, "items", course.get("items"))
      )
        .get("id")
        .toString();
    }
  }
}
