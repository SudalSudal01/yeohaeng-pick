package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import kr.yeohaengpick.auth.*;
import kr.yeohaengpick.common.JsonCodec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

/** 가입부터 실제 메일 본문의 링크 소비·다른 기기 세션 종료까지 HTTP로 검사합니다.
 * 메일 전송기만 테스트 대역으로 바꾸므로 실주소로 메일을 보내거나 SMTP 비밀 설정을 읽지 않습니다. */
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "spring.datasource.url=jdbc:h2:mem:auth-review;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.sample-data=true",
    "app.kakao-rest-key=",
    "app.gemini-key=",
    "app.tour-api-key=",
    "app.mail-enabled=true",
    "app.mail-from=review@example.test",
    "app.admin-email=",
    "app.public-url=https://travel.example",
  }
)
@Import(AuthAccountFlowTest.TestMail.class)
class AuthAccountFlowTest extends HttpFlowDatabase {

  @TestConfiguration(proxyBeanMethods = false)
  static class TestMail {

    @Bean
    SessionProbe sessionProbe() {
      return new SessionProbe();
    }

    @Bean
    @Primary
    JavaMailSender capturedMail() {
      return mock(JavaMailSender.class);
    }
  }

  /** 새 로그인과 동시에 진행 중이던 이전 요청이 같은 세션 객체를 공유하지 않는지 확인하는 테스트 전용 경로입니다. */
  @org.springframework.web.bind.annotation.RestController
  static class SessionProbe {

    volatile CountDownLatch entered = new CountDownLatch(1);
    volatile CountDownLatch release = new CountDownLatch(1);

    @org.springframework.web.bind.annotation.GetMapping(
      "/api/review/session-probe"
    )
    Object inspect(jakarta.servlet.http.HttpServletRequest request)
      throws Exception {
      var original = request.getSession(false);
      entered.countDown();
      if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException(
        "probe timeout"
      );
      try {
        original.getAttribute("credentialVersion");
        return Map.of("oldSessionActive", true);
      } catch (IllegalStateException expired) {
        return Map.of("oldSessionActive", false);
      }
    }
  }

  @LocalServerPort
  int port;

  @Autowired
  JsonCodec json;

  @Autowired
  JavaMailSender mail;

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  AuthRateLimit budget;

  @Autowired
  SessionProbe sessionProbe;

  final List<SimpleMailMessage> sent = new CopyOnWriteArrayList<>();

  @BeforeEach
  void isolateMailAndBudget() {
    ((Map<?, ?>) ReflectionTestUtils.getField(budget, "attempts")).clear();
    reset(mail);
    sent.clear();
    doAnswer(invocation -> {
      sent.add(
        new SimpleMailMessage(
          invocation.getArgument(0, SimpleMailMessage.class)
        )
      );
      return null;
    })
      .when(mail)
      .send(any(SimpleMailMessage.class));
  }

  @Test
  void frameworkInputFailuresKeepTheirFourHundredStatusWithoutInternalDetails()
    throws Exception {
    var browser = new Browser().signup();
    for (var sample : List.of(
      new String[] { "GET", "/auth/not-a-real-endpoint", "404" },
      new String[] { "GET", "/catalog/tourism/search", "400" },
      new String[] {
        "GET",
        "/catalog/tourism/search?region=x&query=x&page=bad",
        "400",
      },
      new String[] { "DELETE", "/catalog/places", "405" }
    )) {
      var response = browser.request(
        sample[0],
        sample[1],
        null,
        "application/json"
      );
      assertThat(response.statusCode())
        .as(sample[1])
        .isEqualTo(Integer.parseInt(sample[2]));
      var body = json.mapper().readTree(response.body());
      assertThat(body.has("message")).isTrue();
      assertThat(body.has("reference")).isFalse();
      assertThat(response.body()).doesNotContain(
        "NoResourceFound",
        "java.",
        "SQLException"
      );
      if (response.statusCode() == 405) assertThat(
        response.headers().firstValue("Allow")
      ).isPresent();
    }
    assertThat(
      browser
        .request("POST", "/auth/login", "email=broken", "text/plain")
        .statusCode()
    ).isEqualTo(415);
  }

