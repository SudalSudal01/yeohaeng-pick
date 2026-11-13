package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import kr.yeohaengpick.auth.AuthRateLimit;
import kr.yeohaengpick.catalog.CatalogService;
import kr.yeohaengpick.common.*;
import kr.yeohaengpick.trip.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** 실제 HTTP·세션·CSRF·DB로 저장 성공 후 재시도와 동시 생성의 원자성을 검증합니다. 외부 API를 호출하지 않습니다. */
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
class IdempotencyFlowTest extends HttpFlowDatabase {

  @LocalServerPort
  int port;

  @Autowired
  JsonCodec json;

  @Autowired
  JdbcTemplate db;

  @Autowired
  AuthRateLimit authenticationBudget;

  @Autowired
  CatalogService catalog;

  @Autowired
  TripMutationReceipts receipts;

  @BeforeEach
  void resetOnlyAuthenticationTestBudget() {
    (
      (Map<?, ?>) ReflectionTestUtils.getField(authenticationBudget, "attempts")
    ).clear();
  }

  @Test
  void travelReplayReturnsCurrentTripWithoutRepeatingSupplierOrTrustingClientMetadata()
    throws Exception {
    var owner = new Browser().signup();
    String key = UUID.randomUUID().toString();
    var request = owner.creation(key.toUpperCase(Locale.ROOT));
    var first = owner.call("POST", "/trips", request);
    String trip = first.get("id").toString();
    owner.call(
      "POST",
      "/trips/" + trip + "/tasks",
      Map.of("content", "저장 후 추가한 준비물")
    );
    var replayBody = copy(request);
    replayBody.put("requestId", key);
    var stops = (List<Map<String, Object>>) replayBody.get("items");
    Collections.reverse(stops);
    for (var stop : stops) {
      stop.put("id", UUID.randomUUID().toString());
      stop.put("fixed", true);
      stop.put("bookingStatus", "PARTNER_CONFIRMED");
      ((Map<String, Object>) stop.get("place")).put(
        "name",
        "서버에서 무시할 임시 이름"
      );
    }
    Object oldSample = ReflectionTestUtils.getField(catalog, "sample");
    try {
      // 새 생성이라면 503이 나는 상태입니다. 첫 저장의 ACK 유실 재시도는 외부 후보를 다시 조회하지 않습니다.
      ReflectionTestUtils.setField(catalog, "sample", false);
      var replayed = owner.call("POST", "/trips", replayBody);
      assertThat(replayed.get("id")).isEqualTo(trip);
      assertThat(
        json.mapper().valueToTree(replayed.get("tasks")).toString()
      ).contains("저장 후 추가한 준비물");
      assertThat(replayed).doesNotContainKeys(
        "requestId",
        "payloadHash",
        "receipt"
      );
    } finally {
      ReflectionTestUtils.setField(catalog, "sample", oldSample);
    }
    assertThat(owner.array("GET", "/trips", null)).hasSize(1);
    assertThat(
      count("SELECT COUNT(*) FROM trip_mutations WHERE member_id=?", owner.id)
    ).isEqualTo(1);
    assertThat(
      db.queryForObject(
        "SELECT request_id FROM trip_mutations WHERE member_id=?",
        String.class,
        owner.id
      )
    ).isEqualTo(key);
  }

  @Test
  void taskExpenseReplayKeepsOneRowAndReturnsLatestListsWithScopedKeys()
    throws Exception {
    var owner = new Browser().signup();
    String trip = owner.trip().get("id").toString(),
      base = "/trips/" + trip;
    String key = UUID.randomUUID().toString();
    var task = Map.of("content", "  우산  ", "requestId", key);
    var expense = Map.of(
      "label",
      "  택시  ",
      "amount",
      12000,
      "requestId",
      key
    );
    owner.array("POST", base + "/tasks", task);
    owner.array("POST", base + "/expenses", expense);
    var companion = new Browser().signup();
    join(owner, companion, trip);
    companion.array(
      "POST",
      base + "/tasks",
      Map.of("content", "다른 동행의 준비물", "requestId", key)
    );
    companion.array(
      "POST",
      base + "/expenses",
      Map.of("label", "버스", "amount", 3000, "requestId", key)
    );
    var tasks = owner.array(
      "POST",
      base + "/tasks",
      Map.of("content", "우산", "requestId", key.toUpperCase(Locale.ROOT))
    );
    var expenses = owner.array(
      "POST",
      base + "/expenses",
      Map.of("label", "택시", "amount", 12000, "requestId", key)
    );
    assertThat(tasks)
      .filteredOn(row -> row.get("content").equals("우산"))
      .hasSize(1);
    assertThat(tasks)
      .extracting(row -> row.get("content"))
      .contains("다른 동행의 준비물");
    assertThat(expenses).hasSize(2);
    assertThat(
      expenses
        .stream()
        .mapToInt(row -> ((Number) row.get("amount")).intValue())
        .sum()
    ).isEqualTo(15000);
    assertThat(
      count("SELECT COUNT(*) FROM trip_mutations WHERE member_id=?", owner.id)
    ).isEqualTo(2);
    assertThat(
      count(
        "SELECT COUNT(*) FROM trip_mutations WHERE member_id=?",
        companion.id
      )
    ).isEqualTo(2);
  }

