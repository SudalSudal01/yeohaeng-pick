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
import org.springframework.jdbc.core.JdbcTemplate;

/** 가짜 회원과 실제 HTTP로 증거 보존·최소 공개·관리자 권한·동시 처리를 H2/Oracle에서 검증합니다. */
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
class ReportFlowTest extends HttpFlowDatabase {

  @LocalServerPort
  int port;

  @Autowired
  JsonCodec json;

  @Autowired
  JdbcTemplate db;

  @Autowired
  kr.yeohaengpick.auth.AuthRateLimit authRateLimit;

  @Autowired
  kr.yeohaengpick.community.CommunityPostService posts;

  @BeforeEach
  void isolateAuthenticationBudget() {
    (
      (Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(
        authRateLimit,
        "attempts"
      )
    ).clear();
  }

  Map<String, Object> content(String title, String body) {
    return Map.of(
      "category",
      "후기",
      "region",
      "서울",
      "title",
      title,
      "body",
      body
    );
  }

  String report(Browser reporter, Browser admin, String type, String target)
    throws Exception {
    String reason = "검증 사유 " + UUID.randomUUID();
    reporter.call(
      "POST",
      "/reports",
      Map.of("type", type, "targetId", target, "reason", reason)
    );
    return admin
      .list()
      .stream()
      .filter(r -> reason.equals(r.get("reason")))
      .findFirst()
      .orElseThrow()
      .get("id")
      .toString();
  }

  Map<String, Object> report(Browser admin, String id) throws Exception {
    return admin
      .list()
      .stream()
      .filter(r -> id.equals(r.get("id")))
      .findFirst()
      .orElseThrow();
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> evidence(Map<String, Object> report) {
    return (Map<String, Object>) report.get("evidence");
  }

  @Test
  void postEvidenceSurvivesEditDeleteAndWithdrawalWithUnicode()
    throws Exception {
    var owner = new Browser().signup();
    var reporter = new Browser().signup();
    var admin = new Browser().signup().admin();
    // 8K 경계를 넘는 한글/이모지 JSON도 CLOB 경로에서 원문 그대로 보존되어야 합니다.
    String body = "여행🙂".repeat(1900);
    var p = owner.call(
      "POST",
      "/community/posts",
      content("신고 당시 제목", body)
    );
    String target = p.get("id").toString();
    String id = report(reporter, admin, "POST", target);
    var saved = report(admin, id);
    assertThat(evidence(saved))
      .containsEntry("body", body)
      .containsEntry("title", "신고 당시 제목")
      .containsEntry("version", 0);
    assertThat(saved.get("capturedAt")).isNotNull();
    owner.call(
      "PUT",
      "/community/posts/" + target,
      Map.of("version", 0, "content", content("바뀐 제목", "바뀐 본문"))
    );
    owner.call("DELETE", "/community/posts/" + target, Map.of("version", 1));
    owner.call(
      "DELETE",
      "/auth/account",
      Map.of("password", "Community-Test-Only12!", "confirmed", true)
    );
    assertThat(report(admin, id)).isEqualTo(saved);
    assertThat(json.write(evidence(saved))).doesNotContain(
      owner.email,
      "passwordHash",
      "preferences",
      "comments"
    );
  }

  @Test
  void onlyParticipantCanCaptureOneMessageWithoutRestOfRoom() throws Exception {
    var sender = new Browser().signup();
    var receiver = new Browser().signup();
    var outsider = new Browser().signup();
    var admin = new Browser().signup().admin();
    var room = sender.call(
      "POST",
      "/messages",
      Map.of("email", receiver.email, "body", "신고한 메시지")
    );
    String roomId = room.get("id").toString();
    var messages = json.mapper().valueToTree(room).path("messages");
    String target = messages.get(0).path("id").asText();
    receiver.call(
      "POST",
      "/messages/" + roomId + "/respond",
      Map.of("accept", true)
    );
    sender.call(
      "POST",
      "/messages/" + roomId,
      Map.of("body", "신고하지 않은 비공개 메시지")
    );
    assertThat(
      outsider
        .request(
          "POST",
          "/reports",
          Map.of("type", "MESSAGE", "targetId", target, "reason", "침입"),
          true
        )
        .statusCode()
    ).isEqualTo(403);
    String id = report(receiver, admin, "MESSAGE", target);
    var saved = evidence(report(admin, id));
    assertThat(saved)
      .containsEntry("body", "신고한 메시지")
      .doesNotContainKeys("conversationId", "messages", "email");
    assertThat(json.write(saved)).doesNotContain(
      sender.email,
      receiver.email,
      "신고하지 않은"
    );
    sender.call(
      "DELETE",
      "/auth/account",
      Map.of("password", "Community-Test-Only12!", "confirmed", true)
    );
    assertThat(evidence(report(admin, id))).isEqualTo(saved);
  }

  @Test
  void userEvidenceContainsOnlyPublicIdentity() throws Exception {
    var target = new Browser().signup();
    var reporter = new Browser().signup();
    var admin = new Browser().signup().admin();
    String id = report(reporter, admin, "USER", target.id());
    assertThat(evidence(report(admin, id)))
      .containsOnlyKeys("id", "nickname")
      .containsEntry("id", target.id());
  }

  @Test
  void invalidTargetsAndMissingTypeAndHiddenPostsAreRejected()
    throws Exception {
    var owner = new Browser().signup();
    var reporter = new Browser().signup();
    assertThat(
      reporter
        .request(
          "POST",
          "/reports",
          Map.of("targetId", "missing", "reason", "사유"),
          true
        )
        .statusCode()
    ).isEqualTo(400);
    for (String type : List.of("POST", "USER", "MESSAGE"))
      assertThat(
        reporter
          .request(
            "POST",
            "/reports",
            Map.of(
              "type",
              type,
              "targetId",
              UUID.randomUUID().toString(),
              "reason",
              "사유"
            ),
            true
          )
          .statusCode()
      ).isEqualTo(404);
    var p = owner.call(
      "POST",
      "/community/posts",
      content("비공개 검증", "본문")
    );
    String id = p.get("id").toString();
    posts.hide(id, true);
    assertThat(
      reporter
        .request(
          "POST",
          "/reports",
          Map.of("type", "POST", "targetId", id, "reason", "숨김"),
          true
        )
        .statusCode()
    ).isEqualTo(404);
    posts.hide(id, false);
    owner.call("DELETE", "/community/posts/" + id, Map.of("version", 2));
    assertThat(
      reporter
        .request(
          "POST",
          "/reports",
          Map.of("type", "POST", "targetId", id, "reason", "삭제"),
          true
        )
        .statusCode()
    ).isEqualTo(404);
  }

  @Test
  void reportAndResolutionRequireAuthenticationRoleAndCsrf() throws Exception {
    var user = new Browser().signup();
    var admin = new Browser().signup().admin();
    var visitor = new Browser();
    String id = report(user, admin, "USER", user.id());
    var resolution = Map.of("resolution", "권한 검사", "hidePost", false);
    assertThat(
      visitor.request("GET", "/admin/reports", null, false).statusCode()
    ).isEqualTo(401);
    assertThat(
      user.request("GET", "/admin/reports", null, false).statusCode()
    ).isEqualTo(403);
    assertThat(
      user
        .request("POST", "/admin/reports/" + id + "/resolve", resolution, true)
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      admin
        .request("POST", "/admin/reports/" + id + "/resolve", resolution, false)
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      user
        .request(
          "POST",
          "/reports",
          Map.of("type", "USER", "targetId", user.id(), "reason", "사유"),
          false
        )
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      visitor
        .request(
          "POST",
          "/reports",
          Map.of("type", "USER", "targetId", user.id(), "reason", "사유"),
          true
        )
        .statusCode()
    ).isEqualTo(401);
    assertThat(report(admin, id)).containsEntry("status", "OPEN");
  }

  @Test
  void resolutionPreservesEvidenceAndSupportsExplicitHideAndLongReason()
    throws Exception {
    var user = new Browser().signup();
    var admin = new Browser().signup().admin();
    var p = user.call(
      "POST",
      "/community/posts",
      content("처리 검증", "보존할 본문")
    );
    String target = p.get("id").toString();
    String first = report(user, admin, "POST", target);
    var evidence = evidence(report(admin, first));
    String note = "확인".repeat(500);
    admin.call(
      "POST",
      "/admin/reports/" + first + "/resolve",
      Map.of("resolution", note, "hidePost", false)
    );
    assertThat(
      user.call("GET", "/community/posts/" + target, null)
    ).containsEntry("version", 0);
    var resolved = report(admin, first);
    assertThat(resolved)
      .containsEntry("resolution", note)
      .containsEntry("status", "RESOLVED")
      .containsEntry("resolvedBy", admin.id());
    assertThat(resolved.get("resolvedAt")).isNotNull();
    assertThat(evidence(resolved)).isEqualTo(evidence);
    String second = report(user, admin, "POST", target);
    admin.call(
      "POST",
      "/admin/reports/" + second + "/resolve",
      Map.of("resolution", "숨김 필요", "hidePost", true)
    );
    assertThat(
      user
        .request("GET", "/community/posts/" + target, null, false)
        .statusCode()
    ).isEqualTo(404);
    assertThat(
      db.queryForObject(
        "SELECT version FROM posts WHERE id=?",
        Integer.class,
        target
      )
    ).isEqualTo(1);
    String member = report(user, admin, "USER", user.id());
    assertThat(
      admin
        .request(
          "POST",
          "/admin/reports/" + member + "/resolve",
          Map.of("resolution", "잘못된 조치", "hidePost", true),
          true
        )
        .statusCode()
    ).isEqualTo(400);
    assertThat(report(admin, member)).containsEntry("status", "OPEN");
  }

  @Test
  void simultaneousResolutionHasOneWinnerAndOneAudit() throws Exception {
    var user = new Browser().signup();
    var a = new Browser().signup().admin();
    var b = new Browser().signup().admin();
    String id = report(user, a, "USER", user.id());
    String path = "/admin/reports/" + id + "/resolve";
    try (
      var workers =
        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
    ) {
      var start = new java.util.concurrent.CountDownLatch(1);
      var one = workers.submit(() -> {
        start.await();
        return a
          .request(
            "POST",
            path,
            Map.of("resolution", "관리자 A", "hidePost", false),
            true
          )
          .statusCode();
      });
      var two = workers.submit(() -> {
        start.await();
        return b
          .request(
            "POST",
            path,
            Map.of("resolution", "관리자 B", "hidePost", false),
            true
          )
          .statusCode();
      });
      start.countDown();
      assertThat(List.of(one.get(), two.get())).containsExactlyInAnyOrder(
        200,
        409
      );
    }
    var saved = report(a, id);
    assertThat(
      a
        .request(
          "POST",
          path,
          Map.of("resolution", "다시 처리", "hidePost", false),
          true
        )
        .statusCode()
    ).isEqualTo(409);
    assertThat(report(a, id)).isEqualTo(saved);
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM admin_audit WHERE action LIKE ?",
        Integer.class,
        "신고 " + id + " 처리%"
      )
    ).isEqualTo(1);
  }

