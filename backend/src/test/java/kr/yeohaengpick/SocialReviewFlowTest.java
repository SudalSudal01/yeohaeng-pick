package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import kr.yeohaengpick.common.JsonCodec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** 누락된 초대/참여 입력과 오래된 신고 탐색, 차단 중 초대 수락의 실제 HTTP·DB 경계를 재검증합니다. */
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "spring.datasource.url=jdbc:h2:mem:social-review;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "app.sample-data=true",
    "app.kakao-rest-key=",
    "app.gemini-key=",
    "app.mail-enabled=false",
    "app.tour-api-key=",
  }
)
class SocialReviewFlowTest extends HttpFlowDatabase {

  @LocalServerPort
  int port;

  @Autowired
  JsonCodec json;

  @Autowired
  JdbcTemplate db;

  @Autowired
  DataSource dataSource;

  @Autowired
  kr.yeohaengpick.auth.AuthRateLimit authBudget;

  @BeforeEach
  void resetBudget() {
    ((Map<?, ?>) ReflectionTestUtils.getField(authBudget, "attempts")).clear();
  }

  /** 외부 장소/AI와 무관한 참여 테스트용 여행입니다. 이후 변경과 권한 검사는 실제 HTTP로 통과시킵니다. */
  String trip(Browser owner, Browser... participants) {
    String id = UUID.randomUUID().toString();
    db.update(
      "INSERT INTO trips(id,owner_id,title,regions,start_date,end_date,origin,party_size,budget,transport,preferences,lodging) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
      id,
      owner.id,
      "사회 기능 검증 여행",
      "[\"서울\"]",
      java.sql.Date.valueOf("2026-11-20"),
      java.sql.Date.valueOf("2026-11-20"),
      "서울역",
      2,
      200000,
      "TRANSIT",
      "[]",
      "미정"
    );
    db.update(
      "INSERT INTO trip_members(trip_id,member_id) VALUES(?,?)",
      id,
      owner.id
    );
    for (Browser participant : participants)
      db.update(
        "INSERT INTO trip_members(trip_id,member_id) VALUES(?,?)",
        id,
        participant.id
      );
    return id;
  }