  @Test
  void changedBodyOrTripCannotReuseTheSameSuccessfulKeyAndLegacyRequestsStillWork()
    throws Exception {
    var owner = new Browser().signup();
    String key = UUID.randomUUID().toString();
    var create = owner.creation(key);
    var created = owner.call("POST", "/trips", create);
    String trip = created.get("id").toString(),
      base = "/trips/" + trip;
    var changedCreate = copy(create);
    ((Map<String, Object>) changedCreate.get("conditions")).put(
      "title",
      "다른 여행 제목"
    );
    assertThat(
      owner.send("POST", "/trips", changedCreate).statusCode()
    ).isEqualTo(409);
    owner.array(
      "POST",
      base + "/tasks",
      Map.of("content", "우산", "requestId", key)
    );
    owner.array(
      "POST",
      base + "/expenses",
      Map.of("label", "점심", "amount", 10000, "requestId", key)
    );
    assertThat(
      owner
        .send(
          "POST",
          base + "/tasks",
          Map.of("content", "물병", "requestId", key)
        )
        .statusCode()
    ).isEqualTo(409);
    assertThat(
      owner
        .send(
          "POST",
          base + "/expenses",
          Map.of("label", "점심", "amount", 20000, "requestId", key)
        )
        .statusCode()
    ).isEqualTo(409);
    String other = owner.trip().get("id").toString();
    assertThat(
      owner
        .send(
          "POST",
          "/trips/" + other + "/tasks",
          Map.of("content", "우산", "requestId", key)
        )
        .statusCode()
    ).isEqualTo(409);
    assertThat(
      owner
        .send(
          "POST",
          "/trips/" + other + "/expenses",
          Map.of("label", "점심", "amount", 10000, "requestId", key)
        )
        .statusCode()
    ).isEqualTo(409);
    owner.array(
      "POST",
      base + "/tasks",
      Map.of("content", "키 없는 예전 준비물")
    );
    owner.array(
      "POST",
      base + "/tasks",
      Map.of("content", "키 없는 예전 준비물")
    );
    owner.array(
      "POST",
      base + "/expenses",
      Map.of("label", "키 없는 예전 경비", "amount", 100)
    );
    assertThat(
      count("SELECT COUNT(*) FROM trip_mutations WHERE member_id=?", owner.id)
    ).isEqualTo(3);
    assertThat(
      count(
        "SELECT COUNT(*) FROM trip_tasks WHERE trip_id=? AND content=?",
        trip,
        "키 없는 예전 준비물"
      )
    ).isEqualTo(2);
    String task = db.queryForObject(
      "SELECT target_id FROM trip_mutations WHERE member_id=? AND operation='TASK_ADD'",
      String.class,
      owner.id
    );
    assertThat(
      owner
        .send(
          "POST",
          base + "/tasks",
          Map.of(
            "taskId",
            task,
            "done",
            true,
            "requestId",
            UUID.randomUUID().toString()
          )
        )
        .statusCode()
    ).isEqualTo(400);
    owner.array("POST", base + "/tasks", Map.of("taskId", task, "done", true));
    for (String malformed : List.of(
      "",
      "not-a-uuid",
      "00000000-0000-0000-0000-00000000000z"
    ))
      assertThat(
        owner
          .send(
            "POST",
            base + "/tasks",
            Map.of("content", "잘못된 키", "requestId", malformed)
          )
          .statusCode()
      ).isEqualTo(400);
  }

