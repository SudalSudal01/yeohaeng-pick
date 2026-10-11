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

  @Test
  void selectionPrefersNearbyCategoryBeforeOrderingTheRoute() {
    List<Place> candidates = new ArrayList<>();
    for (String category : List.of("관광", "맛집", "카페", "전시"))
      for (int i = 0; i < 3; i++) candidates.add(
        new Place(
          category + i,
          category + i,
          "서울",
          category,
          37.55 + i * 0.001,
          126.98,
          60,
          0,
          true,
          "KAKAO",
          "https://example.com"
        )
      );
    Place far = new Place(
      "far",
      "멀리 있는 카페",
      "서울",
      "카페",
      37.65,
      127.15,
      60,
      0,
      true,
      "KAKAO",
      "https://example.com"
    );
    candidates.add(far);
    for (var course : planner.build(
      request(List.of("서울"), 1),
      candidates,
      List.of("far"),
      "GEMINI"
    ))
      assertThat(
        course
          .items()
          .stream()
          .map(s -> s.place().id())
      ).doesNotContain("far");
  }

  @Test
  void skippedStopDoesNotAddTravelTimeToNextAcceptedStop() {
    List<Place> candidates = List.of(
      new Place(
        "tour",
        "긴 방문",
        "서울",
        "관광",
        37.55,
        126.98,
        600,
        0,
        false,
        "SAMPLE",
        ""
      ),
      new Place(
        "meal",
        "늦은 식사",
        "서울",
        "맛집",
        37.55,
        126.98,
        120,
        0,
        true,
        "SAMPLE",
        ""
      ),
      new Place(
        "cafe",
        "짧은 방문",
        "서울",
        "카페",
        37.55,
        126.98,
        10,
        0,
        true,
        "SAMPLE",
        ""
      )
    );
    var course = planner
      .build(request(List.of("서울"), 1), candidates, List.of(), "RULES")
      .getFirst();
    assertThat(
      course
        .items()
        .stream()
        .map(s -> s.place().id())
    ).containsExactly("tour", "cafe");
    assertThat(course.items().getLast().startTime()).isEqualTo("20:30");
    assertThat(course.items().getLast().sortOrder()).isEqualTo(1);
  }

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

  @Test
  void equalPriceLiveCandidatesProduceDifferentVisitSets() {
    // Kakao처럼 같은 분류의 비용이 동일한 실제 검색 조건을 재현합니다.
    // 이름·순서가 아니라 방문 장소의 집합이 달라야 비교 가능한 코스입니다.
    List<Place> candidates = new ArrayList<>();
    for (String category : List.of("관광", "맛집", "카페", "전시")) {
      for (int i = 0; i < 2; i++) candidates.add(
        new Place(
          category + i,
          "검증 후보 " + category + i,
          "서울",
          category,
          37.55,
          126.98,
          60,
          category.equals("맛집") ? 18000 : category.equals("카페") ? 7000 : 0,
          !category.equals("관광"),
          "KAKAO",
          "https://example.com/place"
        )
      );
    }
    var courses = planner.build(
      request(List.of("서울"), 1),
      candidates,
      candidates.stream().map(Place::id).toList(),
      "GEMINI"
    );
    assertThat(courses).hasSize(3);
    assertThat(
      courses.stream().map(c ->
        new HashSet<>(
          c
            .items()
            .stream()
            .map(s -> s.place().id())
            .toList()
        )
      )
    ).doesNotHaveDuplicates();
    for (var course : courses) {
      assertThat(
        course
          .items()
          .stream()
          .map(s -> s.place().category())
      ).contains("관광", "맛집", "카페", "전시");
      assertThat(
        course
          .items()
          .stream()
          .map(s -> s.place().id())
      )
        .doesNotHaveDuplicates()
        .isSubsetOf(candidates.stream().map(Place::id).toList());
      assertThat(course.subtitle()).doesNotContain("유료");
    }
  }

  @Test
  void scarceCandidatesAreDisclosedWithoutInventingPlaces() {
    Place only = places.getFirst();
    var courses = planner.build(
      request(List.of("서울"), 1),
      List.of(only),
      List.of(only.id()),
      "GEMINI"
    );
    for (var course : courses) {
      assertThat(course.items()).hasSize(1);
      assertThat(course.items().getFirst().place().id()).isEqualTo(only.id());
      assertThat(course.warnings()).anyMatch(w ->
        w.contains("방문 구성이 같아요")
      );
    }
  }
}
