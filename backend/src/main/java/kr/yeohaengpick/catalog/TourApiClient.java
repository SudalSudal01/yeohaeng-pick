package kr.yeohaengpick.catalog;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import kr.yeohaengpick.common.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * 관광공사 KorService2의 서버 전용 통신 경계입니다. 키를 브라우저·오류·캐시 키에 노출하지 않습니다.
 * 정상적인 빈 응답, 인증/한도 오류, 공급자 장애를 구분하며 예시 데이터로 대체하지 않습니다.
 */
@Component
public class TourApiClient {

  public record Result(
    List<JsonNode> items,
    int totalCount,
    String queriedAt
  ) {}

  private record Cached(Instant expires, Result result) {}

  private final RestClient client;
  private final JsonCodec json;
  private final String key;
  private final Clock clock;
  private final int limit;
  private LocalDate budgetDate;
  private int used;
  private final Semaphore requests = new Semaphore(2);
  private final Map<String, Cached> cache = new LinkedHashMap<>();

  @Autowired
  public TourApiClient(
    JsonCodec json,
    @Value("${app.tour-api-key:}") String key,
    @Value("${app.tour-api-daily-limit:300}") int limit
  ) {
    this(
      RestClient.builder()
        .baseUrl("https://apis.data.go.kr/B551011/KorService2")
        .requestFactory(
          new org.springframework.http.client.SimpleClientHttpRequestFactory() {
            {
              setConnectTimeout(5000);
              setReadTimeout(8000);
            }
          }
        )
        .build(),
      json,
      key,
      limit,
      Clock.systemUTC()
    );
  }

  /** 테스트만 로컬 HTTP 서버와 고정 시계를 주입합니다. 외부 주소를 요청 파라미터로 받지 않습니다. */
  TourApiClient(
    RestClient client,
    JsonCodec json,
    String key,
    int limit,
    Clock clock
  ) {
    this.client = client;
    this.json = json;
    this.clock = clock;
    this.limit = Math.max(1, Math.min(1000, limit));
    // 공공데이터포털의 Encoding/Decoding 키 모두 한 번만 인코딩하여 보냅니다.
    // '+'는 원본 키의 일부일 수 있으므로 인코딩된 '%'가 있는 경우에만 해제합니다.
    try {
      this.key = key.contains("%")
        ? URLDecoder.decode(key.trim(), StandardCharsets.UTF_8)
        : key.trim();
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
        "TourAPI 서비스키의 인코딩 형식을 확인해 주세요."
      );
    }
  }

  public boolean configured() {
    return !key.isBlank();
  }

  /** 날짜 기준은 한국 시간입니다. 재시작/다중 서버에서 공유되는 공급자 한도 자체를 대체하지는 않습니다. */
  private synchronized void reserve() {
    var today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
    if (!today.equals(budgetDate)) {
      budgetDate = today;
      used = 0;
    }
    if (used >= limit) throw new DomainException(
      429,
      "오늘의 관광정보 조회 한도에 도달했어요. 내일 다시 확인해 주세요."
    );
    used++;
  }

  public Result get(
    String endpoint,
    Map<String, String> parameters,
    Duration ttl
  ) {
    if (
      !List.of(
        "ldongCode2",
        "searchKeyword2",
        "detailCommon2",
        "detailIntro2",
        "detailInfo2"
      ).contains(endpoint)
    ) throw new IllegalArgumentException("지원하지 않는 관광정보 조회입니다.");
    if (!configured()) throw new DomainException(
      503,
      "관광공사 정보 연결을 준비 중이에요. 지금은 지도 상세에서 확인해 주세요."
    );
    var params = new TreeMap<>(parameters);
    String cacheKey = endpoint + params;
    synchronized (cache) {
      var hit = cache.get(cacheKey);
      if (
        hit != null && hit.expires().isAfter(clock.instant())
      ) return hit.result();
    }
    if (!requests.tryAcquire()) throw new DomainException(
      429,
      "관광정보 조회가 몰리고 있어요. 잠시 후 다시 시도해 주세요."
    );
    try {
      reserve();
      String body = client
        .get()
        .uri(builder -> {
          builder
            .path("/" + endpoint)
            .queryParam("serviceKey", "{serviceKey}")
            .queryParam("MobileOS", "WEB")
            .queryParam("MobileApp", "YeohaengPick")
            .queryParam("_type", "json");
          // 모든 값을 URI 템플릿 변수로 넘겨 '+', '/', 한글, '&'를 데이터로 인코딩합니다.
          var values = new HashMap<String, Object>();
          values.put("serviceKey", key);
          params.forEach((name, value) -> {
            builder.queryParam(name, "{" + name + "}");
            values.put(name, value);
          });
          return builder.build(values);
        })
        .retrieve()
        .body(String.class);
      var result = parse(body, params);
      synchronized (cache) {
        cache
          .entrySet()
          .removeIf(e -> !e.getValue().expires().isAfter(clock.instant()));
        if (cache.size() >= 400) cache.remove(cache.keySet().iterator().next());
        cache.put(cacheKey, new Cached(clock.instant().plus(ttl), result));
      }
      return result;
    } catch (DomainException e) {
      throw e;
    } catch (Exception e) {
      // 공급자 예외에는 serviceKey가 포함된 URL이 있을 수 있어 원문/원인을 밖으로 전달하지 않습니다.
      throw new DomainException(
        503,
        "관광공사 정보를 가져오지 못했어요. 잠시 후 다시 시도해 주세요."
      );
    } finally {
      requests.release();
    }
  }

  /** 공공 게이트웨이가 HTTP 200으로 반환하는 인증 오류와 비 JSON(XML) 장애도 정상 데이터로 취급하지 않습니다. */
  private Result parse(String body, Map<String, String> parameters) {
    var response = json.mapper().readTree(body).path("response");
    String code = response.path("header").path("resultCode").asText();
    if (!code.equals("0000")) {
      if (List.of("22", "23").contains(code)) throw new DomainException(
        429,
        "관광공사 조회 한도에 도달했어요. 잠시 후 다시 확인해 주세요."
      );
      throw new DomainException(
        503,
        "관광공사 응답을 확인하지 못했어요. 잠시 후 다시 시도해 주세요."
      );
    }
    var payload = response.path("body");
    int total = payload.path("totalCount").asInt(-1);
    var node = payload.path("items").path("item");
    List<JsonNode> items = new ArrayList<>();
    if (node.isArray()) node.forEach(items::add);
    else if (node.isObject()) items.add(node);
    long offset =
      (Long.parseLong(parameters.getOrDefault("pageNo", "1")) - 1) *
      Long.parseLong(parameters.getOrDefault("numOfRows", "20"));
    if (
      total < items.size() ||
      (total > offset && items.isEmpty()) ||
      items.size() > 100
    ) throw new DomainException(
      503,
      "관광공사 응답 형식을 확인하지 못했어요. 다시 시도해 주세요."
    );
    return new Result(List.copyOf(items), total, clock.instant().toString());
  }
}
