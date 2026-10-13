package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import kr.yeohaengpick.catalog.*;
import kr.yeohaengpick.common.DomainException;
import org.junit.jupiter.api.Test;

/** 지역 이름이 같은 다른 시도와 인접 도시가 여행 후보로 섞이지 않도록 주소 경계를 확인합니다. */
class TravelRegionsTest {

  @Test
  void supportsFifteenDestinationsButLimitsSampleModeToExistingExamples() {
    assertThat(TravelRegions.searchPrefix("광주")).isEqualTo("광주광역시");
    assertThat(TravelRegions.searchPrefix("여수")).isEqualTo(
      "전남광주통합특별시 여수시"
    );
    assertThat(TravelRegions.names(false))
      .hasSize(15)
      .doesNotHaveDuplicates()
      .contains(
        "인천",
        "대구",
        "대전",
        "광주",
        "울산",
        "수원",
        "경주",
        "전주",
        "여수",
        "속초",
        "춘천"
      );
    assertThat(TravelRegions.names(true)).containsExactly(
      "서울",
      "부산",
      "제주",
      "강릉"
    );
    assertThatThrownBy(() -> TravelRegions.require("미지원")).isInstanceOf(
      DomainException.class
    );
  }

  @Test
  void everySupportedRegionRecognizesOfficialProvinceAndItsOwnCity() {
    for (String name : TravelRegions.names(false)) {
      var region = TravelRegions.require(name);
      for (String province : region.provinceNames()) {
        String address =
          province +
          " " +
          (region.city() == null
            ? name.equals("광주")
              ? "동구"
              : "중구"
            : region.city()) +
          " 검증로 1";
        assertThat(KakaoPlaceClassifier.inRegion(name, address))
          .as(address)
          .isTrue();
      }
    }
    assertThat(
      TravelRegions.containsAddress(
        "전주",
        "전북특별자치도 전주시 완산구 검증로 1"
      )
    ).isTrue();
    assertThat(
      TravelRegions.containsAddress("수원", "경기도 수원시 영통구 검증로 1")
    ).isTrue();
  }

  @Test
  void namesAndSubstringsCannotReplaceProvinceAndCityBoundaries() {
    assertThat(
      TravelRegions.containsAddress("광주", "전남광주통합특별시 북구 검증로 1")
    ).isTrue();
    assertThat(
      TravelRegions.containsAddress(
        "광주",
        "전남광주통합특별시 여수시 검증로 1"
      )
    ).isFalse();
    assertThat(
      TravelRegions.containsAddress(
        "광주",
        "전남광주통합특별시 담양군 검증로 1"
      )
    ).isFalse();
    assertThat(
      TravelRegions.containsAddress(
        "여수",
        "전남광주통합특별시 여수시 검증로 1"
      )
    ).isTrue();
    assertThat(
      TravelRegions.containsAddress("여수", "전남광주통합특별시 동구 검증로 1")
    ).isFalse();
    assertThat(
      TravelRegions.containsAddress("광주", "경기도 광주시 경안동 1")
    ).isFalse();
    assertThat(
      TravelRegions.containsAddress("전주", "전남 전주시 검증로 1")
    ).isFalse();
    assertThat(
      TravelRegions.containsAddress("강릉", "강원 속초시 강릉시 카페")
    ).isFalse();
    assertThat(
      TravelRegions.containsAddress("수원", "경기 수원시외부 검증로 1")
    ).isFalse();
    assertThat(
      TravelRegions.containsAddress("제주", "제주상점 서울 종로구 1")
    ).isFalse();
    assertThat(
      TravelRegions.containsAddress("해외", "서울 종로구 1")
    ).isFalse();
    assertThat(TravelRegions.containsAddress("서울", null)).isFalse();
  }
}
