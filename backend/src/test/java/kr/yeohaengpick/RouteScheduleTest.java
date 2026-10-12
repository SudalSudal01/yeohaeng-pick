package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.*;
import kr.yeohaengpick.trip.*;
import org.junit.jupiter.api.Test;

/** 조회 시간을 바꾸는 두 안의 방향, 고정 방문, 예약 사이 이동, 숙소·하루 경계를 검증합니다. */
class RouteScheduleTest {

  final Planner planner = new Planner();
  final BookingSchedule bookings = new BookingSchedule(planner);
  final RouteSchedule engine = new RouteSchedule(planner, bookings);
  final LocalDate date = LocalDate.of(2026, 11, 1);

  Map<String, Object> visit(String id, String time, boolean fixed) {
    var m = new HashMap<String, Object>();
    m.putAll(
      Map.of(
        "id",
        id,
        "placeId",
        id,
        "placeName",
        "방문 " + id,
        "dayIndex",
        0,
        "sortOrder",
        Integer.parseInt(id),
        "startTime",
        time,
        "duration",
        60,
        "fixed",
        fixed
      )
    );
    m.putAll(
      Map.of(
        "region",
        "서울",
        "latitude",
        37.5,
        "longitude",
        127.0,
        "category",
        "관광",
        "cost",
        1000
      )
    );
    return m;
  }

  RouteTiming timing(
    String kind,
    String from,
    String to,
    int minutes,
    String departure
  ) {
    return new RouteTiming(
      kind,
      from,
      from,
      to,
      to,
      0,
      37.5,
      127,
      37.5,
      127,
      "WALK",
      minutes,
      20,
      1000,
      "2026-10-12T01:00:00Z",
      "KAKAO_MAP",
      departure
    );
  }

  List<Map<String, Object>> visits() {
    return List.of(
      visit("1", "10:00", false),
      visit("2", "11:20", false),
      visit("3", "12:40", false)
    );
  }

  @Test
  void twoDirectionsPreserveOrderDurationCostAndDoNotMutateInput() {
    var original = visits();
    var plans = engine.compare(
      date,
      original,
      List.of(),
      List.of(),
      timing("ITEM", "1", "2", 60, null),
      "WALK"
    );
    assertThat(plans).allMatch(RouteSchedule.Plan::canApply);
    assertThat(plans.get(0).items())
      .extracting(i -> i.get("startTime"))
      .containsExactly("10:00", "12:20", "13:45");
    assertThat(plans.get(1).items())
      .extracting(i -> i.get("startTime"))
      .containsExactly("09:00", "11:20", "12:40");
    assertThat(original)
      .extracting(i -> i.get("startTime"))
      .containsExactly("10:00", "11:20", "12:40");
    assertThat(plans).allSatisfy(p ->
      assertThat(p.items()).allSatisfy(i -> {
        assertThat(i.get("duration")).isEqualTo(60);
        assertThat(i.get("cost")).isEqualTo(1000);
      })
    );
  }

  @Test
  void fixedVisitsWaitWhenPossibleAndBlockWhenNotPossible() {
    var original = List.of(
      visit("1", "10:00", true),
      visit("2", "11:20", true)
    );
    var longPlans = engine.compare(
      date,
      original,
      List.of(),
      List.of(),
      timing("ITEM", "1", "2", 60, null),
      "WALK"
    );
    assertThat(longPlans).noneMatch(RouteSchedule.Plan::canApply);
    var shortPlans = engine.compare(
      date,
      original,
      List.of(),
      List.of(),
      timing("ITEM", "1", "2", 0, null),
      "WALK"
    );
    assertThat(shortPlans).allMatch(RouteSchedule.Plan::canApply);
    assertThat(shortPlans).allSatisfy(p -> assertThat(p.changes()).isEmpty());
  }

