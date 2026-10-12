package kr.yeohaengpick.catalog;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import kr.yeohaengpick.common.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** 외부 장소 검색과 로컬 예시 목록을 분리합니다. 운영에서는 API 장애를 예시 데이터로 조용히 덮지 않습니다. */
@Service
public class CatalogService {

  @Value("${app.kakao-rest-key:}")
  private String kakaoKey;

  @Value("${app.sample-data:false}")
  private boolean sample;

  private final JsonCodec json;
  private final RestClient client = RestClient.builder()
    .requestFactory(
      new org.springframework.http.client.SimpleClientHttpRequestFactory() {
        {
          setConnectTimeout(5000);
          setReadTimeout(8000);
        }
      }
    )
    .build();

  public CatalogService(JsonCodec j) {
    json = j;
  }

  public boolean isSample() {
    return sample && kakaoKey.isBlank();
  }

  public List<Place> search(String region, String query) {
    return search(region, query, "전체");
  }

  /** 분류를 서버 검색에 반영합니다. 먼저 무관한 15곳을 받은 뒤 화면에서만 거르면 빈 목록이 됩니다. */
  public List<Place> search(String region, String query, String category) {
    if (
      !List.of("서울", "부산", "제주", "강릉").contains(region) ||
      query.length() > 100 ||
      (!QUERIES.containsKey(category) && !category.equals("전체"))
    ) throw DomainException.bad("지원 지역과 검색 조건을 확인해 주세요.");
    if (!kakaoKey.isBlank()) {
      if (!query.isBlank()) return external(region, query)
        .stream()
        .filter(p -> category.equals("전체") || p.category().equals(category))
        .toList();
      return category.equals("전체")
        ? candidates(List.of(region))
        : external(region, QUERIES.get(category))
            .stream()
            .filter(p -> p.category().equals(category))
            .toList();
    }
    if (!sample) throw new DomainException(
      503,
      "장소 검색 서비스가 준비 중이에요. 운영자가 API 설정을 확인해야 해요."
    );
    return examples()
      .stream()
      .filter(p -> region.isBlank() || p.region().equals(region))
      .filter(p -> query.isBlank() || (p.name() + p.category()).contains(query))
      .filter(p -> category.equals("전체") || p.category().equals(category))
      .toList();
  }

  public List<Place> candidates(List<String> regions) {
    List<Place> all = new ArrayList<>();
    for (String region : regions) {
      if (kakaoKey.isBlank()) all.addAll(search(region, ""));
      else for (String query : List.of(
        "관광명소",
        "공원",
        "음식점",
        "카페",
        "미술관",
        "박물관",
        "체험학습장",
        "숙소"
      ))
        all.addAll(external(region, query));
    }
    return all
      .stream()
      .collect(
        java.util.stream.Collectors.toMap(
          Place::id,
          p -> p,
          (a, b) -> a,
          LinkedHashMap::new
        )
      )
      .values()
      .stream()
      .toList();
  }

  private static final Map<String, String> QUERIES =
    Collections.unmodifiableMap(
      new LinkedHashMap<>(
        Map.of(
          "관광",
          "관광명소",
          "자연",
          "공원",
          "맛집",
          "음식점",
          "카페",
          "카페",
          "전시",
          "미술관",
          "체험",
          "체험학습장",
          "숙소",
          "숙소"
        )
      )
    );

  private record Cached(long expires, List<Place> places) {}

  private final Map<String, Cached> cache = new LinkedHashMap<>();