  @Test
  void profileNormalizesNicknameAndRejectsBlankMissingAndNullPreferences()
    throws Exception {
    var browser = new Browser().signup();
    var valid = Map.<String, Object>of(
      "nickname",
      "  여행 친구  ",
      "preferences",
      List.of("자연"),
      "dmScope",
      "ALL"
    );
    var saved = browser.call("PUT", "/auth/profile", valid);
    assertThat(saved.get("nickname")).isEqualTo("여행 친구");
    for (Object bad : List.of(
      Map.of("nickname", "   ", "preferences", List.of(), "dmScope", "ALL"),
      Map.of("preferences", List.of(), "dmScope", "ALL"),
      Map.of(
        "nickname",
        "정상 이름",
        "preferences",
        Arrays.asList((String) null),
        "dmScope",
        "ALL"
      ),
      Map.of(
        "nickname",
        "정상 이름",
        "preferences",
        List.of(" "),
        "dmScope",
        "ALL"
      )
    ))
      assertThat(
        browser.request("PUT", "/auth/profile", bad).statusCode()
      ).isEqualTo(400);
    assertThat(browser.call("GET", "/auth/me", null).get("nickname")).isEqualTo(
      "여행 친구"
    );
    assertThat(
      new Browser().request("GET", "/auth/withdrawal", null).statusCode()
    ).isEqualTo(401);
    // 유니코드 공백도 가입·프로필에서 똑같이 제거한 뒤 최소 길이를 검사합니다.
    var newcomer = new Browser();
    assertThat(
      newcomer
        .request(
          "POST",
          "/auth/register",
          Map.of(
            "email",
            newcomer.email,
            "password",
            Browser.PASSWORD,
            "nickname",
            "\u2003가\u2003"
          )
        )
        .statusCode()
    ).isEqualTo(400);
  }

  @Test
  void decimalAndExplicitNullIntegerInputsAreNotSilentlyConverted()
    throws Exception {
    var browser = new Browser().signup();
    var conditions = new LinkedHashMap<String, Object>(
      Map.of(
        "title",
        "정수 입력 검증",
        "regions",
        List.of("서울"),
        "startDate",
        "2026-11-20",
        "endDate",
        "2026-11-20",
        "origin",
        "서울역",
        "partySize",
        2,
        "budget",
        300000,
        "transport",
        "TRANSIT",
        "preferences",
        List.of("자연")
      )
    );
    for (String field : List.of("partySize", "budget"))
      for (Object value : Arrays.asList(2.9, null)) {
        var bad = new LinkedHashMap<>(conditions);
        bad.put(field, value);
        assertThat(
          browser.request("POST", "/recommendations", bad).statusCode()
        )
          .as(field + "=" + value)
          .isEqualTo(400);
      }
    var result = browser.request("POST", "/recommendations", conditions);
    assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
  }

  @Test
  void verificationUsesOwnEmailAndSingleUseHashedToken() throws Exception {
    var browser = new Browser().signup();
    var reply = browser.call(
      "POST",
      "/auth/verify/send",
      Map.of("email", "someone-else@example.test")
    );
    assertThat(reply).containsOnlyKeys("message");
    assertThat(sent).hasSize(1);
    assertThat(sent.getFirst().getTo()).containsExactly(browser.email);
    String token = token(sent.getFirst());
    String hash = hash(token);
    assertThat(
      jdbc.queryForObject(
        "SELECT COUNT(*) FROM email_tokens WHERE token_hash=?",
        Integer.class,
        token
      )
    ).isZero();
    assertThat(
      jdbc.queryForObject(
        "SELECT COUNT(*) FROM email_tokens WHERE token_hash=?",
        Integer.class,
        hash
      )
    ).isEqualTo(1);
    var visitor = new Browser();
    assertThat(
      visitor
        .request(
          "POST",
          "/auth/reset",
          Map.of("token", token, "password", "New-only-Password12!")
        )
        .statusCode()
    ).isEqualTo(400);
    visitor.call("POST", "/auth/verify", Map.of("token", token));
    assertThat(browser.call("GET", "/auth/me", null).get("verified")).isEqualTo(
      true
    );
    assertThat(
      visitor
        .request("POST", "/auth/verify", Map.of("token", token))
        .statusCode()
    ).isEqualTo(400);
  }

