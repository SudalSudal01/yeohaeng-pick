package kr.yeohaengpick.catalog;

import java.time.*;
import java.util.*;
import kr.yeohaengpick.common.DomainException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 기존 지도 장소와 관광공사 콘텐츠를 이름으로 자동 병합하지 않습니다.
 * 사용자가 검색 결과의 주소·종류를 확인해 선택한 콘텐츠의 공개 이용 안내만 조회합니다.
 */
@Service
public class TourismService {

  private final TourApiClient client;

  public TourismService(TourApiClient client) {
    this.client = client;
  }

  private static final Duration SEARCH_TTL = Duration.ofMinutes(5),
    DETAIL_TTL = Duration.ofMinutes(30);

  public TourismInfo.Search search(String region, String query, int page) {
    if (
      !List.of("서울", "부산", "제주", "강릉").contains(region) ||
      query == null ||
      query.isBlank() ||
      query.length() > 80 ||
      query.matches("(?s).*[\\p{Cntrl}].*") ||
      page < 1 ||
      page > 50
    ) throw DomainException.bad(
      "지원 지역과 1~80자의 검색어를 확인해 주세요. 최대 50페이지까지 볼 수 있어요."
    );
    var parameters = new HashMap<String, String>();
    parameters.put("keyword", query.trim());
    parameters.put("arrange", "A");
    parameters.put("numOfRows", "20");
    parameters.put("pageNo", Integer.toString(page));
    parameters.putAll(regionCodes(region));
    var result = client.get("searchKeyword2", parameters, SEARCH_TTL);
    var contents = result
      .items()
      .stream()
      .map(TourismInfo::content)
      .filter(
        c ->
          c.id().matches("\\d{1,15}") &&
          TourismInfo.TYPES.containsKey(c.typeId()) &&
          !c.title().isBlank()
      )
      .filter(c -> KakaoPlaceClassifier.inRegion(region, c.address()))
      .toList();
    return new TourismInfo.Search(
      contents,
      result.totalCount(),
      page,
      20,
      result.queriedAt()
    );
  }

  /** 법정동 코드와 명칭은 공급자 목록에서 찾습니다. 폐지 예정인 areaCode/contentTypeId 검색 필터는 보내지 않습니다. */
  private Map<String, String> regionCodes(String region) {
    String province = region.equals("강릉") ? "강원" : region;
    var provinces = client.get(
      "ldongCode2",
      Map.of("numOfRows", "100", "pageNo", "1", "lDongListYn", "N"),
      Duration.ofHours(24)
    );
    String code = provinces
      .items()
      .stream()
      .filter(i -> i.path("name").asText().startsWith(province))
      .map(i -> i.path("code").asText())
      .filter(s -> s.matches("\\d{1,5}"))
      .findFirst()
      .orElseThrow(() ->
        new DomainException(
          503,
          "관광공사의 지역 코드를 확인하지 못했어요. 잠시 후 다시 시도해 주세요."
        )
      );
    if (!region.equals("강릉")) return Map.of("lDongRegnCd", code);
    var cities = client.get(
      "ldongCode2",
      Map.of(
        "numOfRows",
        "100",
        "pageNo",
        "1",
        "lDongListYn",
        "N",
        "lDongRegnCd",
        code
      ),
      Duration.ofHours(24)
    );
    String city = cities
      .items()
      .stream()
      .filter(i -> i.path("name").asText().equals("강릉시"))
      .map(i -> i.path("code").asText())
      .filter(s -> s.matches("\\d{1,5}"))
      .findFirst()
      .orElseThrow(() ->
        new DomainException(
          503,
          "관광공사의 강릉 지역 코드를 확인하지 못했어요. 잠시 후 다시 시도해 주세요."
        )
      );
    return Map.of("lDongRegnCd", code, "lDongSignguCd", city);
  }

  public TourismInfo.Detail detail(String id) {
    if (!id.matches("\\d{1,15}")) throw DomainException.bad(
      "관광정보의 장소를 다시 선택해 주세요."
    );
    var common = client.get(
      "detailCommon2",
      Map.of("contentId", id, "numOfRows", "1", "pageNo", "1"),
      DETAIL_TTL
    );
    if (common.items().isEmpty()) throw new DomainException(
      404,
      "관광공사에 해당 장소 정보가 없어요. 다른 결과를 선택해 주세요."
    );
    var content = common.items().getFirst();
    String type = content.path("contenttypeid").asText();
    if (
      !id.equals(content.path("contentid").asText()) ||
      !TourismInfo.TYPES.containsKey(type)
    ) throw new DomainException(
      503,
      "관광공사의 장소 종류를 확인하지 못했어요."
    );
    var params = Map.of(
      "contentId",
      id,
      "contentTypeId",
      type,
      "numOfRows",
      "100",
      "pageNo",
      "1"
    );
    var intro = client.get("detailIntro2", params, DETAIL_TTL);
    // 관광지 입장료가 반복정보에 있을 때만 추가 호출합니다. 숙소 객실 표를 실제 예약가로 표시하지 않습니다.
    TourApiClient.Result repeated = type.equals("12")
      ? client.get("detailInfo2", params, DETAIL_TTL)
      : null;
    // 일부 페이지만 본 상태에서 '요금 미제공'이라고 결론 내리지 않습니다.
    if (
      repeated != null && repeated.totalCount() > repeated.items().size()
    ) throw new DomainException(
      503,
      "관광공사 추가 안내를 모두 확인하지 못했어요. 장소 홈페이지에서 확인해 주세요."
    );
    for (var result : repeated == null
      ? List.of(intro)
      : List.of(intro, repeated))
      if (
        result
          .items()
          .stream()
          .anyMatch(
            i ->
              !id.equals(i.path("contentid").asText()) ||
              !type.equals(i.path("contenttypeid").asText())
          )
      ) throw new DomainException(
        503,
        "다른 장소의 관광정보가 반환돼 표시하지 않았어요. 다시 시도해 주세요."
      );
    // 소개정보가 정상적으로 비어 있으면 미제공입니다. 호출 실패일 때는 Client가 오류를 반환하므로 혼동하지 않습니다.
    JsonNode introduction = intro.items().isEmpty()
      ? content
      : intro.items().getFirst();
    // 묶음 안에서 가장 먼저 조회한 시각을 표시해 새 호출 하나가 오래된 캐시까지 갱신한 듯 보이지 않게 합니다.
    String oldest = List.of(
      common.queriedAt(),
      intro.queriedAt(),
      repeated == null ? intro.queriedAt() : repeated.queriedAt()
    )
      .stream()
      .min(Comparator.comparing(Instant::parse))
      .orElseThrow();
    return TourismInfo.detail(
      content,
      introduction,
      repeated == null ? List.of() : repeated.items(),
      oldest
    );
  }
}