  @Test
  void missingInvitationIdentityOrDecisionReturns400WithoutConsumingInvite()
    throws Exception {
    var owner = new Browser().signup();
    var recipient = new Browser().signup();
    String trip = trip(owner);
    var missingEmail = new HashMap<String, Object>();
    missingEmail.put("tripId", trip);
    for (Map<String, Object> body : List.<Map<String, Object>>of(
      missingEmail,
      Map.of("tripId", trip, "email", " "),
      Map.of("tripId", trip, "email", "not-an-email"),
      Map.of("email", recipient.email)
    ))
      assertThat(
        owner.request("POST", "/invitations", body).statusCode()
      ).isEqualTo(400);
    missingEmail.put("email", null);
    assertThat(
      owner.request("POST", "/invitations", missingEmail).statusCode()
    ).isEqualTo(400);
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM invitations WHERE trip_id=?",
        Integer.class,
        trip
      )
    ).isZero();
    owner.call(
      "POST",
      "/invitations",
      Map.of(
        "tripId",
        trip,
        "email",
        " " + recipient.email.toUpperCase(Locale.ROOT) + " "
      )
    );
    String invitation = db.queryForObject(
      "SELECT id FROM invitations WHERE trip_id=?",
      String.class,
      trip
    );
    var missingDecision = new HashMap<String, Object>();
    assertThat(
      recipient
        .request(
          "POST",
          "/invitations/" + invitation + "/respond",
          missingDecision
        )
        .statusCode()
    ).isEqualTo(400);
    missingDecision.put("accept", null);
    assertThat(
      recipient
        .request(
          "POST",
          "/invitations/" + invitation + "/respond",
          missingDecision
        )
        .statusCode()
    ).isEqualTo(400);
    assertThat(
      db.queryForObject(
        "SELECT status FROM invitations WHERE id=?",
        String.class,
        invitation
      )
    ).isEqualTo("PENDING");
    recipient.call(
      "POST",
      "/invitations/" + invitation + "/respond",
      Map.of("accept", true)
    );
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM trip_members WHERE trip_id=? AND member_id=?",
        Integer.class,
        trip,
        recipient.id
      )
    ).isEqualTo(1);
  }

  @Test
  void missingMembershipVersionCannotLeaveOrRemoveInitialVersionTrip()
    throws Exception {
    var owner = new Browser().signup();
    var companion = new Browser().signup();
    String trip = trip(owner, companion);
    String leave = "/trips/" + trip + "/leave";
    String remove = "/trips/" + trip + "/members/" + companion.id + "/remove";
    assertThat(
      companion.request("POST", leave, Map.of()).statusCode()
    ).isEqualTo(400);
    assertThat(owner.request("POST", remove, Map.of()).statusCode()).isEqualTo(
      400
    );
    var nullVersion = new HashMap<String, Object>();
    nullVersion.put("version", null);
    assertThat(
      companion.request("POST", leave, nullVersion).statusCode()
    ).isEqualTo(400);
    assertThat(
      owner.request("POST", remove, Map.of("version", -1)).statusCode()
    ).isEqualTo(400);
    assertThat(
      db.queryForObject(
        "SELECT version FROM trips WHERE id=?",
        Integer.class,
        trip
      )
    ).isZero();
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM trip_members WHERE trip_id=?",
        Integer.class,
        trip
      )
    ).isEqualTo(2);
    companion.call("POST", leave, Map.of("version", 0));
    assertThat(
      db.queryForObject(
        "SELECT version FROM trips WHERE id=?",
        Integer.class,
        trip
      )
    ).isEqualTo(1);
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM trip_members WHERE trip_id=?",
        Integer.class,
        trip
      )
    ).isEqualTo(1);
  }

  @Test
  void acceptanceAfterCompletedBlockIsRejectedButRecipientCanDecline()
    throws Exception {
    var owner = new Browser().signup();
    var recipient = new Browser().signup();
    String trip = trip(owner);
    owner.call(
      "POST",
      "/invitations",
      Map.of("tripId", trip, "email", recipient.email)
    );
    String invitation = db.queryForObject(
      "SELECT id FROM invitations WHERE trip_id=?",
      String.class,
      trip
    );
    // 이미 완료된 차단은 이후 수락에서 반드시 반영합니다. 동시 시작은 아래 별도 테스트에서
    // 같은 회원 잠금을 먼저 얻은 요청의 순서로 처리하며, 여행 잠금을 밖에서 잡고 차단을 기다리지 않습니다.
    recipient.call(
      "PUT",
      "/messages/blocks/" + owner.id,
      Map.of("enabled", true)
    );
    assertThat(
      recipient
        .request(
          "POST",
          "/invitations/" + invitation + "/respond",
          Map.of("accept", true)
        )
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      db.queryForObject(
        "SELECT status FROM invitations WHERE id=?",
        String.class,
        invitation
      )
    ).isEqualTo("PENDING");
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM trip_members WHERE trip_id=? AND member_id=?",
        Integer.class,
        trip,
        recipient.id
      )
    ).isZero();
    recipient.call(
      "POST",
      "/invitations/" + invitation + "/respond",
      Map.of("accept", false)
    );
    assertThat(
      db.queryForObject(
        "SELECT status FROM invitations WHERE id=?",
        String.class,
        invitation
      )
    ).isEqualTo("DECLINED");
  }

  @Test
  void simultaneousBlockAndAcceptanceFollowSharedMemberLockOrder()
    throws Exception {
    var owner = new Browser().signup();
    var recipient = new Browser().signup();
    String trip = trip(owner);
    owner.call(
      "POST",
      "/invitations",
      Map.of("tripId", trip, "email", recipient.email)
    );
    String invitation = db.queryForObject(
      "SELECT id FROM invitations WHERE trip_id=?",
      String.class,
      trip
    );
    String token = recipient.csrf();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var barrier = new CyclicBarrier(2);
      var acceptance = pool.submit(() -> {
        barrier.await();
        return recipient.requestWithToken(
          "POST",
          "/invitations/" + invitation + "/respond",
          Map.of("accept", true),
          token
        );
      });
      var block = pool.submit(() -> {
        barrier.await();
        return recipient.requestWithToken(
          "PUT",
          "/messages/blocks/" + owner.id,
          Map.of("enabled", true),
          token
        );
      });
      int accepted = acceptance.get(8, TimeUnit.SECONDS).statusCode();
      assertThat(block.get(8, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
      assertThat(accepted).isIn(200, 403);
      // 수락이 회원 잠금을 먼저 얻으면 참여가 먼저 생기고 차단이 뒤에 완료됩니다.
      // 차단은 기존 여행 참여를 자동 해제하지 않으므로 두 경우의 최종 상태를 각각 검증합니다.
      assertThat(
        db.queryForObject(
          "SELECT status FROM invitations WHERE id=?",
          String.class,
          invitation
        )
      ).isEqualTo(accepted == 200 ? "ACCEPTED" : "PENDING");
      assertThat(
        db.queryForObject(
          "SELECT COUNT(*) FROM trip_members WHERE trip_id=? AND member_id=?",
          Integer.class,
          trip,
          recipient.id
        )
      ).isEqualTo(accepted == 200 ? 1 : 0);
      assertThat(
        db.queryForObject(
          "SELECT COUNT(*) FROM blocks WHERE blocker_id=? AND blocked_id=?",
          Integer.class,
          recipient.id,
          owner.id
        )
      ).isEqualTo(1);
    }
  }

  @Test
  void concurrentSharedImportAndInvitationAcceptanceDoNotReverseMemberTripLocks()
    throws Exception {
    concurrentImportAndInvitation(true);
  }

  @Test
  void concurrentSharedImportAndInvitationSendDoNotReverseMemberTripLocks()
    throws Exception {
    concurrentImportAndInvitation(false);
  }

  /** 두 실제 HTTP 요청을 회원 행에서 기다리게 한 뒤 원본 여행이 미리 잠기지 않았음을 NOWAIT로 검사합니다. */
  void concurrentImportAndInvitation(boolean acceptance) throws Exception {
    var owner = new Browser().signup();
    var recipient = new Browser().signup();
    var conditions = new HashMap<String, Object>(
      Map.of(
        "title",
        "공유 원본과 초대 교차 검증",
        "regions",
        List.of("서울"),
        "startDate",
        "2027-01-15",
        "endDate",
        "2027-01-15",
        "origin",
        "서울역",
        "partySize",
        2,
        "budget",
        200000,
        "transport",
        "TRANSIT",
        "preferences",
        List.of("관광"),
        "lodging",
        ""
      )
    );
    var recommended = owner.request("POST", "/recommendations", conditions);
    assertThat(recommended.statusCode()).as(recommended.body()).isEqualTo(200);
    var firstCourse = json.mapper().readTree(recommended.body()).get(0);
    String trip = owner
      .call(
        "POST",
        "/trips",
        Map.of(
          "conditions",
          conditions,
          "items",
          json.read(firstCourse.get("items").toString(), List.class)
        )
      )
      .get("id")
      .toString();
    String shareToken = owner
      .call("POST", "/trips/" + trip + "/share", Map.of("enabled", true))
      .get("token")
      .toString();
    String snapshot = new Browser()
      .call("GET", "/share/" + shareToken, null)
      .get("snapshot")
      .toString();
    String invitation = null;
    if (acceptance) {
      owner.call(
        "POST",
        "/invitations",
        Map.of("tripId", trip, "email", recipient.email)
      );
      invitation = db.queryForObject(
        "SELECT id FROM invitations WHERE trip_id=?",
        String.class,
        trip
      );
    }
    conditions.put("title", "가져온 독립 여행");
    conditions.put("origin", "내 출발지");
    String requestId = UUID.randomUUID().toString();
    var importRequest = Map.of(
      "token",
      shareToken,
      "snapshot",
      snapshot,
      "requestId",
      requestId,
      "conditions",
      conditions
    );
    String recipientToken = recipient.csrf();
    String invitationToken = acceptance ? recipientToken : owner.csrf();
    Browser invitationActor = acceptance ? recipient : owner;
    String invitationPath = acceptance
      ? "/invitations/" + invitation + "/respond"
      : "/invitations";
    Map<String, Object> invitationBody = acceptance
      ? Map.of("accept", true)
      : Map.of("tripId", trip, "email", recipient.email);
    try (
      var hold = dataSource.getConnection();
      var pool = Executors.newVirtualThreadPerTaskExecutor()
    ) {
      hold.setAutoCommit(false);
      try {
        try (
          var statement = hold.prepareStatement(
            "SELECT id FROM members WHERE id=? FOR UPDATE"
          )
        ) {
          statement.setString(1, recipient.id);
          try (var row = statement.executeQuery()) {
            assertThat(row.next()).isTrue();
          }
        }
        var copied = pool.submit(() ->
          recipient.requestWithToken(
            "POST",
            "/trips/import",
            importRequest,
            recipientToken
          )
        );
        awaitMemberLock(
          "kr.yeohaengpick.trip.TripSharingService",
          "importTrip"
        );
        var invitationResponse = pool.submit(() ->
          invitationActor.requestWithToken(
            "POST",
            invitationPath,
            invitationBody,
            invitationToken
          )
        );
        awaitMemberLock(
          "kr.yeohaengpick.collaboration.InvitationController",
          acceptance ? "respond" : "invite"
        );
        // 예전 여행→회원 구현은 여기서 이미 원본 여행 행을 잡아 NOWAIT가 실패합니다.
        // 두 작업이 회원부터 기다리면 원본을 잡지 않으므로 잠금 순서 역전을 결정적으로 검사할 수 있습니다.
        try (var probe = dataSource.getConnection()) {
          probe.setAutoCommit(false);
          try (
            var statement = probe.prepareStatement(
              "SELECT id FROM trips WHERE id=? FOR UPDATE NOWAIT"
            )
          ) {
            statement.setString(1, trip);
            try (var row = statement.executeQuery()) {
              assertThat(row.next()).isTrue();
            }
          } finally {
            probe.rollback();
          }
        }
        hold.rollback();
        var copiedResponse = copied.get(8, TimeUnit.SECONDS);
        assertThat(copiedResponse.statusCode())
          .as(copiedResponse.body())
          .isEqualTo(200);
        var sent = invitationResponse.get(8, TimeUnit.SECONDS);
        assertThat(sent.statusCode()).as(sent.body()).isEqualTo(200);
        String copyId = json
          .read(copiedResponse.body(), Map.class)
          .get("id")
          .toString();
        assertThat(copyId).isNotEqualTo(trip);
        assertThat(
          recipient.call("GET", "/trips/" + copyId, null).get("ownerId")
        ).isEqualTo(recipient.id);
        assertThat(
          db.queryForObject(
            "SELECT COUNT(*) FROM trip_imports WHERE member_id=? AND request_id=?",
            Integer.class,
            recipient.id,
            requestId
          )
        ).isEqualTo(1);
        assertThat(
          db.queryForObject(
            "SELECT COUNT(*) FROM trip_members WHERE trip_id=? AND member_id=?",
            Integer.class,
            trip,
            recipient.id
          )
        ).isEqualTo(acceptance ? 1 : 0);
        assertThat(
          db.queryForObject(
            "SELECT status FROM invitations WHERE trip_id=? AND recipient_id=?",
            String.class,
            trip,
            recipient.id
          )
        ).isEqualTo(acceptance ? "ACCEPTED" : "PENDING");
      } finally {
        // 단언이 실패해도 외부에서 잡은 회원 잠금을 먼저 풀어 HTTP 작업을 기다리는 executor 종료와 교착하지 않습니다.
        hold.rollback();
      }
    }
  }

  void awaitMemberLock(String className, String method) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    boolean waiting = false;
    while (System.nanoTime() < deadline && !waiting) {
      waiting = Thread.getAllStackTraces()
        .values()
        .stream()
        .anyMatch(
          stack ->
            Arrays.stream(stack).anyMatch(
              frame ->
                frame.getClassName().equals(className) &&
                frame.getMethodName().equals(method)
            ) &&
            Arrays.stream(stack).anyMatch(
              frame ->
                frame.getMethodName().equals("lockMember") &&
                frame.getClassName().contains("Proxy")
            )
        );
      if (!waiting) Thread.sleep(10);
    }
    assertThat(waiting)
      .as(
        className +
          "." +
          method +
          " HTTP가 회원 행 잠금을 기다려야 교차 잠금 순서를 검증할 수 있습니다"
      )
      .isTrue();
  }

  @Test
  void administratorCanReachOlderReportsWithStatusBeforePagination()
    throws Exception {
    var member = new Browser().signup();
    var admin = new Browser().signup().admin();
    assertThat(
      member.request("GET", "/admin/reports/search", null).statusCode()
    ).isEqualTo(403);
    assertThat(
      new Browser().request("GET", "/admin/reports/search", null).statusCode()
    ).isEqualTo(401);
    for (String query : List.of(
      "status=unknown",
      "page=0",
      "page=-1",
      "pageSize=31",
      "pageSize=0"
    ))
      assertThat(
        admin
          .request("GET", "/admin/reports/search?" + query, null)
          .statusCode()
      ).isEqualTo(400);
    var created = new ArrayList<String>();
    var time = LocalDateTime.now().plusDays(2);
    try {
      // 미래 시각의 합성 자료만 사용해 기존 Oracle QA 자료와 섞이지 않게 순서를 고정하고 마지막에 이 ID들만 정리합니다.
      for (int i = 0; i <= 110; i++) {
        String id = UUID.randomUUID().toString();
        created.add(id);
        db.update(
          "INSERT INTO reports(id,reporter_id,target_type,target_id,reason,status,resolution,created_at) VALUES(?,?,?,?,?,?,?,?)",
          id,
          member.id,
          "USER",
          member.id,
          "페이지 검증 " + i,
          i == 0 ? "OPEN" : "RESOLVED",
          i == 0 ? " " : "기존 검증 처리",
          Timestamp.valueOf(time.plusSeconds(i))
        );
      }
      String oldOpen = created.getFirst();
      assertThat(
        admin
          .list("/admin/reports")
          .stream()
          .map(row -> row.get("id"))
      ).doesNotContain(oldOpen);
      var open = admin.call(
        "GET",
        "/admin/reports/search?status=OPEN&pageSize=20",
        null
      );
      assertThat(rows(open)).allSatisfy(row ->
        assertThat(row.get("status")).isEqualTo("OPEN")
      );
      assertThat(
        rows(open)
          .stream()
          .map(row -> row.get("id"))
      ).contains(oldOpen);
      var first = admin.call(
        "GET",
        "/admin/reports/search?status=ALL&pageSize=30",
        null
      );
      assertThat(rows(first)).hasSize(30);
      var seen = new HashSet<String>();
      int pages = ((Number) first.get("totalPages")).intValue();
      for (int page = 1; page <= pages; page++) {
        var result = admin.call(
          "GET",
          "/admin/reports/search?status=ALL&pageSize=30&page=" + page,
          null
        );
        for (var row : rows(result))
          assertThat(seen.add(row.get("id").toString())).isTrue();
      }
      assertThat(seen).containsAll(created);
      var corrected = admin.call(
        "GET",
        "/admin/reports/search?status=ALL&pageSize=30&page=1000000",
        null
      );
      assertThat(corrected.get("page")).isEqualTo(pages);
      admin.call(
        "POST",
        "/admin/reports/" + oldOpen + "/resolve",
        Map.of("resolution", "미처리 내역 확인", "hidePost", false)
      );
      var resolved = admin.call(
        "GET",
        "/admin/reports/search?status=RESOLVED&pageSize=30",
        null
      );
      assertThat(rows(resolved)).allSatisfy(row ->
        assertThat(row.get("status")).isEqualTo("RESOLVED")
      );
    } finally {
      for (String id : created)
        db.update(
          "DELETE FROM reports WHERE id=? AND reporter_id=?",
          id,
          member.id
        );
    }
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> rows(Map<String, Object> page) {
    return (List<Map<String, Object>>) page.get("items");
  }

  @Test
  void trashPaginationKeepsAllOwnDeletedRecordsPrivateAndOlderRecordCanBeRestored()
    throws Exception {
    var member = new Browser().signup();
    var other = new Browser().signup();
    assertThat(
      new Browser().request("GET", "/community/trash/search", null).statusCode()
    ).isEqualTo(401);
    for (String query : List.of(
      "page=0",
      "page=-1",
      "pageSize=31",
      "pageSize=0"
    ))
      assertThat(
        member
          .request("GET", "/community/trash/search?" + query, null)
          .statusCode()
      ).isEqualTo(400);
    var created = new ArrayList<String>();
    String othersId = UUID.randomUUID().toString();
    try {
      for (int i = 0; i < 111; i++) {
        String id = UUID.randomUUID().toString();
        created.add(id);
        db.update(
          "INSERT INTO posts(id,author_id,category,region,title,body,deleted_at) VALUES(?,?,?,?,?,?,?)",
          id,
          member.id,
          "후기",
          "서울",
          "삭제 기록 " + i,
          "목록에 포함하면 안 되는 본문",
          Timestamp.valueOf(LocalDateTime.now().plusSeconds(i))
        );
      }
      db.update(
        "INSERT INTO posts(id,author_id,category,region,title,body,deleted_at) VALUES(?,?,?,?,?,?,?)",
        othersId,
        other.id,
        "후기",
        "서울",
        "다른 회원 삭제 기록",
        "다른 회원 본문",
        Timestamp.valueOf(LocalDateTime.now())
      );
      String oldest = created.getFirst();
      assertThat(
        member
          .list("/community/trash")
          .stream()
          .map(row -> row.get("id"))
      ).doesNotContain(oldest);
      var seen = new HashSet<String>();
      for (int page = 1; page <= 4; page++) {
        var result = member.call(
          "GET",
          "/community/trash/search?pageSize=30&page=" +
            page +
            "&memberId=" +
            other.id,
          null
        );
        assertThat(result.get("total")).isEqualTo(111);
        for (var row : rows(result)) {
          assertThat(row).containsOnlyKeys(
            "id",
            "category",
            "region",
            "title",
            "version",
            "deletedAt",
            "hidden"
          );
          assertThat(seen.add(row.get("id").toString())).isTrue();
        }
      }
      assertThat(seen)
        .containsExactlyInAnyOrderElementsOf(created)
        .doesNotContain(othersId);
      assertThat(rows(other.call("GET", "/community/trash/search", null)))
        .hasSize(1)
        .allSatisfy(row -> assertThat(row.get("id")).isEqualTo(othersId));
      var last = member.call(
        "GET",
        "/community/trash/search?pageSize=30&page=1000000",
        null
      );
      assertThat(last.get("page")).isEqualTo(4);
      assertThat(
        member.request("GET", "/community/posts/" + oldest, null).statusCode()
      ).isEqualTo(404);
      member.call(
        "POST",
        "/community/posts/" + oldest + "/restore",
        Map.of("version", 0)
      );
      assertThat(
        member.request("GET", "/community/posts/" + oldest, null).statusCode()
      ).isEqualTo(200);
      assertThat(
        member.call("GET", "/community/trash/search", null).get("total")
      ).isEqualTo(110);
    } finally {
      for (String id : created)
        db.update(
          "DELETE FROM posts WHERE id=? AND author_id=?",
          id,
          member.id
        );
      db.update(
        "DELETE FROM posts WHERE id=? AND author_id=?",
        othersId,
        other.id
      );
    }
  }

  class Browser {

    final String email = UUID.randomUUID() + "@example.test";
    final HttpClient client = HttpClient.newBuilder()
      .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
      .build();
    String id;

    String csrf() throws Exception {
      return call("GET", "/auth/csrf", null).get("token").toString();
    }

    HttpResponse<String> request(String method, String path, Object body)
      throws Exception {
      return requestWithToken(
        method,
        path,
        body,
        method.equals("GET") ? null : csrf()
      );
    }

    HttpResponse<String> requestWithToken(
      String method,
      String path,
      Object body,
      String token
    ) throws Exception {
      var builder = HttpRequest.newBuilder(
        URI.create("http://127.0.0.1:" + port + "/api" + path)
      )
        .timeout(Duration.ofSeconds(12))
        .header("Content-Type", "application/json");
      if (token != null) builder.header("X-XSRF-TOKEN", token);
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
      var response = request(method, path, body);
      assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
      return response.body().startsWith("{")
        ? json.read(response.body(), Map.class)
        : Map.of();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list(String path) throws Exception {
      var response = request("GET", path, null);
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      return json.read(response.body(), List.class);
    }

    Browser signup() throws Exception {
      call(
        "POST",
        "/auth/register",
        Map.of(
          "email",
          email,
          "password",
          "Social-Review-Test12!",
          "nickname",
          "사회 기능 검증자"
        )
      );
      call(
        "POST",
        "/auth/login",
        Map.of("email", email, "password", "Social-Review-Test12!")
      );
      id = call("GET", "/auth/me", null).get("id").toString();
      return this;
    }

    Browser admin() throws Exception {
      db.update("UPDATE members SET role='ADMIN' WHERE id=?", id);
      call(
        "POST",
        "/auth/login",
        Map.of("email", email, "password", "Social-Review-Test12!")
      );
      return this;
    }
  }
}
