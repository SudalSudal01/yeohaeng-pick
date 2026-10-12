package kr.yeohaengpick.transport;

import static kr.yeohaengpick.transport.TransportRoutes.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import kr.yeohaengpick.common.*;
import kr.yeohaengpick.trip.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** 실제 경로를 명시적으로 조회합니다. 일정 시각·예산·예약 상태는 변경하지 않으며 출발지 원문을 DB에 추가 저장하지 않습니다. */
@Service
public class TransportService {

  private final TripService trips;
  private final TripMapper mapper;
  private final JsonCodec json;
  private final TransportQuotes quotes;

  @Value("${app.kakao-rest-key:}")
  private String key;

  private final RestClient client = RestClient.builder()
    .requestFactory(
      new org.springframework.http.client.SimpleClientHttpRequestFactory() {
        {
          setConnectTimeout(5000);
          setReadTimeout(12000);
        }
      }
    )
    .build();

  public record Ref(String kind, String id) {}

  public record Request(Ref from, Ref to, String mode) {}

  private record Origin(String member, Point point, long expires) {}

  private record Cached(List<Option> options, Instant at) {}

  private final Map<String, Origin> origins = new LinkedHashMap<>();
  private final Map<String, Cached> cache = new LinkedHashMap<>();
  private LocalDate quotaDay = LocalDate.now(ZoneId.of("Asia/Seoul"));
  private int calls;
  private final Map<String, Integer> memberCalls = new HashMap<>();

  public TransportService(
    TripService trips,
    TripMapper mapper,
    JsonCodec json,
    TransportQuotes quotes
  ) {
    this.trips = trips;
    this.mapper = mapper;
    this.json = json;
    this.quotes = quotes;
  }

  /** 서버 전체 100회/일, 회원당 30회/일의 보수적인 로컬 보호 한도입니다. 공급자 무료 잔량과는 별개이며 재시작 시 초기화됩니다. */
  private synchronized void reserve(String member) {
    if (key.isBlank()) throw new DomainException(
      503,
      "경로 조회 연결이 준비 중이에요. 공식 지도와 예매처에서 확인해 주세요."
    );
    LocalDate now = LocalDate.now(ZoneId.of("Asia/Seoul"));
    if (!now.equals(quotaDay)) {
      quotaDay = now;
      calls = 0;
      memberCalls.clear();
    }
    if (
      calls >= 100 || memberCalls.getOrDefault(member, 0) >= 30
    ) throw new DomainException(
      429,
      "오늘의 경로 조회 한도에 도달했어요. 카카오맵에서 이어서 확인해 주세요."
    );
    calls++;
    memberCalls.merge(member, 1, Integer::sum);
  }

  public List<Point> origins(String trip, String member, String query) {
    trips.access(trip, member); // 외부 호출과 검색 결과 발급보다 접근 권한 검사가 먼저입니다.
    if (
      query == null || query.isBlank() || query.length() > 100
    ) throw DomainException.bad("장소 이름을 1~100자로 입력해 주세요.");
    reserve(member);
    var root = get(
      "https://dapi.kakao.com/v2/local/search/keyword.json?query={q}&size=5",
      query.trim()
    );
    List<Point> result = new ArrayList<>();
    synchronized (origins) {
      origins
        .entrySet()
        .removeIf(e -> e.getValue().expires() < System.currentTimeMillis());
      for (var p : root.path("documents")) {
        double lat = p.path("y").asDouble(Double.NaN),
          lon = p.path("x").asDouble(Double.NaN);
        if (!valid(lat, lon)) continue;
        String token = UUID.randomUUID().toString();
        Point point = new Point(
          token,
          p.path("place_name").asText() +
            " · " +
            p.path("address_name").asText(),
          lat,
          lon
        );
        if (origins.size() >= 1000) origins.remove(
          origins.keySet().iterator().next()
        );
        origins.put(
          token,
          new Origin(member, point, System.currentTimeMillis() + 1_800_000)
        );
        result.add(point);
      }
    }
    return result;
  }