  private List<Place> external(String region, String query) {
    String cacheKey = region + ":" + query;
    synchronized (cache) {
      var hit = cache.get(cacheKey);
      if (
        hit != null && hit.expires() > System.currentTimeMillis()
      ) return hit.places();
    }
    try {
      String response = client
        .get()
        .uri(
          "https://dapi.kakao.com/v2/local/search/keyword.json?query={q}&size=15",
          region + " " + query
        )
        .header("Authorization", "KakaoAK " + kakaoKey)
        .retrieve()
        .body(String.class);
      var root = json.mapper().readTree(response);
      List<Place> places = new ArrayList<>();
      for (var p : root.path("documents")) {
        String group = p.path("category_group_code").asText();
        String category = KakaoPlaceClassifier.category(
          group,
          p.path("category_name").asText()
        );
        if (
          category == null ||
          !KakaoPlaceClassifier.inRegion(
            region,
            p.path("address_name").asText()
          )
        ) continue;
        places.add(
          new Place(
            "kakao-" + p.path("id").asText(),
            p.path("place_name").asText(),
            region,
            category,
            p.path("y").asDouble(),
            p.path("x").asDouble(),
            category.equals("숙소") ? 0 : 60,
            category.equals("맛집")
              ? 18000
              : category.equals("카페")
                ? 7000
                : 0,
            !List.of("관광", "자연", "체험").contains(category),
            "KAKAO",
            p.path("place_url").asText()
          )
        );
      }
      // 동일 조건의 추천·저장 시 결과가 갑자기 바뀌는 일을 줄이고 무료 쿼터를 아낍니다.
      synchronized (cache) {
        cache
          .entrySet()
          .removeIf(e -> e.getValue().expires() < System.currentTimeMillis());
        if (cache.size() >= 200) cache.remove(cache.keySet().iterator().next());
        cache.put(
          cacheKey,
          new Cached(System.currentTimeMillis() + 300_000, List.copyOf(places))
        );
      }
      return places;
    } catch (Exception e) {
      throw new DomainException(
        503,
        "장소 정보를 가져오지 못했어요. 잠시 후 다시 시도해 주세요."
      );
    }
  }