  @Test
  void allDomainTablesAndColumnsHaveDatabaseDescriptions() throws Exception {
    boolean oracle;
    try (var connection = db.getDataSource().getConnection()) {
      oracle = connection
        .getMetaData()
        .getDatabaseProductName()
        .equals("Oracle");
    }
    // 실제 DB 사전의 설명을 검사합니다. SQL 파일의 주석이 있다는 것만으로 통과시키지 않습니다.
    String tables = oracle
      ? "SELECT COUNT(*) FROM user_tab_comments WHERE table_type='TABLE' AND table_name<>'flyway_schema_history' AND comments IS NULL"
      : "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name<>'flyway_schema_history' AND remarks IS NULL";
    String columns = oracle
      ? "SELECT COUNT(*) FROM user_col_comments WHERE table_name<>'flyway_schema_history' AND comments IS NULL"
      : "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='public' AND table_name<>'flyway_schema_history' AND remarks IS NULL";
    assertThat(db.queryForObject(tables, Integer.class)).isZero();
    assertThat(db.queryForObject(columns, Integer.class)).isZero();
  }

  @Test
  void legacyReportMigrationPreservesReasonWithoutInventingEvidence()
    throws Exception {
    String url =
      "jdbc:h2:mem:legacy-report-" +
      UUID.randomUUID() +
      ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    org.flywaydb.core.Flyway.configure()
      .dataSource(url, "sa", "")
      .target("11")
      .load()
      .migrate();
    try (
      var c = java.sql.DriverManager.getConnection(url, "sa", "");
      var s = c.createStatement()
    ) {
      s.executeUpdate(
        "INSERT INTO members(id,email,password_hash,nickname) VALUES('legacy','legacy@example.test','fake','검증 회원')"
      );
      s.executeUpdate(
        "INSERT INTO reports(id,reporter_id,target_type,target_id,reason,status,resolution) VALUES('legacy-report','legacy','USER','legacy','기존 사유','RESOLVED','기존 처리')"
      );
    }
    org.flywaydb.core.Flyway.configure()
      .dataSource(url, "sa", "")
      .load()
      .migrate();
    try (
      var c = java.sql.DriverManager.getConnection(url, "sa", "");
      var s = c.createStatement();
      var row = s.executeQuery("SELECT * FROM reports WHERE id='legacy-report'")
    ) {
      assertThat(row.next()).isTrue();
      assertThat(row.getString("reason")).isEqualTo("기존 사유");
      assertThat(row.getString("resolution")).isEqualTo("기존 처리");
      assertThat(row.getString("evidence")).isNull();
      assertThat(row.getObject("captured_at")).isNull();
      assertThat(row.getObject("resolved_at")).isNull();
    }
    // 최신 앱의 이전 신고 응답도 소급 보존이 아니라 NULL임을 확인합니다.
    var user = new Browser().signup();
    var admin = new Browser().signup().admin();
    String id = UUID.randomUUID().toString();
    db.update(
      "INSERT INTO reports(id,reporter_id,target_type,target_id,reason) VALUES(?,?,?,?,?)",
      id,
      user.id(),
      "USER",
      user.id(),
      "이전 신고"
    );
    assertThat(report(admin, id).get("evidence")).isNull();
  }