  @Test
  void simultaneousRequestsCreateOnlyOneTravelTaskAndExpense()
    throws Exception {
    var owner = new Browser().signup();
    var request = owner.creation(UUID.randomUUID().toString());
    var created = concurrent(owner, "/trips", request, 6);
    Set<String> ids = new HashSet<>();
    for (var result : created) {
      assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
      ids.add(map(result).get("id").toString());
    }
    assertThat(ids).hasSize(1);
    String trip = ids.iterator().next(),
      base = "/trips/" + trip;
    for (var result : concurrent(
      owner,
      base + "/tasks",
      Map.of("content", "동시 우산", "requestId", UUID.randomUUID().toString()),
      6
    ))
      assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
    for (var result : concurrent(
      owner,
      base + "/expenses",
      Map.of(
        "label",
        "동시 식사",
        "amount",
        15000,
        "requestId",
        UUID.randomUUID().toString()
      ),
      6
    ))
      assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
    assertThat(
      count(
        "SELECT COUNT(*) FROM trip_tasks WHERE trip_id=? AND content=?",
        trip,
        "동시 우산"
      )
    ).isEqualTo(1);
    assertThat(
      count("SELECT COUNT(*) FROM expenses WHERE trip_id=?", trip)
    ).isEqualTo(1);
    assertThat(
      count("SELECT COUNT(*) FROM trip_mutations WHERE member_id=?", owner.id)
    ).isEqualTo(3);
  }

  @Test
  void deletedTargetsAndTripsAreTombstonesRatherThanNewCreationRequests()
    throws Exception {
    var owner = new Browser().signup();
    var create = owner.creation(UUID.randomUUID().toString());
    String trip = owner.call("POST", "/trips", create).get("id").toString(),
      base = "/trips/" + trip;
    var task = Map.of(
      "content",
      "지울 준비물",
      "requestId",
      UUID.randomUUID().toString()
    );
    var expense = Map.of(
      "label",
      "지울 경비",
      "amount",
      9000,
      "requestId",
      UUID.randomUUID().toString()
    );
    owner.array("POST", base + "/tasks", task);
    owner.array("POST", base + "/expenses", expense);
    db.update(
      "DELETE FROM trip_tasks WHERE trip_id=? AND content=?",
      trip,
      "지울 준비물"
    );
    db.update("DELETE FROM expenses WHERE trip_id=?", trip);
    assertThat(
      owner.send("POST", base + "/tasks", task).statusCode()
    ).isEqualTo(409);
    assertThat(
      owner.send("POST", base + "/expenses", expense).statusCode()
    ).isEqualTo(409);
    assertThat(
      count(
        "SELECT COUNT(*) FROM trip_tasks WHERE trip_id=? AND content=?",
        trip,
        "지울 준비물"
      )
    ).isZero();
    assertThat(
      count("SELECT COUNT(*) FROM expenses WHERE trip_id=?", trip)
    ).isZero();
    owner.call("DELETE", base, null);
    assertThat(owner.send("POST", "/trips", create).statusCode()).isEqualTo(
      409
    );
    assertThat(
      owner.send("POST", base + "/tasks", task).statusCode()
    ).isEqualTo(409);
    assertThat(
      owner.send("POST", base + "/expenses", expense).statusCode()
    ).isEqualTo(409);
    assertThat(owner.array("GET", "/trips", null)).isEmpty();
    assertThat(
      count(
        "SELECT COUNT(*) FROM trip_mutations WHERE member_id=? AND trip_id IS NULL",
        owner.id
      )
    ).isEqualTo(3);
  }

