package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.*;
import kr.yeohaengpick.catalog.*;
import kr.yeohaengpick.common.*;
import kr.yeohaengpick.trip.*;
import org.junit.jupiter.api.Test;

/** 여행 엔진의 날짜 제한·장소 중복·멀티 지역 배분을 외부 API와 무관하게 검증합니다. */
class PlannerTest {

  final Planner planner = new Planner();
  final List<Place> places = new CatalogService(new JsonCodec()).examples();

  TripRequest request(List<String> regions, int days) {
    return new TripRequest(
      "엔진 검증",
      regions,
      LocalDate.of(2026, 11, 1),
      LocalDate.of(2026, 11, 1).plusDays(days - 1),
      "서울역",
      2,
      300000,
      "TRANSIT",
      List.of("전시"),
      "",
      List.of()
    );
  }

  @Test
  void threeCoursesContainUniquePlacesAndRespectDayRegions() {
    var courses = planner.build(
      request(List.of("서울", "부산"), 2),
      places,
      List.of(),
      "RULES"
    );
    assertThat(courses).hasSize(3);
    for (var c : courses) {
      assertThat(
        c
          .items()
          .stream()
          .map(s -> s.place().id())
      ).doesNotHaveDuplicates();
      assertThat(
        c
          .items()
          .stream()
          .filter(s -> s.dayIndex() == 0)
          .map(s -> s.place().region())
      ).containsOnly("서울");
      assertThat(
        c
          .items()
          .stream()
          .filter(s -> s.dayIndex() == 1)
          .map(s -> s.place().region())
      ).containsOnly("부산");
    }
  }

  @Test
  void impossibleDurationAndTooManyRegionsAreRejected() {
    assertThatThrownBy(() ->
      planner.validate(request(List.of("서울"), 15))
    ).isInstanceOf(DomainException.class);
    assertThatThrownBy(() ->
      planner.validate(request(List.of("서울", "부산"), 1))
    ).isInstanceOf(DomainException.class);
  }

  @Test
  void walkingDoesNotClaimToBeFasterThanDriving() {
    assertThat(
      planner.estimateMinutes(places.get(0), places.get(5), "WALK")
    ).isGreaterThanOrEqualTo(
      planner.estimateMinutes(places.get(0), places.get(5), "CAR")
    );
  }
}