  public Result routes(String trip, String member, Request request) {
    var currentTrip = trips.access(trip, member);
    if (
      request == null ||
      !List.of("CAR", "TRANSIT", "WALK").contains(
        Objects.requireNonNullElse(request.mode(), "")
      )
    ) throw DomainException.bad("이동 수단을 선택해 주세요.");
    Point from = resolve(trip, member, request.from()),
      to = resolve(trip, member, request.to());
    if (
      Math.abs(from.latitude() - to.latitude()) +
        Math.abs(from.longitude() - to.longitude()) <
      0.00001
    ) throw DomainException.bad(
      "출발지와 도착지가 같아요. 다른 장소를 선택해 주세요."
    );
    String mode = request.mode();
    String cacheKey =
      mode +
      ":" +
      from.latitude() +
      ":" +
      from.longitude() +
      ":" +
      to.latitude() +
      ":" +
      to.longitude();
    Cached cached;
    synchronized (cache) {
      cached = cache.get(cacheKey);
    }
    if (
      cached == null || cached.at().isBefore(Instant.now().minusSeconds(300))
    ) {
      reserve(member);
      var root = mode.equals("CAR")
        ? get(
            "https://apis-navi.kakaomobility.com/v1/directions?origin={o}&destination={d}&alternatives=true",
            from.longitude() + "," + from.latitude(),
            to.longitude() + "," + to.latitude()
          )
        : get(
            "https://dapi.kakao.com/v2/routing/" +
              (mode.equals("WALK") ? "walk" : "publictraffic") +
              "?start_x={sx}&start_y={sy}&end_x={ex}&end_y={ey}",
            from.longitude(),
            from.latitude(),
            to.longitude(),
            to.latitude()
          );
      cached = new Cached(TransportRoutes.parse(mode, root), Instant.now());
      synchronized (cache) {
        cache
          .entrySet()
          .removeIf(e ->
            e.getValue().at().isBefore(Instant.now().minusSeconds(300))
          );
        if (cache.size() >= 200) cache.remove(cache.keySet().iterator().next());
        cache.put(cacheKey, cached);
      }
    }
    String by = mode.equals("TRANSIT")
      ? "traffic"
      : mode.equals("CAR")
        ? "car"
        : "walk";
    return new Result(
      from,
      to,
      mode,
      cached.at().toString(),
      mode.equals("CAR") ? "KAKAO_MOBILITY" : "KAKAO_MAP",
      cached.options(),
      "https://map.kakao.com/link/by/" +
        by +
        "/" +
        linkPoint(from) +
        "/" +
        linkPoint(to),
      quotes.issue(
        trip,
        member,
        ((Number) currentTrip.get("version")).intValue(),
        request,
        from,
        to,
        cached.at().toString(),
        mode.equals("CAR") ? "KAKAO_MOBILITY" : "KAKAO_MAP",
        cached.options()
      )
    );
  }

  private Point resolve(String trip, String member, Ref ref) {
    if (
      ref == null || ref.kind() == null || ref.id() == null
    ) throw DomainException.bad("출발지와 도착지를 선택해 주세요.");
    if (ref.kind().equals("ORIGIN")) {
      synchronized (origins) {
        Origin origin = origins.get(ref.id());
        if (
          origin == null ||
          !origin.member().equals(member) ||
          origin.expires() < System.currentTimeMillis()
        ) throw DomainException.bad(
          "장소 검색 결과가 만료됐어요. 다시 검색해 주세요."
        );
        return origin.point();
      }
    }
    boolean lodging = ref.kind().equals("LODGING");
    if (!lodging && !ref.kind().equals("ITEM")) throw DomainException.bad(
      "선택한 장소를 확인해 주세요."
    );
    var points = lodging ? mapper.lodgings(trip) : mapper.items(trip);
    var point = points
      .stream()
      .filter(p -> ref.id().equals(p.get(lodging ? "placeId" : "id")))
      .findFirst()
      .orElseThrow(() ->
        DomainException.bad("이 여행에 포함된 장소만 조회할 수 있어요.")
      );
    double lat = ((Number) point.get("latitude")).doubleValue(),
      lon = ((Number) point.get("longitude")).doubleValue();
    if (!valid(lat, lon)) throw DomainException.bad(
      "장소 좌표를 확인하지 못했어요."
    );
    return new Point(
      ref.id(),
      (String) point.get(lodging ? "name" : "placeName"),
      lat,
      lon
    );
  }

  private boolean valid(double lat, double lon) {
    return (
      Double.isFinite(lat) &&
      Double.isFinite(lon) &&
      lat >= 32 &&
      lat <= 39 &&
      lon >= 124 &&
      lon <= 132
    );
  }

  private String linkPoint(Point point) {
    return (
      URLEncoder.encode(point.label(), StandardCharsets.UTF_8).replace(
        "+",
        "%20"
      ) +
      "," +
      point.latitude() +
      "," +
      point.longitude()
    );
  }

  private tools.jackson.databind.JsonNode get(String url, Object... args) {
    try {
      return json.mapper().readTree(
        client
          .get()
          .uri(url, args)
          .header("Authorization", "KakaoAK " + key)
          .retrieve()
          .body(String.class)
      );
    } catch (RestClientResponseException e) {
      // URL·원문 응답·키를 사용자나 로그에 내보내지 않습니다. 쿼터 오류는 재시도 폭주를 막도록 별도로 안내합니다.
      if (e.getStatusCode().value() == 429) throw new DomainException(
        429,
        "경로 서비스 조회 한도에 도달했어요. 공식 지도에서 확인해 주세요."
      );
      throw new DomainException(
        503,
        "경로 서비스에 연결하지 못했어요. 잠시 후 다시 조회해 주세요."
      );
    } catch (Exception e) {
      throw new DomainException(
        503,
        "경로 서비스 응답이 늦거나 올바르지 않아요. 잠시 후 다시 조회해 주세요."
      );
    }
  }
}
