package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.util.*;
import kr.yeohaengpick.common.JsonCodec;
import kr.yeohaengpick.community.CommunityPostService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** 실제 HTTP·세션·CSRF·V8 메모리 DB로 작성자 관리와 공개 제외, 동시 편집을 검증합니다. 외부 API/메일은 호출하지 않습니다. */
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
class CommunityFlowTest {

  @LocalServerPort
  int port;

  @Autowired
  JsonCodec json;

  @Autowired
  kr.yeohaengpick.auth.AuthRateLimit authRateLimit;

  @Autowired
  CommunityPostService posts;

  @BeforeEach
  void isolateAuthenticationBudget() {
    (
      (Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(
        authRateLimit,
        "attempts"
      )
    ).clear();
  }

  Map<String, Object> content(String category, String title) {
    var p = new HashMap<String, Object>();
    p.put("category", category);
    p.put("region", "서울");
    p.put("title", title);
    p.put("body", "함께 나눌 공개 여행 이야기");
    p.put("recruitmentDate", "2026-11-09");
    p.put("capacity", 2);
    return p;
  }

  Map<String, Object> edit(Map<String, Object> p, Map<String, Object> content) {
    return Map.of("version", p.get("version"), "content", content);
  }

  @Test
  void ownerEditsRecruitmentWithoutLosingCommentsOrStatus() throws Exception {
    var owner = new Browser().signup();
    var reader = new Browser().signup();
    var original = owner.call(
      "POST",
      "/community/posts",
      content("동행", "처음 이야기")
    );
    String base = "/community/posts/" + original.get("id");
    reader.call(
      "POST",
      base + "/comments",
      Map.of("body", "수정 전 대화의 맥락")
    );
    var closed = owner.call(
      "PUT",
      base + "/status",
      Map.of("version", 0, "status", "CLOSED")
    );
    var change = content("동행", "  변경한 제목  ");
    change.put("region", "  부산  ");
    change.put("body", "  변경한 내용\n두 번째 줄  ");
    change.put("capacity", 4);
    change.put("recruitmentDate", "2026-11-15");
    var saved = owner.call("PUT", base, edit(closed, change));
    assertThat(saved)
      .containsEntry("version", 2)
      .containsEntry("title", "변경한 제목")
      .containsEntry("region", "부산")
      .containsEntry("body", "변경한 내용\n두 번째 줄")
      .containsEntry("status", "CLOSED")
      .containsEntry("capacity", 4)
      .containsEntry("recruitmentDate", "2026-11-15")
      .containsEntry("createdAt", original.get("createdAt"));
    assertThat(saved.get("updatedAt")).isNotNull();
    assertThat(json.write(saved.get("comments"))).contains(
      "수정 전 대화의 맥락"
    );
    assertThat(new Browser().call("GET", base, null)).isEqualTo(saved);
    assertThat(
      owner
        .request(
          "PUT",
          base,
          edit(closed, content("동행", "오래된 덮어쓰기")),
          true
        )
        .statusCode()
    ).isEqualTo(409);
    assertThat(owner.call("GET", base, null)).isEqualTo(saved);
  }

  @Test
  void authorAndCsrfAndNestedInputsAreCheckedOnServer() throws Exception {
    var owner = new Browser().signup();
    var other = new Browser().signup();
    var visitor = new Browser();
    var p = owner.call(
      "POST",
      "/community/posts",
      content("후기", "검증 후기")
    );
    String base = "/community/posts/" + p.get("id");
    // 일반 글에 숨겨 보낸 모집 조건은 제거합니다. 수정 중 다른 게시판으로의 전환도 거절합니다.
    assertThat(p.get("capacity")).isNull();
    assertThat(p.get("recruitmentDate")).isNull();
    assertThat(
      visitor.request("GET", "/community/trash", null, false).statusCode()
    ).isEqualTo(401);
    assertThat(
      visitor
        .request("PUT", base, edit(p, content("후기", "침입")), true)
        .statusCode()
    ).isEqualTo(401);
    assertThat(
      other
        .request("PUT", base, edit(p, content("후기", "침입")), true)
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      other.request("DELETE", base, Map.of("version", 0), true).statusCode()
    ).isEqualTo(403);
    assertThat(
      owner
        .request("PUT", base, edit(p, content("후기", "CSRF 누락")), false)
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      owner.request("DELETE", base, Map.of("version", 0), false).statusCode()
    ).isEqualTo(403);
    assertThat(
      owner
        .request("PUT", base, edit(p, content("추천", "종류 변경")), true)
        .statusCode()
    ).isEqualTo(400);
    var invalid = content("후기", " ");
    assertThat(
      owner.request("PUT", base, edit(p, invalid), true).statusCode()
    ).isEqualTo(400);
    invalid.put("title", "제목");
    invalid.put("body", "x".repeat(10001));
    assertThat(
      owner.request("PUT", base, edit(p, invalid), true).statusCode()
    ).isEqualTo(400);
    assertThat(
      owner
        .request(
          "PUT",
          base,
          Map.of("content", content("후기", "버전 누락")),
          true
        )
        .statusCode()
    ).isEqualTo(400);
    assertThat(
      owner.request("DELETE", base, Map.of("version", -1), true).statusCode()
    ).isEqualTo(400);
    assertThat(
      owner
        .request(
          "PUT",
          base + "/status",
          Map.of("version", 0, "status", "CLOSED"),
          true
        )
        .statusCode()
    ).isEqualTo(400);
    var recruit = content("동행", "모집 검증");
    recruit.put("capacity", 21);
    assertThat(
      owner.request("POST", "/community/posts", recruit, true).statusCode()
    ).isEqualTo(400);
    recruit.put("capacity", 2);
    recruit.put("recruitmentDate", null);
    assertThat(
      owner.request("POST", "/community/posts", recruit, true).statusCode()
    ).isEqualTo(400);
    assertThat(owner.call("GET", base, null)).isEqualTo(p);
  }

  @Test
  void deletionIsPrivateAndRestorePreservesCommentsAndAcceptedDm()
    throws Exception {
    var owner = new Browser().signup();
    var reader = new Browser().signup();
    var visitor = new Browser();
    var p = owner.call(
      "POST",
      "/community/posts",
      content("동행", "삭제와 복원 검증")
    );
    String id = p.get("id").toString(),
      base = "/community/posts/" + id;
    reader.call("POST", base + "/comments", Map.of("body", "보존할 댓글"));
    var dm = reader.call(
      "POST",
      "/messages",
      Map.of("email", owner.email, "body", "글에서 시작한 개인 대화")
    );
    String room = dm.get("id").toString();
    owner.call(
      "POST",
      "/messages/" + room + "/respond",
      Map.of("accept", true)
    );
    owner.call("DELETE", base, Map.of("version", 0));
    assertThat(
      visitor.request("GET", base, null, false).statusCode()
    ).isEqualTo(404);
    assertThat(
      visitor.request("GET", "/community/posts", null, false).body()
    ).doesNotContain(id);
    assertThat(
      reader
        .request(
          "POST",
          base + "/comments",
          Map.of("body", "삭제 뒤 댓글"),
          true
        )
        .statusCode()
    ).isEqualTo(404);
    assertThat(
      owner
        .request("PUT", base, edit(p, content("동행", "삭제 뒤 수정")), true)
        .statusCode()
    ).isEqualTo(404);
    assertThat(
      owner
        .request(
          "PUT",
          base + "/status",
          Map.of("version", 1, "status", "OPEN"),
          true
        )
        .statusCode()
    ).isEqualTo(404);
    assertThat(
      reader.request("GET", "/community/trash", null, false).body()
    ).doesNotContain(id);
    var trash = json
      .mapper()
      .readTree(owner.request("GET", "/community/trash", null, false).body());
    assertThat(trash.size()).isEqualTo(1);
    assertThat(trash.get(0).path("id").asText()).isEqualTo(id);
    assertThat(trash.get(0).has("body")).isFalse();
    assertThat(trash.get(0).has("comments")).isFalse();
    assertThat(
      reader
        .request("POST", base + "/restore", Map.of("version", 1), true)
        .statusCode()
    ).isEqualTo(403);
    assertThat(
      owner
        .request("POST", base + "/restore", Map.of("version", 0), true)
        .statusCode()
    ).isEqualTo(409);
    var restored = owner.call("POST", base + "/restore", Map.of("version", 1));
    assertThat(restored)
      .containsEntry("version", 2)
      .containsEntry("status", "CLOSED")
      .containsEntry("title", p.get("title"))
      .containsEntry("body", p.get("body"));
    assertThat(json.write(restored.get("comments")))
      .contains("보존할 댓글")
      .doesNotContain("삭제 뒤 댓글");
    assertThat(
      owner.request("GET", "/community/trash", null, false).body()
    ).doesNotContain(id);
    assertThat(
      visitor.request("GET", base, null, false).statusCode()
    ).isEqualTo(200);
    reader.call(
      "POST",
      "/messages/" + room,
      Map.of("body", "기존 개인 대화는 계속 유지")
    );
    assertThat(
      owner
        .request("POST", base + "/restore", Map.of("version", 2), true)
        .statusCode()
    ).isEqualTo(409);
    assertThat(
      owner.call(
        "PUT",
        base + "/status",
        Map.of("version", 2, "status", "OPEN")
      )
    )
      .containsEntry("version", 3)
      .containsEntry("status", "OPEN");
  }

  @Test
  void moderationCannotBeBypassedByEditingOrRestoring() throws Exception {
    var owner = new Browser().signup();
    var p = owner.call(
      "POST",
      "/community/posts",
      content("동행", "운영 숨김 검증")
    );
    String id = p.get("id").toString(),
      base = "/community/posts/" + id;
    // 운영진 처리의 상태만 서비스로 준비합니다. 작성자 우회 시도는 모두 실제 HTTP를 통과합니다.
    posts.hide(id, true);
    assertThat(owner.request("GET", base, null, false).statusCode()).isEqualTo(
      404
    );
    assertThat(
      owner
        .request("PUT", base, edit(p, content("동행", "숨김 우회")), true)
        .statusCode()
    ).isEqualTo(404);
    assertThat(
      owner
        .request(
          "PUT",
          base + "/status",
          Map.of("version", 1, "status", "OPEN"),
          true
        )
        .statusCode()
    ).isEqualTo(404);
    posts.hide(id, false);
    var visible = owner.call("GET", base, null);
    owner.call("DELETE", base, Map.of("version", visible.get("version")));
    posts.hide(id, true);
    var trash = json
      .mapper()
      .readTree(owner.request("GET", "/community/trash", null, false).body());
    assertThat(trash.get(0).path("hidden").asBoolean()).isTrue();
    assertThat(
      owner
        .request(
          "POST",
          base + "/restore",
          Map.of("version", trash.get(0).path("version").asInt()),
          true
        )
        .statusCode()
    ).isEqualTo(409);
    assertThat(
      new Browser().request("GET", base, null, false).statusCode()
    ).isEqualTo(404);
  }

  @Test
  void concurrentEditorsCannotOverwriteEachOther() throws Exception {
    var owner = new Browser().signup();
    var second = new Browser();
    second.call(
      "POST",
      "/auth/login",
      Map.of("email", owner.email, "password", "Community-Test-Only12!")
    );
    var p = owner.call(
      "POST",
      "/community/posts",
      content("추천", "동시 수정 전")
    );
    String base = "/community/posts/" + p.get("id");
    var barrier = new java.util.concurrent.CyclicBarrier(2);
    var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
      try {
        barrier.await();
        return owner.request(
          "PUT",
          base,
          edit(p, content("추천", "첫 번째 창의 수정")),
          true
        );
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    var another = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
      try {
        barrier.await();
        return second.request(
          "PUT",
          base,
          edit(p, content("추천", "두 번째 창의 수정")),
          true
        );
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    var a = first.get(20, java.util.concurrent.TimeUnit.SECONDS);
    var b = another.get(20, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(
      List.of(a.statusCode(), b.statusCode())
    ).containsExactlyInAnyOrder(200, 409);
    var current = owner.call("GET", base, null);
    assertThat(current.get("version")).isEqualTo(1);
    assertThat(current.get("title")).isEqualTo(
      json.read((a.statusCode() == 200 ? a : b).body(), Map.class).get("title")
    );
    assertThat(
      owner.request("DELETE", base, Map.of("version", 0), true).statusCode()
    ).isEqualTo(409);
    assertThat(owner.call("GET", base, null)).isEqualTo(current);
  }

  @Test
  void v8KeepsExistingPublishedPostsAndComments() throws Exception {
    String url =
      "jdbc:h2:mem:community-legacy-" +
      UUID.randomUUID() +
      ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    org.flywaydb.core.Flyway.configure()
      .dataSource(url, "sa", "")
      .target("7")
      .load()
      .migrate();
    try (
      var connection = java.sql.DriverManager.getConnection(url, "sa", "");
      var statement = connection.createStatement()
    ) {
      statement.executeUpdate(
        "INSERT INTO members(id,email,password_hash,nickname) VALUES('legacy-author','legacy@example.test','test-hash','이전 작성자')"
      );
      statement.executeUpdate(
        "INSERT INTO posts(id,author_id,category,region,title,body) VALUES('legacy-post','legacy-author','후기','서울','이전 글','이전 본문')"
      );
      statement.executeUpdate(
        "INSERT INTO comments(id,post_id,author_id,body) VALUES('legacy-comment','legacy-post','legacy-author','이전 댓글')"
      );
    }
    org.flywaydb.core.Flyway.configure()
      .dataSource(url, "sa", "")
      .load()
      .migrate();
    try (
      var connection = java.sql.DriverManager.getConnection(url, "sa", "");
      var statement = connection.createStatement();
      var row = statement.executeQuery(
        "SELECT p.title,p.body,p.version,p.deleted_at,p.updated_at,c.body AS comment_body FROM posts p JOIN comments c ON c.post_id=p.id WHERE p.id='legacy-post'"
      )
    ) {
      assertThat(row.next()).isTrue();
      assertThat(row.getString("title")).isEqualTo("이전 글");
      assertThat(row.getString("body")).isEqualTo("이전 본문");
      assertThat(row.getInt("version")).isZero();
      assertThat(row.getObject("deleted_at")).isNull();
      assertThat(row.getObject("updated_at")).isNull();
      assertThat(row.getString("comment_body")).isEqualTo("이전 댓글");
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