  @Test
  void movementAcrossBookingBlocksEvenWhenBothVisitsAreOutside() {
    var original = List.of(
      visit("1", "10:00", false),
      visit("2", "13:00", false)
    );
    var booking = new HashMap<String, Object>(
      Map.of(
        "id",
        "train",
        "label",
        "열차",
        "status",
        "USER_CONFIRMED",
        "scheduleLinked",
        true,
        "departure",
        "2026-11-01T11:30",
        "arrival",
        "2026-11-01T12:00",
        "beforeMinutes",
        0,
        "afterMinutes",
        0
      )
    );
    var plans = engine.compare(
      date,
      original,
      List.of(booking),
      List.of(),
      timing("ITEM", "1", "2", 100, null),
      "WALK"
    );
    assertThat(
      bookings.inspect(date, original, List.of(booking)).issues()
    ).isEmpty();
    assertThat(plans).noneMatch(RouteSchedule.Plan::canApply);
    assertThat(plans.get(0).conflicts().toString()).contains("이동·여유 시간");
  }

  @Test
  void lodgingArrivalStrategyComputesDepartureAndRejectsEarlyStart() {
    var original = List.of(
      visit("1", "10:00", false),
      visit("2", "11:20", false)
    );
    var plans = engine.compare(
      date,
      original,
      List.of(),
      List.of(),
      timing("LODGING", "hotel", "1", 60, "09:00"),
      "WALK"
    );
    assertThat(plans).allMatch(RouteSchedule.Plan::canApply);
    assertThat(plans.get(0).arrival()).isEqualTo("10:20");
    assertThat(plans.get(1).departure()).isEqualTo("08:40");
    assertThat(plans.get(1).changes()).isEmpty();
    assertThat(plans.get(1).timing().originDeparture()).isEqualTo("08:40");
    assertThat(
      engine
        .compare(
          date,
          original,
          List.of(),
          List.of(),
          timing("LODGING", "hotel", "1", 900, "09:00"),
          "WALK"
        )
        .get(1)
        .departure()
    ).isEqualTo("하루 범위 밖");
    assertThat(
      engine
        .compare(
          date,
          original,
          List.of(),
          List.of(),
          timing("LODGING", "hotel", "1", 300, "09:00"),
          "WALK"
        )
        .get(1)
        .canApply()
    ).isFalse();
  }

  @Test
  void existingRouteGapIsPreservedAndPlaceReplacementInvalidatesIt() {
    var original = visits();
    var existing = timing("ITEM", "2", "3", 30, null);
    var plan = engine
      .compare(
        date,
        original,
        List.of(),
        List.of(existing),
        timing("ITEM", "1", "2", 60, null),
        "WALK"
      )
      .get(0);
    assertThat(plan.canApply()).isTrue();
    assertThat(plan.items().get(2).get("startTime")).isEqualTo("14:10");
    assertThat(existing.applies(original, List.of())).isTrue();
    var changed = new ArrayList<>(original);
    changed.set(2, new HashMap<>(original.get(2)));
    changed.get(2).put("placeId", "changed");
    assertThat(existing.applies(changed, List.of())).isFalse();
  }

  @Test
  void lodgingAndItemAdjacencyAndDayBoundariesAreRequired() {
    var original = visits();
    assertThat(
      timing("ITEM", "1", "3", 10, null).applies(original, List.of())
    ).isFalse();
    var hotel = Map.<String, Object>of(
      "placeId",
      "hotel",
      "region",
      "서울",
      "latitude",
      37.5,
      "longitude",
      127.0
    );
    assertThat(
      timing("LODGING", "hotel", "1", 10, "09:00").applies(
        original,
        List.of(hotel)
      )
    ).isTrue();
    assertThat(
      timing("LODGING", "hotel", "2", 10, "09:00").applies(
        original,
        List.of(hotel)
      )
    ).isFalse();
    assertThat(
      engine.compare(
        date,
        original,
        List.of(),
        List.of(),
        timing("ITEM", "1", "2", 700, null),
        "WALK"
      )
    ).noneMatch(RouteSchedule.Plan::canApply);
  }
}
