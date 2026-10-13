package kr.yeohaengpick.catalog;

import java.util.List;

/** 검색어가 아닌 카카오의 업종 분류로 여행 후보를 판별합니다. 관련 없는 장소를 관광지로 승격하지 않습니다. */
public final class KakaoPlaceClassifier {

  private KakaoPlaceClassifier() {}

  public static String category(String group, String path) {
    if (group.equals("FD6")) return "맛집";
    if (group.equals("CE7")) return "카페";
    if (group.equals("AD5")) return "숙소";
    if (
      contains(path, List.of("미술관", "박물관", "전시관", "갤러리"))
    ) return "전시";
    // 병원·주차장 등의 이름에 '체험'이 포함되어도 통과하지 않도록 업종 경로만 확인합니다.
    if (
      contains(path, List.of("체험학습장", "공방", "테마파크", "놀이공원"))
    ) return "체험";
    // Kakao의 공원 업종은 AT4 코드 없이 '여행 > 공원' 경로로 반환되기도 합니다.
    if (path.startsWith("여행 > 공원")) return "자연";
    if (group.equals("AT4") || path.startsWith("여행 > 관광")) {
      if (
        contains(
          path,
          List.of("공원", "수목원", "식물원", "해수욕장", "해변", "숲", "자연")
        )
      ) return "자연";
      return "관광";
    }
    // CT1에는 도서관·컨벤션센터도 포함됩니다. 전시 개최 여부를 모르면 전시로 분류하지 않습니다.
    return null;
  }

  private static boolean contains(String path, List<String> words) {
    return words.stream().anyMatch(path::contains);
  }

  /** 지역 키워드 검색은 인접 도시도 반환할 수 있어 실제 주소로 지역을 다시 확인합니다. */
  public static boolean inRegion(String region, String address) {
    return TravelRegions.containsAddress(region, address);
  }
}
