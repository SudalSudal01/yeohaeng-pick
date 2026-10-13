package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import kr.yeohaengpick.auth.AuthRateLimit;
import kr.yeohaengpick.catalog.*;
import kr.yeohaengpick.common.JsonCodec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

/** 실제 웹 API·세션·저장 재검사를 합성 장소 공급자로 검증합니다. 실제 외부 키나 실사용 DB는 쓰지 않습니다. */
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
class RegionCatalogFlowTest extends HttpFlowDatabase {

  @LocalServerPort
  int port;

  @Autowired
  CatalogService catalog;

  @Autowired
  JsonCodec json;

  @Autowired
  AuthRateLimit authRateLimit;

  HttpServer supplier;
  Object originalClient;
  String originalKey;
  final AtomicInteger calls = new AtomicInteger();
  final HttpClient browser = HttpClient.newBuilder()
    .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
    .build();

  @BeforeEach
  void mockSupplier() throws Exception {
    (
      (Map<?, ?>) ReflectionTestUtils.getField(authRateLimit, "attempts")
    ).clear();
    originalClient = ReflectionTestUtils.getField(catalog, "client");
    originalKey = (String) ReflectionTestUtils.getField(catalog, "kakaoKey");
    supplier = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    supplier.createContext("/", exchange -> {
      calls.incrementAndGet();
      String query = URLDecoder.decode(
        exchange.getRequestURI().getRawQuery(),
        StandardCharsets.UTF_8
      )
        .split("query=", 2)[1]
        .split("&size=", 2)[0];
      String region = exchange.getRequestURI().getRawQuery().contains("rect=")
        ? "광주"
        : TravelRegions.names(false)
            .stream()
            .filter(name ->
              query.startsWith(TravelRegions.searchPrefix(name) + " ")
            )
            .findFirst()
            .orElseThrow();
      String kind = region.equals("광주")
        ? query.split("&rect=", 2)[0]
        : query.substring(TravelRegions.searchPrefix(region).length()).trim();
      var definition = TravelRegions.require(region);
      String address =
        definition.provinceNames().getLast() +
        " " +
        (definition.city() == null
          ? region.equals("광주")
            ? "동구"
            : "중구"
          : definition.city()) +
        " 검증로 1";
      String group = switch (kind) {
        case "음식점" -> "FD6";
        case "카페" -> "CE7";
        case "숙소" -> "AD5";
        default -> "AT4";
      };
      String path = switch (kind) {
        case "공원" -> "여행 > 공원";
        case "미술관", "박물관" -> "문화 > " + kind;
        case "체험학습장" -> "여행 > 체험학습장";
        default -> "여행 > 관광";
      };
      var places = new ArrayList<Map<String, Object>>();
      for (int index = 0; index < 4; index++) places.add(
        Map.of(
          "id",
          region + "-" + kind + "-" + index,
          "place_name",
          region + " 합성 " + kind + index,
          "address_name",
          address,
          "category_group_code",
          group,
          "category_name",
          path,
          "y",
          "35.8",
          "x",
          Double.toString(129.2 + index * 0.001),
          "place_url",
          "https://example.test/place"
        )
      );
      // 같은 이름의 인접 지역 결과가 공급자 응답에 있어도 서버 주소 필터가 제외해야 합니다.
      places.add(
        Map.of(
          "id",
          "outside",
          "place_name",
          region + " 합성 외부 장소",
          "address_name",
          "경기 다른시 검증로 1",
          "category_group_code",
          "AT4",
          "category_name",
          "여행 > 관광",
          "y",
          "37.1",
          "x",
          "127.1",
          "place_url",
          "https://example.test/outside"
        )
      );
      byte[] body = json
        .write(Map.of("documents", places))
        .getBytes(StandardCharsets.UTF_8);
      exchange
        .getResponseHeaders()
        .set("Content-Type", "application/json;charset=UTF-8");
      exchange.sendResponseHeaders(200, body.length);
      try (var out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    supplier.start();
    int mockPort = supplier.getAddress().getPort();
    var factory = new SimpleClientHttpRequestFactory();
    var client = RestClient.builder()
      .requestFactory((uri, method) ->
        factory.createRequest(
          URI.create(
            "http://127.0.0.1:" +
              mockPort +
              uri.getRawPath() +
              "?" +
              uri.getRawQuery()
          ),
          method
        )
      )
      .build();
    ReflectionTestUtils.setField(catalog, "client", client);
    ReflectionTestUtils.setField(catalog, "kakaoKey", "synthetic-region-key");
    // 각 테스트가 독립적인 공급자 응답을 확인하도록 해당 서비스의 메모리 캐시만 비웁니다.
    ((Map<?, ?>) ReflectionTestUtils.getField(catalog, "cache")).clear();
  }

  @AfterEach
  void restoreSupplier() {
    supplier.stop(0);
    ReflectionTestUtils.setField(catalog, "client", originalClient);
    ReflectionTestUtils.setField(catalog, "kakaoKey", originalKey);
    ((Map<?, ?>) ReflectionTestUtils.getField(catalog, "cache")).clear();
  }

  @Test
  void publicConfigAndSearchShareFifteenRegionsWithoutMixingNearbyCities()
    throws Exception {
    var config = json.mapper().readTree(request("GET", "/config", null).body());
    assertThat(config.path("regions").size()).isEqualTo(15);
    assertThat(config.path("sampleData").asBoolean()).isFalse();
    assertThat(config.toString()).doesNotContain("synthetic-region-key");
    for (String region : TravelRegions.names(false)) {
      var response = request(
        "GET",
        "/catalog/places?region=" +
          URLEncoder.encode(region, StandardCharsets.UTF_8) +
          "&category=관광",
        null
      );
      assertThat(response.statusCode()).isEqualTo(200);
      var places = json.mapper().readTree(response.body());
      assertThat(places.size()).as(region).isEqualTo(4);
      places.forEach(place -> {
        assertThat(place.path("region").asText()).isEqualTo(region);
        assertThat(place.path("source").asText()).isEqualTo("KAKAO");
        assertThat(place.path("id").asText()).isNotEqualTo("kakao-outside");
      });
    }
    int before = calls.get();
    assertThat(
      request("GET", "/catalog/places?region=해외", null).statusCode()
    ).isEqualTo(400);
    assertThat(calls.get()).isEqualTo(before);
    ReflectionTestUtils.setField(catalog, "kakaoKey", "");
    var sampleConfig = json
      .mapper()
      .readTree(request("GET", "/config", null).body());
    assertThat(sampleConfig.path("regions").size()).isEqualTo(4);
    assertThat(sampleConfig.path("sampleData").asBoolean()).isTrue();
  }

  @Test
  void expandedRegionsCanRecommendAndSaveAMultiCityTrip() throws Exception {
    String email = UUID.randomUUID() + "@example.test";
    String password = "Region-Flow-Only12!";
    assertThat(
      request(
        "POST",
        "/auth/register",
        Map.of(
          "email",
          email,
          "password",
          password,
          "nickname",
          "지역확장 검증"
        )
      ).statusCode()
    ).isEqualTo(200);
    assertThat(
      request(
        "POST",
        "/auth/login",
        Map.of("email", email, "password", password)
      ).statusCode()
    ).isEqualTo(200);
    var conditions = Map.ofEntries(
      Map.entry("title", "합성 경주 전주 여행"),
      Map.entry("regions", List.of("경주", "전주")),
      Map.entry("startDate", "2026-11-01"),
      Map.entry("endDate", "2026-11-02"),
      Map.entry("origin", "서울역"),
      Map.entry("partySize", 2),
      Map.entry("budget", 500000),
      Map.entry("transport", "CAR"),
      Map.entry("preferences", List.of("관광", "전시")),
      Map.entry("lodging", "")
    );
    var recommendation = request("POST", "/recommendations", conditions);
    assertThat(recommendation.statusCode())
      .as(recommendation.body())
      .isEqualTo(200);
    var courses = json.mapper().readTree(recommendation.body());
    assertThat(courses.size()).isEqualTo(3);
    var items = courses.get(0).path("items");
    var regions = new HashSet<String>();
    items.forEach(item ->
      regions.add(item.path("place").path("region").asText())
    );
    assertThat(regions).containsExactlyInAnyOrder("경주", "전주");
    var save = request(
      "POST",
      "/trips",
      Map.of(
        "conditions",
        conditions,
        "items",
        json.read(items.toString(), List.class)
      )
    );
    assertThat(save.statusCode()).as(save.body()).isBetween(200, 299);
    var saved = json.mapper().readTree(save.body());
    assertThat(saved.path("regions").toString()).isEqualTo(
      "[\"경주\",\"전주\"]"
    );
    var invalid = new HashMap<String, Object>(conditions);
    invalid.put("regions", List.of("미지원"));
    int before = calls.get();
    assertThat(
      request("POST", "/recommendations", invalid).statusCode()
    ).isEqualTo(400);
    assertThat(calls.get()).isEqualTo(before);
  }

  HttpResponse<String> request(String method, String path, Object body)
    throws Exception {
    var request = HttpRequest.newBuilder(
      URI.create("http://127.0.0.1:" + port + "/api" + path)
    );
    if (!method.equals("GET")) {
      var csrf = json
        .mapper()
        .readTree(request("GET", "/auth/csrf", null).body())
        .path("token")
        .asText();
      request.header("X-XSRF-TOKEN", csrf);
    }
    return browser.send(
      request
        .header("Content-Type", "application/json")
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
}
