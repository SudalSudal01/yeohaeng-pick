package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;

import kr.yeohaengpick.catalog.KakaoPlaceClassifier;
import org.junit.jupiter.api.Test;

/** 일반 장소가 관광지로 섞이거나 인접 도시를 요청 지역으로 잘못 저장하는 회귀를 방지합니다. */
class CatalogClassificationTest {

  @Test
  void unrelatedPlacesAndConventionCentersAreNotExhibitions() {
    assertThat(
      KakaoPlaceClassifier.category("HP8", "의료,건강 > 병원")
    ).isNull();
    assertThat(
      KakaoPlaceClassifier.category("PK6", "교통,수송 > 주차장")
    ).isNull();
    assertThat(
      KakaoPlaceClassifier.category("CT1", "문화,예술 > 컨벤션센터")
    ).isNull();
    assertThat(
      KakaoPlaceClassifier.category("CT1", "문화,예술 > 박물관")
    ).isEqualTo("전시");
    assertThat(
      KakaoPlaceClassifier.category("AT4", "여행 > 관광,명소 > 수목원")
    ).isEqualTo("자연");
    assertThat(KakaoPlaceClassifier.category("", "가정,생활 > 공방")).isEqualTo(
      "체험"
    );
    assertThat(KakaoPlaceClassifier.category("FD6", "음식점 > 한식")).isEqualTo(
      "맛집"
    );
  }

  @Test
  void requestedRegionMustMatchActualAddress() {
    assertThat(
      KakaoPlaceClassifier.category("", "여행 > 공원 > 도시근린공원")
    ).isEqualTo("자연");
    assertThat(
      KakaoPlaceClassifier.inRegion("서울", "경기 고양시 덕양구")
    ).isFalse();
    assertThat(
      KakaoPlaceClassifier.inRegion("서울", "서울 강남구 테헤란로")
    ).isTrue();
    assertThat(
      KakaoPlaceClassifier.inRegion("강릉", "강원특별자치도 강릉시 초당동")
    ).isTrue();
    assertThat(
      KakaoPlaceClassifier.inRegion("강릉", "강원특별자치도 속초시 중앙동")
    ).isFalse();
  }
}