  @Test
  void resetFlowHidesUnknownAccountAndInvalidatesAllOldSessions()
    throws Exception {
    var browser = new Browser().signup();
    var otherDevice = new Browser();
    otherDevice.login(browser.email, Browser.PASSWORD);
    var visitor = new Browser().signup();
    var unknown = visitor.call(
      "POST",
      "/auth/reset/send",
      Map.of("email", UUID.randomUUID() + "@example.test")
    );
    assertThat(sent).isEmpty();
    var known = visitor.call(
      "POST",
      "/auth/reset/send",
      Map.of("email", browser.email)
    );
    assertThat(known).isEqualTo(unknown);
    String token = token(sent.getFirst());
    assertThat(
      visitor
        .request(
          "POST",
          "/auth/reset",
          Map.of("token", token, "password", "short")
        )
        .statusCode()
    ).isEqualTo(400);
    assertThat(browser.request("GET", "/auth/me", null).statusCode()).isEqualTo(
      200
    );
    visitor.call(
      "POST",
      "/auth/reset",
      Map.of("token", token, "password", "Changed-only-Password12!")
    );
    assertThat(visitor.call("GET", "/auth/me", null)).containsEntry(
      "id",
      visitor.id
    );
    assertThat(browser.request("GET", "/auth/me", null).statusCode()).isEqualTo(
      401
    );
    assertThat(
      otherDevice.request("GET", "/auth/me", null).statusCode()
    ).isEqualTo(401);
    assertThat(
      visitor
        .request(
          "POST",
          "/auth/reset",
          Map.of("token", token, "password", "Changed-again-Password12!")
        )
        .statusCode()
    ).isEqualTo(400);
    assertThat(
      visitor
        .request(
          "POST",
          "/auth/login",
          Map.of("email", browser.email, "password", Browser.PASSWORD)
        )
        .statusCode()
    ).isEqualTo(401);
    visitor.login(browser.email, "Changed-only-Password12!");
  }

