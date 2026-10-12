package kr.yeohaengpick.catalog;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import kr.yeohaengpick.common.*;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

/** 로컬 HTTP 공급자로 요청 인코딩·법정동 파라미터·캐시/호출 제한·부분 장애와 미제공을 재현합니다. 실제 키는 사용하지 않습니다. */
class TourismApiTest {

  private final JsonCodec json = new JsonCodec();
  private HttpServer server;
  private final AtomicInteger calls = new AtomicInteger();
  private final List<URI> seen = Collections.synchronizedList(
    new ArrayList<>()
  );
  private Function<URI, String> reply;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      calls.incrementAndGet();
      seen.add(exchange.getRequestURI());
      byte[] bytes = reply
        .apply(exchange.getRequestURI())
        .getBytes(StandardCharsets.UTF_8);
      exchange
        .getResponseHeaders()
        .set("Content-Type", "application/json;charset=UTF-8");
      exchange.sendResponseHeaders(200, bytes.length);
      try (var out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private TourApiClient client(String key, int limit) {
    return new TourApiClient(
      RestClient.builder()
        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
        .build(),
      json,
      key,
      limit,
      Clock.fixed(Instant.parse("2026-10-12T01:00:00Z"), ZoneOffset.UTC)
    );
  }

  private String data(Object items, int total) {
    return json.write(
      Map.of(
        "response",
        Map.of(
          "header",
          Map.of("resultCode", "0000"),
          "body",
          Map.of("items", Map.of("item", items), "totalCount", total)
        )
      )
    );
  }

  private Map<String, String> params(URI uri) {
    var result = new HashMap<String, String>();
    for (var pair : uri.getRawQuery().split("&")) {
      var parts = pair.split("=", 2);
      result.put(
        URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
        URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
      );
    }
    return result;
  }

  private Map<String, Object> place(String id, String type) {
    return new HashMap<>(
      Map.of(
        "contentid",
        id,
        "contenttypeid",
        type,
        "title",
        "검증 관광지",
        "addr1",
        "서울특별시 종로구 검증로 1",
        "modifiedtime",
        "20261011090000",
        "homepage",
        "<a href=\"https://example.test/place?a=1&amp;b=2\">공식 홈페이지</a>",
        "overview",
        "소개<br>둘째 줄"
      )
    );
  }

  @Test
  void encodesDecodedAndEncodedKeysAndUsesCachedSuccessBeforeBudget() {
    reply = uri -> data(Map.of("code", "11", "name", "서울특별시"), 1);
    var client = client("test%2B%2F%3Dkey", 1);
    var query = Map.of(
      "keyword",
      "서울 & 궁",
      "numOfRows",
      "20",
      "pageNo",
      "1"
    );
    var first = client.get("searchKeyword2", query, Duration.ofMinutes(5));
    assertThat(params(seen.getFirst()))
      .containsEntry("serviceKey", "test+/=key")
      .containsEntry("keyword", "서울 & 궁")
      .containsEntry("MobileOS", "WEB")
      .containsEntry("MobileApp", "YeohaengPick")
      .containsEntry("_type", "json");
    assertThat(first.items()).hasSize(1);
    assertThat(
      client.get("searchKeyword2", query, Duration.ofMinutes(5))
    ).isEqualTo(first);
    assertThat(calls).hasValue(1);
    assertThatThrownBy(() ->
      client.get(
        "searchKeyword2",
        Map.of("keyword", "다른 장소"),
        Duration.ofMinutes(5)
      )
    )
      .isInstanceOf(DomainException.class)
      .hasMessageContaining("오늘의");
    assertThat(calls).hasValue(1);
  }

  @Test
  void gatewayErrorsXmlAndMalformedPositiveCountsAreNotEmptySuccessOrLeakedUrls() {
    var bodies = List.of(
      "<OpenAPI_ServiceResponse>private-provider-sentinel</OpenAPI_ServiceResponse>",
      "{\"response\":{\"header\":{\"resultCode\":\"20\",\"resultMsg\":\"private-provider-sentinel\"}}}",
      "{\"response\":{\"header\":{\"resultCode\":\"22\"}}}",
      data(List.of(), 1),
      data(List.of(), -1)
    );
    var client = client("test-secret-key", 50);
    for (int n = 0; n < bodies.size(); n++) {
      String body = bodies.get(n);
      reply = uri -> body;
      int index = n;
      assertThatThrownBy(() ->
        client.get(
          "detailCommon2",
          Map.of("contentId", Integer.toString(index)),
          Duration.ZERO
        )
      )
        .isInstanceOf(DomainException.class)
        .hasMessageNotContaining("test-secret-key")
        .hasMessageNotContaining("private-provider-sentinel")
        .hasCause(null);
    }
  }

  @Test
  void emptyItemsAllowValidZeroAndPastLastPageButNotIncompleteFirstPage() {
    var client = client("test", 50);
    reply = uri -> data("", 0);
    assertThat(
      client.get("searchKeyword2", Map.of("pageNo", "1"), Duration.ZERO).items()
    ).isEmpty();
    reply = uri -> data("", 9);
    assertThat(
      client
        .get(
          "searchKeyword2",
          Map.of("pageNo", "2", "numOfRows", "20"),
          Duration.ZERO
        )
        .items()
    ).isEmpty();
    assertThatThrownBy(() ->
      client.get("searchKeyword2", Map.of("pageNo", "1"), Duration.ZERO)
    ).isInstanceOf(DomainException.class);
  }

  @Test
  void resolvesCurrentProvinceAndCityCodesAndFiltersAddressesWithoutLegacySearchFlags() {
    reply = uri -> {
      var p = params(uri);
      if (uri.getPath().equals("/ldongCode2")) return p.containsKey(
        "lDongRegnCd"
      )
        ? data(List.of(Map.of("code", "150", "name", "강릉시")), 1)
        : data(List.of(Map.of("code", "51", "name", "강원특별자치도")), 1);
      return data(
        List.of(
          Map.of(
            "contentid",
            "1",
            "contenttypeid",
            "12",
            "title",
            "검증 해변",
            "addr1",
            "강원특별자치도 강릉시 검증로 1"
          ),
          Map.of(
            "contentid",
            "2",
            "contenttypeid",
            "12",
            "title",
            "다른 해변",
            "addr1",
            "강원특별자치도 속초시 검증로 1"
          )
        ),
        2
      );
    };
    var service = new TourismService(client("test", 50));
    var results = service.search("강릉", " 해변 ", 1);
    assertThat(results.items())
      .extracting(TourismInfo.Content::id)
      .containsExactly("1");
    assertThat(results.totalCount()).isEqualTo(2);
    assertThat(params(seen.getLast()))
      .containsEntry("keyword", "해변")
      .containsEntry("lDongRegnCd", "51")
      .containsEntry("lDongSignguCd", "150")
      .doesNotContainKeys(
        "areaCode",
        "sigunguCode",
        "contentTypeId",
        "cat1",
        "cat2",
        "cat3"
      );
    service.search("강릉", "해변", 1);
    assertThat(calls).hasValue(3);
    assertThatThrownBy(() -> service.search("미지원", "해변", 1)).isInstanceOf(
      DomainException.class
    );
    assertThatThrownBy(() -> service.search("강릉", " ", 1)).isInstanceOf(
      DomainException.class
    );
    assertThatThrownBy(() -> service.search("강릉", "해변", 51)).isInstanceOf(
      DomainException.class
    );
  }

  @Test
  void combinesProviderFieldsWithoutInferringFareAndDoesNotSendRemovedCommonFlags() {
    reply = uri ->
      switch (uri.getPath()) {
        case "/detailCommon2" -> data(place("123", "12"), 1);
        case "/detailIntro2" -> data(
          Map.of(
            "contentid",
            "123",
            "contenttypeid",
            "12",
            "usetime",
            "09:00~18:00<br>입장 마감 17:00",
            "restdate",
            "매주 화요일",
            "infocenter",
            "02-000-0000"
          ),
          1
        );
        default -> data(
          List.of(
            Map.of(
              "contentid",
              "123",
              "contenttypeid",
              "12",
              "infoname",
              "입장료",
              "infotext",
              "성인 3,000원<br>어린이 무료"
            )
          ),
          1
        );
      };
    var service = new TourismService(client("test", 50));
    var detail = service.detail("123");
    assertThat(detail.hours().text()).isEqualTo("09:00~18:00\n입장 마감 17:00");
    assertThat(detail.fees().status()).isEqualTo("PROVIDED");
    assertThat(detail.fees().text()).contains("성인 3,000원", "어린이 무료");
    assertThat(detail.homepageUrl()).isEqualTo(
      "https://example.test/place?a=1&b=2"
    );
    assertThat(detail.source()).isEqualTo("한국관광공사 TourAPI");
    assertThat(params(seen.getFirst()))
      .containsEntry("contentId", "123")
      .doesNotContainKeys(
        "defaultYN",
        "firstImageYN",
        "areacodeYN",
        "catcodeYN",
        "addrinfoYN",
        "mapinfoYN",
        "overviewYN"
      );
    assertThat(params(seen.get(1))).containsEntry("contentTypeId", "12");
    assertThat(service.detail("123")).isEqualTo(detail);
    assertThat(calls).hasValue(3);
  }

  @Test
  void notProvidedDiffersFromPartialFailureAndMismatchedContent() {
    reply = uri ->
      uri.getPath().equals("/detailCommon2")
        ? data(place("123", "14"), 1)
        : data("", 0);
    var detail = new TourismService(client("test", 50)).detail("123");
    assertThat(detail.hours().status()).isEqualTo("NOT_PROVIDED");
    assertThat(detail.fees().status()).isEqualTo("NOT_PROVIDED");
    reply = uri ->
      uri.getPath().equals("/detailCommon2")
        ? data(place("123", "14"), 1)
        : data(Map.of("contentid", "999", "contenttypeid", "14"), 1);
    assertThatThrownBy(() ->
      new TourismService(client("test", 50)).detail("123")
    ).hasMessageContaining("다른 장소");
    reply = uri ->
      uri.getPath().equals("/detailCommon2")
        ? data(place("123", "14"), 1)
        : "provider failure";
    assertThatThrownBy(() ->
      new TourismService(client("test", 50)).detail("123")
    ).isInstanceOf(DomainException.class);
    reply = uri ->
      switch (uri.getPath()) {
        case "/detailCommon2" -> data(place("123", "12"), 1);
        case "/detailIntro2" -> data(
          Map.of("contentid", "123", "contenttypeid", "12"),
          1
        );
        default -> data(
          List.of(
            Map.of(
              "contentid",
              "123",
              "contenttypeid",
              "12",
              "infoname",
              "기타 안내",
              "infotext",
              "내용"
            )
          ),
          101
        );
      };
    assertThatThrownBy(() ->
      new TourismService(client("test", 50)).detail("123")
    ).hasMessageContaining("모두 확인하지 못했어요");
    reply = uri -> data("", 0);
    assertThatThrownBy(() ->
      new TourismService(client("test", 50)).detail("123")
    ).hasMessageContaining("해당 장소 정보가 없어요");
  }

  @Test
  void safeTextHomepageAndLodgingTimesDoNotTreatRoomDataAsBookablePrice() {
    assertThat(
      TourismInfo.text(
        "<script>alert(1)</script><p>A&amp;B</p>&lt;img src=x onerror=x&gt;다음<br/>줄"
      )
    ).isEqualTo("A&B\n다음\n줄");
    assertThat(TourismInfo.homepage("")).isEmpty();
    assertThat(
      TourismInfo.text("[겨울]09:00~17:00(입장 마감)[여름]09:00~18:00")
    ).isEqualTo("[겨울]09:00~17:00(입장 마감)\n[여름]09:00~18:00");
    assertThat(TourismInfo.homepage("javascript:alert(1)")).isEmpty();
    assertThat(
      TourismInfo.homepage("https://user:pass@example.test")
    ).isEmpty();
    assertThat(
      TourismInfo.homepage("<a href='javascript:alert(1)'>홈</a>")
    ).isEmpty();
    var detail = TourismInfo.detail(
      json.mapper().valueToTree(place("123", "32")),
      json
        .mapper()
        .valueToTree(Map.of("checkintime", "15:00", "checkouttime", "11:00")),
      List.of(json.mapper().valueToTree(Map.of("roomoffseasonminfee1", 0))),
      "2026-10-12T01:00:00Z"
    );
    assertThat(detail.hours().text()).isEqualTo("체크인 15:00\n체크아웃 11:00");
    assertThat(detail.fees().status()).isEqualTo("NOT_PROVIDED");
  }
}