  @Test
  void revokedMembershipCannotBeRestoredByReplayingSuccessfulRequests()
    throws Exception {
    var owner = new Browser().signup();
    var next = new Browser().signup();
    var create = owner.creation(UUID.randomUUID().toString());
    String trip = owner.call("POST", "/trips", create).get("id").toString(),
      base = "/trips/" + trip;
    var task = Map.of(
      "content",
      "이전 동행 준비물",
      "requestId",
      UUID.randomUUID().toString()
    );
    var expense = Map.of(
      "label",
      "이전 동행 경비",
      "amount",
      3000,
      "requestId",
      UUID.randomUUID().toString()
    );
    owner.array("POST", base + "/tasks", task);
    owner.array("POST", base + "/expenses", expense);
    join(owner, next, trip);
    int version = (
      (Number) owner.call("GET", base, null).get("version")
    ).intValue();
    owner.call(
      "POST",
      base + "/ownership/transfer",
      Map.of("memberId", next.id, "version", version)
    );
    version = ((Number) next.call("GET", base, null).get("version")).intValue();
    next.call(
      "POST",
      base + "/members/" + owner.id + "/remove",
      Map.of("version", version)
    );
    assertThat(owner.send("POST", "/trips", create).statusCode()).isEqualTo(
      403
    );
    assertThat(
      owner.send("POST", base + "/tasks", task).statusCode()
    ).isEqualTo(403);
    assertThat(
      owner.send("POST", base + "/expenses", expense).statusCode()
    ).isEqualTo(403);
    assertThat(
      count(
        "SELECT COUNT(*) FROM trip_members WHERE trip_id=? AND member_id=?",
        trip,
        owner.id
      )
    ).isZero();
    assertThat(
      count(
        "SELECT COUNT(*) FROM trip_tasks WHERE trip_id=? AND content=?",
        trip,
        "이전 동행 준비물"
      )
    ).isEqualTo(1);
  }

  @Test
  void lateFailureRollsBackCreationAndReceiptAndSameKeyCanThenSucceed()
    throws Exception {
    var owner = new Browser().signup();
    var request = owner.creation(UUID.randomUUID().toString());
    var real = (TripMutationMapper) ReflectionTestUtils.getField(
      receipts,
      "mutations"
    );
    var failing = (TripMutationMapper) java.lang.reflect.Proxy.newProxyInstance(
      TripMutationMapper.class.getClassLoader(),
      new Class<?>[] { TripMutationMapper.class },
      (proxy, method, args) -> {
        Object result;
        try {
          result = method.invoke(real, args);
        } catch (InvocationTargetException failure) {
          throw failure.getCause();
        }
        if (
          method.getName().equals("record") && owner.id.equals(args[0])
        ) throw new DomainException(409, "합성 트랜잭션 종료 실패");
        return result;
      }
    );
    try {
      ReflectionTestUtils.setField(receipts, "mutations", failing);
      assertThat(owner.send("POST", "/trips", request).statusCode()).isEqualTo(
        409
      );
    } finally {
      ReflectionTestUtils.setField(receipts, "mutations", real);
    }
    assertThat(owner.array("GET", "/trips", null)).isEmpty();
    assertThat(
      count("SELECT COUNT(*) FROM trip_mutations WHERE member_id=?", owner.id)
    ).isZero();
    owner.call("POST", "/trips", request);
    assertThat(owner.array("GET", "/trips", null)).hasSize(1);
    assertThat(
      count("SELECT COUNT(*) FROM trip_mutations WHERE member_id=?", owner.id)
    ).isEqualTo(1);
  }

  @Test
  void withdrawalErasesOnlyTheMembersPrivateReceiptsAndKeepsSharedRecords()
    throws Exception {
    var owner = new Browser().signup();
    var companion = new Browser().signup();
    String trip = owner.trip().get("id").toString(),
      base = "/trips/" + trip;
    join(owner, companion, trip);
    companion.array(
      "POST",
      base + "/tasks",
      Map.of(
        "content",
        "탈퇴 전 준비물",
        "requestId",
        UUID.randomUUID().toString()
      )
    );
    companion.array(
      "POST",
      base + "/expenses",
      Map.of(
        "label",
        "탈퇴 전 경비",
        "amount",
        7000,
        "requestId",
        UUID.randomUUID().toString()
      )
    );
    owner.array(
      "POST",
      base + "/expenses",
      Map.of(
        "label",
        "소유자 경비",
        "amount",
        1000,
        "requestId",
        UUID.randomUUID().toString()
      )
    );
    assertThat(
      count(
        "SELECT COUNT(*) FROM trip_mutations WHERE member_id=?",
        companion.id
      )
    ).isEqualTo(2);
    companion.call(
      "DELETE",
      "/auth/account",
      Map.of("password", Browser.PASSWORD, "confirmed", true)
    );
    assertThat(
      count(
        "SELECT COUNT(*) FROM trip_mutations WHERE member_id=?",
        companion.id
      )
    ).isZero();
    assertThat(
      count("SELECT COUNT(*) FROM trip_mutations WHERE member_id=?", owner.id)
    ).isEqualTo(1);
    assertThat(
      count(
        "SELECT COUNT(*) FROM trip_tasks WHERE trip_id=? AND content=?",
        trip,
        "탈퇴 전 준비물"
      )
    ).isEqualTo(1);
    assertThat(
      count("SELECT COUNT(*) FROM expenses WHERE trip_id=?", trip)
    ).isEqualTo(2);
  }

