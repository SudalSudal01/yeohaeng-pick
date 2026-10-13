package kr.yeohaengpick.catalog;

import java.util.*;
import kr.yeohaengpick.common.DomainException;

/**
 * 여행픽에서 실제 검색을 지원하는 여행 지역의 단일 기준입니다.
 * 광역시는 전체 행정구역, 일반 도시는 해당 시만 검색하며 제주는 기존 계약대로 섬 전체입니다.
 * 공급자 코드 숫자는 저장하지 않습니다. TourAPI가 반환한 현재 법정동 명칭으로 매번 캐시 조회합니다.
 */
public final class TravelRegions {

  private TravelRegions() {}

  public record Region(String name, List<String> provinceNames, String city) {}

  private static Region metro(String name, String official) {
    return new Region(name, List.of(name, official), null);
  }

  private static final List<Region> REGIONS = List.of(
    metro("서울", "서울특별시"),
    metro("부산", "부산광역시"),
    new Region("제주", List.of("제주", "제주특별자치도"), null),
    new Region("강릉", List.of("강원", "강원도", "강원특별자치도"), "강릉시"),
    metro("인천", "인천광역시"),
    metro("대구", "대구광역시"),
    metro("대전", "대전광역시"),
    new Region(
      "광주",
      List.of("광주", "광주광역시", "전남광주통합특별시"),
      null
    ),
    metro("울산", "울산광역시"),
    new Region("수원", List.of("경기", "경기도"), "수원시"),
    new Region("경주", List.of("경북", "경상북도"), "경주시"),
    new Region("전주", List.of("전북", "전라북도", "전북특별자치도"), "전주시"),
    new Region(
      "여수",
      List.of("전남", "전라남도", "전남광주통합특별시"),
      "여수시"
    ),
    new Region("속초", List.of("강원", "강원도", "강원특별자치도"), "속초시"),
    new Region("춘천", List.of("강원", "강원도", "강원특별자치도"), "춘천시")
  );

  /** 예시가 없는 지역을 로컬 예시 모드에서 고르게 하지 않습니다. 실제 검색 모드는 15곳입니다. */
  public static List<String> names(boolean sampleOnly) {
    return REGIONS.stream()
      .limit(sampleOnly ? 4 : REGIONS.size())
      .map(Region::name)
      .toList();
  }

  public static boolean supports(String name) {
    return REGIONS.stream().anyMatch(region -> region.name().equals(name));
  }

  public static Region require(String name) {
    return REGIONS.stream()
      .filter(region -> region.name().equals(name))
      .findFirst()
      .orElseThrow(() ->
        DomainException.bad("지원하는 여행 지역을 다시 선택해 주세요.")
      );
  }

  /** 동명이인 지역의 결과가 상위 15건을 차지하지 않도록 공급자 검색어에도 상위 행정구역을 넣습니다. */
  public static String searchPrefix(String name) {
    Region region = require(name);
    if (name.equals("광주")) return "광주광역시";
    return (
      region.provinceNames().getLast() +
      (region.city() == null ? "" : " " + region.city())
    );
  }

  /**
   * 광주는 통합된 상위 행정구역만 검색하면 전남 전체 후보가 앞에 올 수 있습니다.
   * 넉넉한 도시 주변 검색 사각형으로 후보를 좁힌 뒤 아래의 실제 구 주소로 다시 검사합니다.
   * 이 사각형은 행정 경계 판정이나 사용자 위치가 아니라 공급자 검색 범위일 뿐입니다.
   */
  public static String searchBounds(String name) {
    return name.equals("광주") ? "126.64,35.00,127.03,35.28" : null;
  }

  /**
   * 단어 경계와 상위 시도를 함께 확인합니다. '광주' 검색의 경기도 광주시,
   * '강릉'을 이름에 가진 다른 시의 상호나 주소를 여행 후보로 잘못 넣지 않습니다.
   */
  public static boolean containsAddress(String name, String address) {
    if (!supports(name) || address == null || address.isBlank()) return false;
    Region region = require(name);
    String[] parts = address.strip().split("\\s+");
    if (!region.provinceNames().contains(parts[0])) return false;
    // 2026년 통합 주소에서 광주 5개 구와 전남의 시·군을 구분합니다. 상위 명칭만 같다고 통과시키지 않습니다.
    if (name.equals("광주") && parts[0].equals("전남광주통합특별시")) return (
      parts.length > 1 &&
      Set.of("동구", "서구", "남구", "북구", "광산구").contains(parts[1])
    );
    return (
      region.city() == null ||
      (parts.length > 1 && region.city().equals(parts[1]))
    );
  }
}
