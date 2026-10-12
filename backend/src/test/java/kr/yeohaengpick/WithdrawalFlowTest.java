package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import kr.yeohaengpick.auth.*;
import kr.yeohaengpick.common.JsonCodec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** 허구 계정·메모리 DB의 실제 HTTP로 탈퇴, 다른 기기 세션, 공동 기록과 경합을 확인합니다. 실사용자·메일은 건드리지 않습니다. */
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
class WithdrawalFlowTest {

  @LocalServerPort
  int port;

  @Autowired
  JsonCodec json;

  @Autowired
  MemberMapper members;

  @Autowired
  JdbcTemplate db;

  @Autowired
  AuthRateLimit rateLimit;

  final String password = "Withdrawal-Test-Only12!";

  @BeforeEach
  void isolateBudget() {
    (
      (Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(
        rateLimit,
        "attempts"
      )
    ).clear();
  }

  Map<String, Object> confirmation() {
    return Map.of("password", password, "confirmed", true);
  }

  Map<String, Object> post(String title) {
    return Map.of(
      "category",
      "후기",
      "region",
      "서울",
      "title",
      title,
      "body",
      "삭제 대상 원문"
    );
  }

  @Test
  void currentPasswordConfirmationAuthenticationAndCsrfAreRequired()
    throws Exception {
    var visitor = new Browser();
    assertThat(
      visitor.request("GET", "/auth/withdrawal", null, false).statusCode()
    ).isEqualTo(401);
    assertThat(
      visitor
        .request("DELETE", "/auth/account", confirmation(), true)
        .statusCode()
    ).isEqualTo(401);
    var owner = new Browser().signup();
    assertThat(
      owner
        .request("DELETE", "/auth/account", confirmation(), false)
        .statusCode()
    ).isEqualTo(403);
    for (var invalid : List.of(
      Map.of("password", password),
      Map.of("password", password, "confirmed", false),
      Map.of("password", "", "confirmed", true)
    )) {
      assertThat(
        owner.request("DELETE", "/auth/account", invalid, true).statusCode()
      ).isEqualTo(400);
    }
    assertThat(
      owner
        .request(
          "DELETE",
          "/auth/account",
          Map.of("password", "Wrong-Password12!", "confirmed", true),
          true
        )
        .statusCode()
    ).isEqualTo(403);
    assertThat(owner.call("GET", "/auth/me", null)).containsEntry(
      "email",
      owner.email
    );
    assertThat(members.byId(owner.id).get("deletedAt")).isNull();
    assertThat(
      owner
        .request(
          "DELETE",
          "/auth/account",
          Map.of("password", "가".repeat(30), "confirmed", true),
          true
        )
        .statusCode()
    ).isEqualTo(400);
  }

  @Test
  void ownedTripBlocksWithdrawalWithoutChangingAnyRecords() throws Exception {
    var owner = new Browser().signup();
    var trip = owner.trip();
    var p = owner.call("POST", "/community/posts", post("남아야 할 글"));
    var before = new HashMap<>(members.byId(owner.id));
    var preview = owner.call("GET", "/auth/withdrawal", null);
    assertThat(json.write(preview))
      .contains(trip.get("id").toString(), "소유한 여행 검증")
      .doesNotContain(owner.email, password);
    assertThat(
      owner
        .request("DELETE", "/auth/account", confirmation(), true)
        .statusCode()
    ).isEqualTo(409);
    assertThat(members.byId(owner.id)).isEqualTo(before);
    assertThat(owner.call("GET", "/trips/" + trip.get("id"), null)).isEqualTo(
      trip
    );
    assertThat(
      owner.call("GET", "/community/posts/" + p.get("id"), null).get("body")
    ).isEqualTo("삭제 대상 원문");
    owner.call("DELETE", "/trips/" + trip.get("id"), null);
    assertThat(
      owner
        .request("DELETE", "/auth/account", confirmation(), true)
        .statusCode()
    ).isEqualTo(204);
  }

  @Test
  void withdrawalRemovesCredentialsTokensAndAllSessionsAndAllowsFreshSignup()
    throws Exception {
    var owner = new Browser().signup();
    var secondDevice = new Browser(owner.email).login();
    String verify = "a".repeat(64),
      reset = "b".repeat(64);
    members.token(
      hash(verify),
      owner.id,
      "VERIFY",
      LocalDateTime.now().plusMinutes(30)
    );
    members.token(
      hash(reset),
      owner.id,
      "RESET",
      LocalDateTime.now().plusMinutes(30)
    );
    var response = owner.request(
      "DELETE",
      "/auth/account",
      confirmation(),
      true
    );
    assertThat(response.statusCode()).isEqualTo(204);
    // 컨테이너에 따라 Max-Age=0 대신 과거 Expires로 직렬화합니다. 실제 만료 의미를 검사합니다.
    assertThat(response.headers().allValues("set-cookie")).anySatisfy(
      header -> {
        var cookie = HttpCookie.parse(header).getFirst();
        assertThat(cookie.getName()).isEqualTo("JSESSIONID");
        assertThat(cookie.getValue()).isEmpty();
        assertThat(cookie.hasExpired()).isTrue();
      }
    );
    var erased = members.byId(owner.id);
    assertThat(erased)
      .containsEntry("nickname", "탈퇴한 회원")
      .containsEntry("preferences", "[]")
      .containsEntry("dmScope", "OFF")
      .containsEntry("verified", false)
      .containsEntry("role", "USER")
      .containsEntry("credentialVersion", 1)
      .containsEntry("passwordHash", "!withdrawn");
    assertThat(erased.get("email")).isNotEqualTo(owner.email);
    assertThat(erased.get("deletedAt")).isNotNull();
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM email_tokens WHERE member_id=?",
        Integer.class,
        owner.id
      )
    ).isZero();
    assertThat(
      owner.request("GET", "/auth/me", null, false).statusCode()
    ).isEqualTo(401);
    assertThat(
      secondDevice.request("GET", "/auth/me", null, false).statusCode()
    ).isEqualTo(401);
    assertThat(
      secondDevice
        .request(
          "PUT",
          "/auth/profile",
          Map.of(
            "nickname",
            "부활 시도",
            "preferences",
            List.of(),
            "dmScope",
            "ALL"
          ),
          true
        )
        .statusCode()
    ).isEqualTo(401);
    var anonymous = new Browser(owner.email);
    assertThat(
      anonymous
        .request(
          "POST",
          "/auth/login",
          Map.of("email", owner.email, "password", password),
          true
        )
        .statusCode()
    ).isEqualTo(401);
    assertThat(
      anonymous
        .request("POST", "/auth/verify", Map.of("token", verify), true)
        .statusCode()
    ).isEqualTo(400);
    assertThat(
      anonymous
        .request(
          "POST",
          "/auth/reset",
          Map.of("token", reset, "password", password),
          true
        )
        .statusCode()
    ).isEqualTo(400);
    var replacement = anonymous.signup();
    assertThat(replacement.id).isNotEqualTo(owner.id);
    assertThat(
      json
        .mapper()
        .readTree(replacement.request("GET", "/trips", null, false).body())
        .size()
    ).isZero();
  }

  @Test
  void transferredTripAndSharedGroupAndExpensesRemainWithAnonymousAuthor()
    throws Exception {
    var oldOwner = new Browser().signup();
    var newOwner = new Browser().signup();
    var pending = new Browser().signup();
    var trip = oldOwner.trip();
    String base = "/trips/" + trip.get("id");
    join(oldOwner, newOwner, trip);
    oldOwner.call(
      "POST",
      base + "/expenses",
      Map.of("label", "함께한 점심", "amount", 20000)
    );
    oldOwner.call("POST", base + "/tasks", Map.of("content", "공동 준비물"));
    var room = oldOwner.call(
      "POST",
      "/messages/trip/" + trip.get("id"),
      Map.of()
    );
    oldOwner.call(
      "POST",
      "/messages/" + room.get("id"),
      Map.of("body", "함께 남긴 대화")
    );
    String share = oldOwner
      .call("POST", base + "/share", Map.of("enabled", true))
      .get("token")
      .toString();
    var transferred = oldOwner.call(
      "POST",
      base + "/ownership/transfer",
      Map.of(
        "version",
        oldOwner.call("GET", base, null).get("version"),
        "memberId",
        newOwner.id
      )
    );
    // 새 소유자가 발급한 미수락 초대도 탈퇴한 수신자와의 연결만 정리합니다.
    newOwner.call(
      "POST",
      "/invitations",
      Map.of("tripId", trip.get("id"), "email", pending.email)
    );
    var pendingInvite = json
      .mapper()
      .readTree(pending.request("GET", "/invitations", null, false).body())
      .get(0)
      .path("id")
      .asText();
    var outsiderTrip = pending.trip();
    pending.call(
      "POST",
      "/invitations",
      Map.of("tripId", outsiderTrip.get("id"), "email", oldOwner.email)
    );
    var preview = oldOwner.call("GET", "/auth/withdrawal", null);
    assertThat(preview.get("joinedTripCount")).isEqualTo(1);
    assertThat(
      oldOwner
        .request("DELETE", "/auth/account", confirmation(), true)
        .statusCode()
    ).isEqualTo(204);
    var saved = newOwner.call("GET", base, null);
    assertThat(saved)
      .containsEntry("ownerId", newOwner.id)
      .containsEntry(
        "version",
        ((Number) transferred.get("version")).intValue() + 1
      );
    for (String field : List.of(
      "items",
      "lodgings",
      "tasks",
      "partySize",
      "budget",
      "preferences",
      "externalBookings",
      "routeTimings"
    ))
      assertThat(saved.get(field)).as(field).isEqualTo(transferred.get(field));
    assertThat(json.write(saved.get("expenses"))).contains(
      "함께한 점심",
      "20000",
      "탈퇴한 회원"
    );
    assertThat(json.write(saved.get("ownershipHistory"))).contains(
      "탈퇴한 회원"
    );
    assertThat(
      json.mapper().valueToTree(saved).path("members").size()
    ).isEqualTo(1);
    assertThat(
      json.write(newOwner.call("GET", "/messages/" + room.get("id"), null))
    ).contains("함께 남긴 대화", "탈퇴한 회원");
    assertThat(
      new Browser().request("GET", "/share/" + share, null, false).statusCode()
    ).isEqualTo(200);
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM invitations WHERE sender_id=? OR recipient_id=?",
        Integer.class,
        oldOwner.id,
        oldOwner.id
      )
    ).isZero();
    // 다른 회원의 초대는 그대로 유지됩니다.
    pending.call(
      "POST",
      "/invitations/" + pendingInvite + "/respond",
      Map.of("accept", true)
    );
    assertThat(
      oldOwner.request("GET", base, null, false).statusCode()
    ).isEqualTo(401);
  }

  @Test
  void publicPostsAndCommentsAreErasedIncludingTrashButOthersAndReportsRemain()
    throws Exception {
    var writer = new Browser().signup();
    var other = new Browser().signup();
    var own = writer.call("POST", "/community/posts", post("공개 글 원문"));
    var trashed = writer.call(
      "POST",
      "/community/posts",
      post("휴지통 글 원문")
    );
    writer.call(
      "DELETE",
      "/community/posts/" + trashed.get("id"),
      Map.of("version", 0)
    );
    var otherPost = other.call(
      "POST",
      "/community/posts",
      post("다른 회원 글")
    );
    String base = "/community/posts/" + otherPost.get("id");
    writer.call(
      "POST",
      base + "/comments",
      Map.of("body", "삭제할 댓글 개인정보")
    );
    other.call(
      "POST",
      base + "/comments",
      Map.of("body", "남겨야 할 다른 댓글")
    );
    writer.call(
      "POST",
      "/reports",
      Map.of(
        "type",
        "POST",
        "targetId",
        otherPost.get("id"),
        "reason",
        "보존할 신고 사유"
      )
    );
    writer.call("DELETE", "/auth/account", confirmation());
    var visitor = new Browser();
    assertThat(
      visitor
        .request("GET", "/community/posts/" + own.get("id"), null, false)
        .statusCode()
    ).isEqualTo(404);
    assertThat(
      visitor
        .request("GET", "/community/posts/" + trashed.get("id"), null, false)
        .statusCode()
    ).isEqualTo(404);
    assertThat(json.write(visitor.call("GET", base, null)))
      .contains("남겨야 할 다른 댓글")
      .doesNotContain("삭제할 댓글 개인정보");
    assertThat(
      db.queryForList(
        "SELECT title,body,region,status FROM posts WHERE author_id=?",
        writer.id
      )
    ).allSatisfy(p ->
      assertThat(p)
        .containsEntry("title", "탈퇴한 회원의 게시글")
        .containsEntry("body", "")
        .containsEntry("region", "")
        .containsEntry("status", "CLOSED")
    );
    assertThat(
      db.queryForObject(
        "SELECT body FROM comments WHERE author_id=?",
        String.class,
        writer.id
      )
    ).isEmpty();
    assertThat(
      db.queryForObject(
        "SELECT reason FROM reports WHERE reporter_id=?",
        String.class,
        writer.id
      )
    ).isEqualTo("보존할 신고 사유");
  }

  @Test
  void directRoomsBecomeReadOnlyAndNoNewContactOrBlockCanTargetWithdrawnMember()
    throws Exception {
    var departed = new Browser().signup();
    var peer = new Browser().signup();
    var requester = new Browser().signup();
    var room = peer.call(
      "POST",
      "/messages",
      Map.of("recipientId", departed.id, "body", "남아야 할 첫 인사")
    );
    departed.call(
      "POST",
      "/messages/" + room.get("id") + "/respond",
      Map.of("accept", true)
    );
    departed.call(
      "POST",
      "/messages/" + room.get("id"),
      Map.of("body", "남아야 할 응답")
    );
    var pending = requester.call(
      "POST",
      "/messages",
      Map.of("recipientId", departed.id, "body", "대기 요청")
    );
    departed.call(
      "PUT",
      "/messages/blocks/" + requester.id,
      Map.of("enabled", true)
    );
    departed.call("DELETE", "/auth/account", confirmation());
    var closed = peer.call("GET", "/messages/" + room.get("id"), null);
    assertThat(closed).containsEntry("status", "CLOSED");
    assertThat(json.write(closed)).contains(
      "남아야 할 첫 인사",
      "남아야 할 응답",
      "탈퇴한 회원"
    );
    assertThat(
      requester.call("GET", "/messages/" + pending.get("id"), null)
    ).containsEntry("status", "CLOSED");
    assertThat(
      peer
        .request(
          "POST",
          "/messages/" + room.get("id"),
          Map.of("body", "종료 후 전송"),
          true
        )
        .statusCode()
    ).isEqualTo(409);
    assertThat(
      peer
        .request(
          "POST",
          "/messages/" + room.get("id") + "/respond",
          Map.of("accept", true),
          true
        )
        .statusCode()
    ).isEqualTo(409);
    for (var target : List.of(
      Map.of("recipientId", departed.id, "body", "새 요청"),
      Map.of("email", departed.email, "body", "새 요청")
    ))
      assertThat(
        peer.request("POST", "/messages", target, true).statusCode()
      ).isEqualTo(400);
    assertThat(
      peer
        .request(
          "PUT",
          "/messages/blocks/" + departed.id,
          Map.of("enabled", true),
          true
        )
        .statusCode()
    ).isEqualTo(400);
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM blocks WHERE blocker_id=? OR blocked_id=?",
        Integer.class,
        departed.id,
        departed.id
      )
    ).isZero();
  }

  @Test
  void concurrentOwnershipTransferAndWithdrawalNeverLeaveAnInactiveOwner()
    throws Exception {
    var owner = new Browser().signup();
    var target = new Browser().signup();
    var trip = owner.trip();
    join(owner, target, trip);
    var fresh = owner.call("GET", "/trips/" + trip.get("id"), null);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var barrier = new CyclicBarrier(2);
      var transfer = pool.submit(() -> {
        barrier.await();
        return owner
          .request(
            "POST",
            "/trips/" + trip.get("id") + "/ownership/transfer",
            Map.of("version", fresh.get("version"), "memberId", target.id),
            true
          )
          .statusCode();
      });
      var deletion = pool.submit(() -> {
        barrier.await();
        return target
          .request("DELETE", "/auth/account", confirmation(), true)
          .statusCode();
      });
      int t = transfer.get(20, TimeUnit.SECONDS),
        d = deletion.get(20, TimeUnit.SECONDS);
      assertThat((t == 200 && d == 409) || (t == 404 && d == 204))
        .as("transfer=%s, withdrawal=%s", t, d)
        .isTrue();
    }
    String currentOwner = db.queryForObject(
      "SELECT owner_id FROM trips WHERE id=?",
      String.class,
      trip.get("id")
    );
    assertThat(MemberMapper.active(members.byId(currentOwner))).isTrue();
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM trip_members WHERE trip_id=? AND member_id=?",
        Integer.class,
        trip.get("id"),
        currentOwner
      )
    ).isEqualTo(1);
  }

  @Test
  void concurrentPostCreationCannotResurrectContentAfterWithdrawal()
    throws Exception {
    var owner = new Browser().signup();
    var secondDevice = new Browser(owner.email).login();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var barrier = new CyclicBarrier(2);
      var writing = pool.submit(() -> {
        barrier.await();
        return secondDevice
          .request("POST", "/community/posts", post("경합 원문"), true)
          .statusCode();
      });
      var deletion = pool.submit(() -> {
        barrier.await();
        return owner
          .request("DELETE", "/auth/account", confirmation(), true)
          .statusCode();
      });
      assertThat(deletion.get(20, TimeUnit.SECONDS)).isEqualTo(204);
      assertThat(writing.get(20, TimeUnit.SECONDS)).isIn(200, 401);
    }
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM posts WHERE author_id=? AND (deleted_at IS NULL OR body <> '')",
        Integer.class,
        owner.id
      )
    ).isZero();
    assertThat(
      secondDevice.request("GET", "/auth/me", null, false).statusCode()
    ).isEqualTo(401);
  }

  @Test
  void passwordAttemptsAreRateLimited() throws Exception {
    var owner = new Browser().signup();
    for (int i = 0; i < 10; i++) assertThat(
      owner
        .request(
          "DELETE",
          "/auth/account",
          Map.of("password", "Wrong-Password12!", "confirmed", true),
          true
        )
        .statusCode()
    ).isEqualTo(403);
    var response = owner.request(
      "DELETE",
      "/auth/account",
      confirmation(),
      true
    );
    assertThat(response.statusCode()).isEqualTo(429);
    assertThat(response.headers().firstValue("Retry-After")).contains("60");
    assertThat(MemberMapper.active(members.byId(owner.id))).isTrue();
  }

  @Test
  void concurrentInvitationAcceptanceCannotRestoreWithdrawnMembership()
    throws Exception {
    var owner = new Browser().signup();
    var target = new Browser().signup();
    var otherDevice = new Browser(target.email).login();
    var trip = owner.trip();
    owner.call(
      "POST",
      "/invitations",
      Map.of("tripId", trip.get("id"), "email", target.email)
    );
    String invitation = json
      .mapper()
      .readTree(target.request("GET", "/invitations", null, false).body())
      .get(0)
      .path("id")
      .asText();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var barrier = new CyclicBarrier(2);
      var accepting = pool.submit(() -> {
        barrier.await();
        return otherDevice
          .request(
            "POST",
            "/invitations/" + invitation + "/respond",
            Map.of("accept", true),
            true
          )
          .statusCode();
      });
      var deleting = pool.submit(() -> {
        barrier.await();
        return target
          .request("DELETE", "/auth/account", confirmation(), true)
          .statusCode();
      });
      assertThat(deleting.get(20, TimeUnit.SECONDS)).isEqualTo(204);
      assertThat(accepting.get(20, TimeUnit.SECONDS)).isIn(200, 401);
    }
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM trip_members WHERE member_id=?",
        Integer.class,
        target.id
      )
    ).isZero();
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM invitations WHERE recipient_id=?",
        Integer.class,
        target.id
      )
    ).isZero();
    assertThat(
      owner
        .request(
          "POST",
          "/invitations",
          Map.of("tripId", trip.get("id"), "email", target.email),
          true
        )
        .statusCode()
    ).isEqualTo(400);
  }

  @Test
  void migrationPreservesExistingMembersAndTrips() throws Exception {
    String url =
      "jdbc:h2:mem:withdraw-migrate-" +
      UUID.randomUUID() +
      ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    org.flywaydb.core.Flyway.configure()
      .dataSource(url, "sa", "")
      .target("10")
      .load()
      .migrate();
    try (
      var connection = java.sql.DriverManager.getConnection(url, "sa", "");
      var s = connection.createStatement()
    ) {
      s.executeUpdate(
        "INSERT INTO members(id,email,password_hash,nickname,credential_version) VALUES('legacy','legacy@example.test','legacy-hash','기존 회원',4)"
      );
      s.executeUpdate(
        "INSERT INTO trips(id,owner_id,title,regions,start_date,end_date,origin,party_size,budget,transport,preferences) VALUES('legacy-trip','legacy','기존 여행','[\"서울\"]',DATE '2026-11-01',DATE '2026-11-01','서울역',2,100000,'TRANSIT','[]')"
      );
    }
    org.flywaydb.core.Flyway.configure()
      .dataSource(url, "sa", "")
      .load()
      .migrate();
    try (
      var connection = java.sql.DriverManager.getConnection(url, "sa", "");
      var s = connection.createStatement();
      var row = s.executeQuery(
        "SELECT m.*,t.title FROM members m JOIN trips t ON t.owner_id=m.id"
      )
    ) {
      assertThat(row.next()).isTrue();
      assertThat(row.getString("email")).isEqualTo("legacy@example.test");
      assertThat(row.getString("password_hash")).isEqualTo("legacy-hash");
      assertThat(row.getInt("credential_version")).isEqualTo(4);
      assertThat(row.getObject("deleted_at")).isNull();
      assertThat(row.getString("title")).isEqualTo("기존 여행");
    }
  }

  void join(Browser owner, Browser target, Map<String, Object> trip)
    throws Exception {
    owner.call(
      "POST",
      "/invitations",
      Map.of("tripId", trip.get("id"), "email", target.email)
    );
    var invite = json
      .mapper()
      .readTree(target.request("GET", "/invitations", null, false).body())
      .get(0)
      .path("id")
      .asText();
    target.call(
      "POST",
      "/invitations/" + invite + "/respond",
      Map.of("accept", true)
    );
  }

  String hash(String token) throws Exception {
    return HexFormat.of().formatHex(
      java.security.MessageDigest.getInstance("SHA-256").digest(
        token.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      )
    );
  }

  class Browser {

    final String email;
    String id;
    final HttpClient client = HttpClient.newBuilder()
      .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
      .build();

    Browser() {
      this(UUID.randomUUID() + "@example.test");
    }

    Browser(String email) {
      this.email = email;
    }

    HttpResponse<String> request(
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
        json
          .read(request("GET", "/auth/csrf", null, false).body(), Map.class)
          .get("token")
          .toString()
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
      var r = request(method, path, body, !method.equals("GET"));
      assertThat(r.statusCode()).as(r.body()).isBetween(200, 299);
      return r.body().startsWith("{")
        ? json.read(r.body(), Map.class)
        : Map.of();
    }

    Browser signup() throws Exception {
      call(
        "POST",
        "/auth/register",
        Map.of(
          "email",
          email,
          "password",
          password,
          "nickname",
          "탈퇴 검증 여행자"
        )
      );
      return login();
    }

    Browser login() throws Exception {
      id = call(
        "POST",
        "/auth/login",
        Map.of("email", email, "password", password)
      )
        .get("id")
        .toString();
      return this;
    }

    Map<String, Object> trip() throws Exception {
      var c = new HashMap<String, Object>();
      c.put("title", "소유한 여행 검증");
      c.put("regions", List.of("서울"));
      c.put("startDate", "2026-11-01");
      c.put("endDate", "2026-11-01");
      c.put("origin", "서울역");
      c.put("partySize", 2);
      c.put("budget", 300000);
      c.put("transport", "TRANSIT");
      c.put("preferences", List.of("관광", "전시"));
      c.put("lodging", "");
      var r = request("POST", "/recommendations", c, true);
      assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
      var items = json.read(
        json.mapper().readTree(r.body()).get(1).path("items").toString(),
        List.class
      );
      return call("POST", "/trips", Map.of("conditions", c, "items", items));
    }
  }
}