  @Test
  void twoDifferentResetLinksRaceWithoutDeadlockOrDoublePasswordChange()
    throws Exception {
    var member = new Browser().signup();
    var sender = new Browser();
    sender.call("POST", "/auth/reset/send", Map.of("email", member.email));
    sender.call("POST", "/auth/reset/send", Map.of("email", member.email));
    String first = token(sent.get(0)),
      second = token(sent.get(1));
    var gate = new CyclicBarrier(2);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var left = pool.submit(() -> {
        var browser = new Browser();
        browser.csrf();
        gate.await(5, TimeUnit.SECONDS);
        return browser.request(
          "POST",
          "/auth/reset",
          Map.of("token", first, "password", "First-new-Password12!")
        );
      });
      var right = pool.submit(() -> {
        var browser = new Browser();
        browser.csrf();
        gate.await(5, TimeUnit.SECONDS);
        return browser.request(
          "POST",
          "/auth/reset",
          Map.of("token", second, "password", "Second-new-Password12!")
        );
      });
      assertThat(
        List.of(
          left.get(15, TimeUnit.SECONDS).statusCode(),
          right.get(15, TimeUnit.SECONDS).statusCode()
        )
      ).containsExactlyInAnyOrder(200, 400);
    }
    assertThat(
      jdbc.queryForObject(
        "SELECT COUNT(*) FROM email_tokens WHERE member_id=? AND purpose='RESET'",
        Integer.class,
        member.id
      )
    ).isZero();
  }

  @Test
  void expiredAndFailedDeliveryTokensCannotBeConsumed() throws Exception {
    var member = new Browser().signup();
    var visitor = new Browser();
    visitor.call("POST", "/auth/reset/send", Map.of("email", member.email));
    String token = token(sent.getFirst());
    jdbc.update(
      "UPDATE email_tokens SET expires_at=? WHERE token_hash=?",
      java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusMinutes(1)),
      hash(token)
    );
    assertThat(
      visitor
        .request(
          "POST",
          "/auth/reset",
          Map.of("token", token, "password", "Expired-new-Password12!")
        )
        .statusCode()
    ).isEqualTo(400);
    doThrow(
      new org.springframework.mail.MailSendException("fake transport failure")
    )
      .when(mail)
      .send(any(SimpleMailMessage.class));
    assertThat(
      visitor
        .request("POST", "/auth/reset/send", Map.of("email", member.email))
        .statusCode()
    ).isEqualTo(503);
    assertThat(
      jdbc.queryForObject(
        "SELECT COUNT(*) FROM email_tokens WHERE member_id=?",
        Integer.class,
        member.id
      )
    ).isEqualTo(1);
  }

  @Test
  void expectedMemberContextCannotWriteAnotherAccountAndIsNotAuthentication()
    throws Exception {
    var first = new Browser().signup();
    var current = new Browser().signup();
    var update = Map.of(
      "nickname",
      "다른 계정의 옛 초안",
      "preferences",
      List.of("자연"),
      "dmScope",
      "ALL"
    );
    var blocked = current.client.send(
      HttpRequest.newBuilder(current.uri("/auth/profile"))
        .header("Content-Type", "application/json")
        .header("X-XSRF-TOKEN", current.csrf())
        .header("X-Travel-Member", first.id)
        .PUT(HttpRequest.BodyPublishers.ofString(json.write(update)))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    assertThat(blocked.statusCode()).isEqualTo(409);
    assertThat(
      blocked.headers().firstValue("X-Travel-Account-Changed")
    ).contains("true");
    assertThat(blocked.body())
      .contains("ACCOUNT_CHANGED")
      .doesNotContain(first.id, current.id, first.email, current.email);
    assertThat(current.call("GET", "/auth/me", null))
      .containsEntry("id", current.id)
      .containsEntry("nickname", "여행 검증");
    var accepted = current.client.send(
      HttpRequest.newBuilder(current.uri("/auth/profile"))
        .header("Content-Type", "application/json")
        .header("X-XSRF-TOKEN", current.csrf())
        .header("X-Travel-Member", current.id)
        .PUT(HttpRequest.BodyPublishers.ofString(json.write(update)))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    assertThat(accepted.statusCode()).isEqualTo(200);
    var visitor = new Browser();
    assertThat(
      visitor.client
        .send(
          HttpRequest.newBuilder(visitor.uri("/trips"))
            .header("X-Travel-Member", current.id)
            .GET()
            .build(),
          HttpResponse.BodyHandlers.ofString()
        )
        .statusCode()
    ).isEqualTo(401);
  }

  @Test
  void newLoginDoesNotReuseTheSessionObjectOfAnAlreadyRunningRequest()
    throws Exception {
    var first = new Browser().signup();
    var next = new Browser().signup();
    sessionProbe.entered = new CountDownLatch(1);
    sessionProbe.release = new CountDownLatch(1);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var earlier = pool.submit(() ->
        first.call("GET", "/review/session-probe", null)
      );
      try {
        assertThat(sessionProbe.entered.await(5, TimeUnit.SECONDS)).isTrue();
        first.login(next.email, Browser.PASSWORD);
      } finally {
        sessionProbe.release.countDown();
      }
      assertThat(earlier.get(10, TimeUnit.SECONDS)).containsEntry(
        "oldSessionActive",
        false
      );
      assertThat(first.call("GET", "/auth/me", null)).containsEntry(
        "id",
        next.id
      );
    }
  }

  private String token(SimpleMailMessage message) {
    var matcher = Pattern.compile("token=([0-9a-f]{64})").matcher(
      message.getText()
    );
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }

  private String hash(String token) throws Exception {
    return HexFormat.of().formatHex(
      java.security.MessageDigest.getInstance("SHA-256").digest(
        token.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      )
    );
  }

  private class Browser {

    static final String PASSWORD = "Test-only-Password12!";
    final String email =
      "account-review-" + UUID.randomUUID() + "@example.test";
    final HttpClient client = HttpClient.newBuilder()
      .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
      .connectTimeout(java.time.Duration.ofSeconds(5))
      .build();
    String id;

    String csrf() throws Exception {
      return (String) map(
        client.send(
          HttpRequest.newBuilder(uri("/auth/csrf")).GET().build(),
          HttpResponse.BodyHandlers.ofString()
        )
      ).get("token");
    }

    URI uri(String path) {
      return URI.create("http://127.0.0.1:" + port + "/api" + path);
    }

    Browser signup() throws Exception {
      var registered = call(
        "POST",
        "/auth/register",
        Map.of("email", email, "password", PASSWORD, "nickname", "여행 검증")
      );
      id = (String) registered.get("id");
      login(email, PASSWORD);
      return this;
    }

    void login(String address, String password) throws Exception {
      call(
        "POST",
        "/auth/login",
        Map.of("email", address, "password", password)
      );
    }

    HttpResponse<String> request(String method, String path, Object body)
      throws Exception {
      return request(method, path, body, "application/json");
    }

    HttpResponse<String> request(
      String method,
      String path,
      Object body,
      String contentType
    ) throws Exception {
      var builder = HttpRequest.newBuilder(uri(path))
        .timeout(java.time.Duration.ofSeconds(15))
        .header("Content-Type", contentType);
      if (!method.equals("GET")) builder.header("X-XSRF-TOKEN", csrf());
      return client.send(
        builder
          .method(
            method,
            body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(
                  contentType.equals("application/json")
                    ? json.write(body)
                    : body.toString()
                )
          )
          .build(),
        HttpResponse.BodyHandlers.ofString()
      );
    }

    Map<String, Object> call(String method, String path, Object body)
      throws Exception {
      var response = request(method, path, body);
      assertThat(response.statusCode())
        .as(method + " " + path + ": " + response.body())
        .isEqualTo(200);
      return map(response);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> map(HttpResponse<String> response) {
      return json.read(response.body(), Map.class);
    }
  }
}