  /** 실제 존재하는 장소를 사용한 로컬 시연 목록입니다. 운영시간·요금·재고를 확인한 데이터가 아닙니다. */
  public List<Place> examples() {
    List<Place> result = new ArrayList<>();
    add(result, "서울", "서울숲", "자연", 37.5445, 127.0374, 90, 0, false);
    add(
      result,
      "서울",
      "리움미술관",
      "전시",
      37.5384,
      126.9993,
      90,
      15000,
      true
    );
    add(result, "서울", "경복궁", "관광", 37.5796, 126.9770, 90, 3000, false);
    add(
      result,
      "서울",
      "국립중앙박물관",
      "전시",
      37.5239,
      126.9804,
      90,
      0,
      true
    );
    add(result, "서울", "광장시장", "맛집", 37.5701, 126.9999, 60, 15000, true);
    add(
      result,
      "서울",
      "성수동 카페거리",
      "카페",
      37.5446,
      127.0558,
      60,
      8000,
      true
    );
    add(
      result,
      "서울",
      "북촌한옥마을",
      "관광",
      37.5826,
      126.9830,
      60,
      0,
      false
    );
    add(result, "서울", "망원시장", "맛집", 37.5560, 126.9055, 60, 12000, true);
    add(
      result,
      "서울",
      "남산서울타워",
      "관광",
      37.5512,
      126.9882,
      90,
      21000,
      false
    );
    add(
      result,
      "서울",
      "동대문디자인플라자",
      "전시",
      37.5665,
      127.0090,
      60,
      10000,
      true
    );
    add(result, "서울", "노들섬", "자연", 37.5175, 126.9580, 60, 0, false);
    add(
      result,
      "서울",
      "서울신라호텔",
      "숙소",
      37.5559,
      127.0050,
      0,
      260000,
      true
    );
    add(
      result,
      "부산",
      "광안리해수욕장",
      "자연",
      35.1532,
      129.1186,
      90,
      0,
      false
    );
    add(
      result,
      "부산",
      "해운대해수욕장",
      "자연",
      35.1587,
      129.1604,
      90,
      0,
      false
    );
    add(
      result,
      "부산",
      "부산시립미술관",
      "전시",
      35.1669,
      129.1375,
      90,
      10000,
      true
    );
    add(
      result,
      "부산",
      "감천문화마을",
      "관광",
      35.0975,
      129.0106,
      90,
      0,
      false
    );
    add(
      result,
      "부산",
      "흰여울문화마을",
      "관광",
      35.0787,
      129.0442,
      60,
      0,
      false
    );
    add(result, "부산", "국제시장", "맛집", 35.1010, 129.0280, 60, 15000, true);
    add(
      result,
      "부산",
      "전포카페거리",
      "카페",
      35.1553,
      129.0643,
      60,
      8000,
      true
    );
    add(
      result,
      "부산",
      "부산엑스더스카이",
      "관광",
      35.1596,
      129.1691,
      60,
      27000,
      true
    );
    add(
      result,
      "부산",
      "해운대블루라인파크",
      "체험",
      35.1602,
      129.1805,
      60,
      16000,
      false
    );
    add(
      result,
      "부산",
      "부산현대미술관",
      "전시",
      35.1090,
      128.9421,
      90,
      0,
      true
    );
    add(
      result,
      "부산",
      "자갈치시장",
      "맛집",
      35.0966,
      129.0306,
      60,
      25000,
      true
    );
    add(
      result,
      "부산",
      "파크하얏트부산",
      "숙소",
      35.1553,
      129.1411,
      0,
      270000,
      true
    );
    add(
      result,
      "제주",
      "함덕해수욕장",
      "자연",
      33.5433,
      126.6690,
      90,
      0,
      false
    );
    add(
      result,
      "제주",
      "성산일출봉",
      "관광",
      33.4581,
      126.9425,
      90,
      5000,
      false
    );
    add(result, "제주", "비자림", "자연", 33.4889, 126.8097, 90, 3000, false);
    add(
      result,
      "제주",
      "아르떼뮤지엄제주",
      "전시",
      33.3966,
      126.3446,
      90,
      17000,
      true
    );
    add(
      result,
      "제주",
      "오설록티뮤지엄",
      "카페",
      33.3059,
      126.2895,
      60,
      12000,
      true
    );
    add(
      result,
      "제주",
      "제주동문시장",
      "맛집",
      33.5120,
      126.5260,
      60,
      15000,
      true
    );
    add(
      result,
      "제주",
      "협재해수욕장",
      "자연",
      33.3940,
      126.2390,
      90,
      0,
      false
    );
    add(
      result,
      "제주",
      "서귀포매일올레시장",
      "맛집",
      33.2480,
      126.5630,
      60,
      15000,
      true
    );
    add(
      result,
      "제주",
      "국립제주박물관",
      "전시",
      33.5130,
      126.5490,
      90,
      0,
      true
    );
    add(
      result,
      "제주",
      "제주돌문화공원",
      "관광",
      33.4480,
      126.6590,
      90,
      5000,
      false
    );
    add(result, "제주", "산굼부리", "관광", 33.4320, 126.6880, 60, 7000, false);
    add(
      result,
      "제주",
      "제주신라호텔",
      "숙소",
      33.2479,
      126.4078,
      0,
      260000,
      true
    );
    add(result, "강릉", "경포해변", "자연", 37.8040, 128.9090, 90, 0, false);
    add(result, "강릉", "안목해변", "카페", 37.7730, 128.9480, 60, 8000, true);
    add(
      result,
      "강릉",
      "아르떼뮤지엄강릉",
      "전시",
      37.7890,
      128.8980,
      90,
      17000,
      true
    );
    add(result, "강릉", "오죽헌", "관광", 37.7790, 128.8790, 90, 3000, false);
    add(
      result,
      "강릉",
      "강릉중앙시장",
      "맛집",
      37.7540,
      128.8990,
      60,
      15000,
      true
    );
    add(result, "강릉", "선교장", "관광", 37.7860, 128.8850, 60, 5000, false);
    add(
      result,
      "강릉",
      "초당순두부마을",
      "맛집",
      37.7900,
      128.9150,
      60,
      15000,
      true
    );
    add(
      result,
      "강릉",
      "허균허난설헌기념공원",
      "자연",
      37.7900,
      128.9100,
      60,
      0,
      false
    );
    add(
      result,
      "강릉",
      "하슬라아트월드",
      "전시",
      37.7060,
      129.0110,
      90,
      17000,
      true
    );
    add(result, "강릉", "정동진해변", "자연", 37.6900, 129.0340, 90, 0, false);
    add(
      result,
      "강릉",
      "참소리축음기박물관",
      "전시",
      37.8050,
      128.8960,
      60,
      12000,
      true
    );
    add(
      result,
      "강릉",
      "씨마크호텔",
      "숙소",
      37.7970,
      128.9180,
      0,
      280000,
      true
    );
    // 저장 후 숙소 변경도 시연할 수 있도록 기존 ID를 유지한 채 끝에 후보를 추가합니다. 요금은 검증되지 않은 예시입니다.
    add(
      result,
      "서울",
      "롯데호텔 서울",
      "숙소",
      37.5651,
      126.9811,
      0,
      240000,
      true
    );
    return result;
  }

  private void add(
    List<Place> list,
    String region,
    String name,
    String category,
    double lat,
    double lon,
    int duration,
    int cost,
    boolean indoor
  ) {
    list.add(
      new Place(
        "sample-" + list.size(),
        name,
        region,
        category,
        lat,
        lon,
        duration,
        cost,
        indoor,
        "SAMPLE",
        "https://map.kakao.com/link/search/" +
          URLEncoder.encode(region + " " + name, StandardCharsets.UTF_8)
      )
    );
  }
}