  private List<HttpResponse<String>> concurrent(
    Browser browser,
    String path,
    Object body,
    int size
  ) throws Exception {
    String token = browser.token();
    var gate = new CyclicBarrier(size);
    try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<HttpResponse<String>>> responses = new ArrayList<>();
      for (int index = 0; index < size; index++) responses.add(
        callers.submit(() -> {
          gate.await(10, TimeUnit.SECONDS);
          return browser.withToken("POST", path, body, token);
        })
      );
      List<HttpResponse<String>> values = new ArrayList<>();
      for (var response : responses)
        values.add(response.get(30, TimeUnit.SECONDS));
      return values;
    }
  }

  private void join(Browser owner, Browser companion, String trip)
    throws Exception {
    owner.call(
      "POST",
      "/invitations",
      Map.of("tripId", trip, "email", companion.email)
    );
    var invitations = companion.array("GET", "/invitations", null);
    String invitation = invitations
      .stream()
      .filter(row -> row.get("tripId").equals(trip))
      .findFirst()
      .orElseThrow()
      .get("id")
      .toString();
    companion.call(
      "POST",
      "/invitations/" + invitation + "/respond",
      Map.of("accept", true)
    );
  }

  private int count(String sql, Object... parameters) {
    return db.queryForObject(sql, Integer.class, parameters);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> copy(Map<String, Object> source) {
    return json.read(json.write(source), Map.class);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(HttpResponse<String> response) {
    return json.read(response.body(), Map.class);
  }

  class Browser {

    static final String PASSWORD = "Test-only-Password12!";
    final String email = UUID.randomUUID() + "@example.test";
    final HttpClient client = HttpClient.newBuilder()
      .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
      .build();
    String id;

    HttpResponse<String> send(String method, String path, Object body)
      throws Exception {
      return withToken(
        method,
        path,
        body,
        method.equals("GET") ? null : token()
      );
    }

    String token() throws Exception {
      return map(withToken("GET", "/auth/csrf", null, null))
        .get("token")
        .toString();
    }

    HttpResponse<String> withToken(
      String method,
      String path,
      Object body,
      String token
    ) throws Exception {
      var request = HttpRequest.newBuilder(
        URI.create("http://127.0.0.1:" + port + "/api" + path)
      ).header("Content-Type", "application/json");
      if (token != null) request.header("X-XSRF-TOKEN", token);
      return client.send(
        request
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
      var response = send(method, path, body);
      assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
      return response.body().startsWith("{") ? map(response) : Map.of();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> array(String method, String path, Object body)
      throws Exception {
      var response = send(method, path, body);
      assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
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
          PASSWORD,
          "nickname",
          "재시도 검증자"
        )
      );
      call("POST", "/auth/login", Map.of("email", email, "password", PASSWORD));
      id = call("GET", "/auth/me", null).get("id").toString();
      return this;
    }

    Map<String, Object> creation(String key) throws Exception {
      var conditions = new LinkedHashMap<String, Object>();
      conditions.put("title", "중복 저장 검증 여행");
      conditions.put("regions", List.of("서울"));
      conditions.put("startDate", "2026-11-14");
      conditions.put("endDate", "2026-11-14");
      conditions.put("origin", "합성 출발지");
      conditions.put("partySize", 2);
      conditions.put("budget", 300000);
      conditions.put("transport", "TRANSIT");
      conditions.put("preferences", List.of("전시", "맛집"));
      conditions.put("lodging", "");
      var recommended = send("POST", "/recommendations", conditions);
      assertThat(recommended.statusCode())
        .as(recommended.body())
        .isEqualTo(200);
      var request = new LinkedHashMap<String, Object>();
      request.put("conditions", conditions);
      request.put(
        "items",
        json.read(
          json
            .mapper()
            .readTree(recommended.body())
            .get(1)
            .path("items")
            .toString(),
          List.class
        )
      );
      if (key != null) request.put("requestId", key);
      return request;
    }

    Map<String, Object> trip() throws Exception {
      return call("POST", "/trips", creation(null));
    }
  }
}
