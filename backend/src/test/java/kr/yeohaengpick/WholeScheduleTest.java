package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import kr.yeohaengpick.trip.*;
import org.junit.jupiter.api.Test;

/** 전체 일정의 양방향 계산에서 장소·고정 앵커·실제 조회 시간과 예약 경계를 함께 확인합니다. */
class WholeScheduleTest {

  private final Planner planner = new Planner();
  private final WholeSchedule engine = new WholeSchedule(
    planner,
    new BookingSchedule(planner)
  );
  private final LocalDate date = LocalDate.of(2026, 11, 1);
  private final WholeSchedule.Settings settings = new WholeSchedule.Settings(
    480,
    1260,
    20
  );

  private Map<String, Object> item(
    String id,
    int day,
    String time,
    boolean fixed
  ) {
    var item = new HashMap<String, Object>();
    item.putAll(
      Map.of(
        "id",
        id,
        "placeId",
        id,
        "placeName",
        "장소 " + id,
        "region",
        "서울",
        "category",
        "관광",
        "latitude",
        37.5,
        "longitude",
        127.0,
        "cost",
        1000
      )
    );
    item.putAll(
      Map.of(
        "dayIndex",
        day,
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
    return item;
  }

  private Map<String, Object> booking(String from, String to) {
    return new HashMap<>(
      Map.of(
        "id",
        "b",
        "label",
        "예약",
        "departure",
        from,
        "arrival",
        to,
        "beforeMinutes",
        0,
        "afterMinutes",
        0,
        "status",
        "USER_CONFIRMED",
        "scheduleLinked",
        true
      )
    );
  }

  private List<WholeSchedule.Plan> compare(
    List<Map<String, Object>> items,
    List<Map<String, Object>> reserved,
    List<RouteTiming> timings
  ) {
    return engine.compare(
      date,
      items,
      reserved,
      timings,
      List.of(),
      "WALK",
      settings
    );
  }

  @Test
  void bothDirectionsCoverEveryDayAndKeepFixedVisitsWithoutMutatingInput() {
    var items = List.of(
      item("1", 0, "10:00", false),
      item("2", 0, "12:00", true),
      item("3", 0, "15:00", false),
      item("4", 1, "10:00", false)
    );
    var plans = compare(items, List.of(), List.of());
    assertThat(plans).allMatch(WholeSchedule.Plan::canApply);
    assertThat(plans.get(0).items())
      .extracting(i -> i.get("startTime"))
      .containsExactly("08:00", "12:00", "13:25", "08:00");
    assertThat(plans.get(1).items())
      .extracting(i -> i.get("startTime"))
      .containsExactly("10:35", "12:00", "20:00", "20:00");
    assertThat(plans).allSatisfy(p -> {
      assertThat(p.days()).hasSize(2);
      assertThat(p.items())
        .extracting(i -> i.get("cost"))
        .containsOnly(1000);
      assertThat(p.items())
        .extracting(i -> i.get("duration"))
        .containsOnly(60);
      assertThat(p.estimatedLegs()).isEqualTo(2);
      assertThat(p.warnings()).anyMatch(w -> w.contains("날짜 간"));
    });
    assertThat(items.getFirst().get("startTime")).isEqualTo("10:00");
  }

  @Test
  void bookingDuringOutgoingTravelMovesWholeBlockAndExactBoundaryIsAllowed() {
    var items = List.of(
      item("1", 0, "10:00", false),
      item("2", 0, "12:00", false)
    );
    var plan = compare(
      items,
      List.of(booking("2026-11-01T09:10", "2026-11-01T10:00")),
      List.of()
    ).getFirst();
    assertThat(plan.canApply()).isTrue();
    assertThat(plan.items())
      .extracting(i -> i.get("startTime"))
      .containsExactly("10:00", "11:25");
    var boundary = compare(
      items,
      List.of(booking("2026-11-01T09:25", "2026-11-01T10:00")),
      List.of()
    ).getFirst();
    assertThat(boundary.items().getFirst().get("startTime")).isEqualTo("08:00");
    assertThat(boundary.items().getLast().get("startTime")).isEqualTo("10:00");
    assertThat(boundary.conflicts()).isEmpty();
  }

  @Test
  void appliedGapOverridesComparisonBufferAndCannotBeShortened() {
    var items = List.of(
      item("1", 0, "10:00", false),
      item("2", 0, "15:00", false)
    );
    var timing = new RouteTiming(
      "ITEM",
      "1",
      "1",
      "2",
      "2",
      0,
      37.5,
      127,
      37.5,
      127,
      "CAR",
      120,
      15,
      2000,
      "2026-10-12T10:00:00Z",
      "TEST",
      null
    );
    var plans = engine.compare(
      date,
      items,
      List.of(),
      List.of(timing),
      List.of(),
      "WALK",
      new WholeSchedule.Settings(480, 1260, 0)
    );
    assertThat(plans.getFirst().items().getLast().get("startTime")).isEqualTo(
      "11:15"
    );
    assertThat(plans.getLast().items().getFirst().get("startTime")).isEqualTo(
      "16:45"
    );
    assertThat(plans).allMatch(
      p -> p.queriedLegs() == 1 && p.estimatedLegs() == 0 && p.canApply()
    );
  }

  @Test
  void fixedAndImpossibleDayAreBlockedRatherThanSilentlyMoved() {
    var items = List.of(
      item("1", 0, "08:00", true),
      item("2", 0, "09:00", true)
    );
    assertThat(compare(items, List.of(), List.of())).noneMatch(
      WholeSchedule.Plan::canApply
    );
    assertThat(compare(items, List.of(), List.of())).allSatisfy(p ->
      assertThat(p.items()).isEqualTo(items)
    );
    var blocked = engine.compare(
      date,
      List.of(item("1", 0, "10:00", true)),
      List.of(),
      List.of(),
      List.of(),
      "WALK",
      new WholeSchedule.Settings(720, 780, 20)
    );
    assertThat(blocked).noneMatch(WholeSchedule.Plan::canApply);
    assertThat(
      compare(
        List.of(item("1", 0, "12:00", true)),
        List.of(booking("2026-11-01T06:00", "2026-11-01T22:00")),
        List.of()
      )
    ).noneMatch(WholeSchedule.Plan::canApply);
  }

  @Test
  void overnightDepartureArrivalAndLodgingConditionsRemainAnchored() {
    var items = List.of(
      item("1", 0, "16:00", false),
      item("2", 1, "11:00", false)
    );
    var leg = new RouteTiming.Leg(30, 1000, "2026-10-12T10:00:00Z", "TEST");
    var stay = new RouteTiming.Overnight(
      "hotel",
      "숙소",
      37.5,
      127,
      480,
      "10:00",
      "2026-11-01T18:30",
      "2026-11-02T10:00",
      leg,
      leg
    );
    var timing = new RouteTiming(
      "ITEM",
      "1",
      "1",
      "2",
      "2",
      1,
      37.5,
      127,
      37.5,
      127,
      "CAR",
      60,
      20,
      2000,
      "2026-10-12T10:00:00Z",
      "TEST",
      "2026-11-01T18:00",
      stay
    );
    var plans = compare(items, List.of(), List.of(timing));
    assertThat(plans).allMatch(WholeSchedule.Plan::canApply);
    assertThat(plans.getFirst().items().getLast().get("startTime")).isEqualTo(
      "10:50"
    );
    assertThat(plans.getLast().items().getFirst().get("startTime")).isEqualTo(
      "17:00"
    );
    var hotel = new HashMap<String, Object>();
    hotel.putAll(
      Map.of(
        "placeId",
        "hotel",
        "name",
        "숙소",
        "checkInDate",
        "2026-11-01",
        "checkOutDate",
        "2026-11-02",
        "checkOutTime",
        "09:00"
      )
    );
    assertThat(
      engine.compare(
        date,
        items,
        List.of(),
        List.of(timing),
        List.of(hotel),
        "CAR",
        settings
      )
    )
      .noneMatch(WholeSchedule.Plan::canApply)
      .allSatisfy(p ->
        assertThat(p.conflicts()).anyMatch(c -> c.contains("체크아웃"))
      );
    assertThat(timing.overnight()).isSameAs(stay);
  }

  @Test
  void requestRejectsInvalidTimesFractionsAndMissingBufferAndNoChangeIsNotApplied() {
    for (var request : List.of(
      new WholeSchedule.Request(0, "05:00", "21:00", BigDecimal.TEN, null),
      new WholeSchedule.Request(0, "21:00", "09:00", BigDecimal.TEN, null),
      new WholeSchedule.Request(
        0,
        "09:00",
        "21:00",
        new BigDecimal("1.5"),
        null
      ),
      new WholeSchedule.Request(0, "09:00", "21:00", null, null)
    ))
      assertThatThrownBy(() -> engine.validate(request)).isInstanceOf(
        kr.yeohaengpick.common.DomainException.class
      );
    var same = compare(
      List.of(item("1", 0, "08:00", false)),
      List.of(),
      List.of()
    ).getFirst();
    assertThat(same.canApply()).isFalse();
    assertThat(same.conflicts()).isEmpty();
  }
}