  class Browser {

    final String email = UUID.randomUUID() + "@example.test";
    final HttpClient client = HttpClient.newBuilder()
      .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
      .build();

    List<?> callList(String path) throws Exception {
      var response = request("GET", path, null, false);
      // 오류 본문에 ID가 없다는 이유만으로 공개 제외 검사가 통과하지 않게 정상 응답도 검사합니다.
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      return json.read(response.body(), List.class);
    }

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
      var response = request(method, path, body, !method.equals("GET"));
      assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
      return response.body().startsWith("{")
        ? json.read(response.body(), Map.class)
        : Map.of();
    }

    String id() throws Exception {
      return call("GET", "/auth/me", null).get("id").toString();
    }

    Browser admin() throws Exception {
      // 이 테스트가 방금 만든 가짜 계정만 승격한 뒤 새 세션 권한으로 로그인합니다.
      db.update("UPDATE members SET role='ADMIN' WHERE id=?", id());
      call(
        "POST",
        "/auth/login",
        Map.of("email", email, "password", "Community-Test-Only12!")
      );
      return this;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list() throws Exception {
      return (List<Map<String, Object>>) (List<?>) callList("/admin/reports");
    }

    Browser signup() throws Exception {
      call(
        "POST",
        "/auth/register",
        Map.of(
          "email",
          email,
          "password",
          "Community-Test-Only12!",
          "nickname",
          "커뮤니티 검증자"
        )
      );
      call(
        "POST",
        "/auth/login",
        Map.of("email", email, "password", "Community-Test-Only12!")
      );
      return this;
    }
  }
}
