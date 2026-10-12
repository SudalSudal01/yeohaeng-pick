package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.*;
import kr.yeohaengpick.common.JsonCodec;
import kr.yeohaengpick.trip.*;
import org.junit.jupiter.api.Test;

/** 숙소 체류를 이동 분과 분리하고, 날짜 경계·고정 방문·예약 보호가 함께 유지되는지 검증합니다. */
class OvernightScheduleTest {

  final RouteScheduleTest fixture = new RouteScheduleTest();
  final OvernightSchedule engine = new OvernightSchedule(
    fixture.engine,
    fixture.bookings
  );
  final LocalDate date = LocalDate.of(2026, 11, 1);

  List<Map<String, Object>> visits() {
    var a = fixture.visit("1", "19:00", false);
    var b = fixture.visit("2", "20:00", false);
    var c = fixture.visit("3", "08:00", false);
    c.put("dayIndex", 1);
    var d = fixture.visit("4", "09:30", false);
    d.put("dayIndex", 1);
    return List.of(a, b, c, d);
  }

  RouteTiming proposed(int inbound, int outbound, int minimum, String morning) {
    var stay = new RouteTiming.Overnight(
      "hotel",
      "검증 숙소",
      37.5,
      127,
      minimum,
      morning,
      null,
      null,
      new RouteTiming.Leg(inbound, 1000, "2026-10-12T01:00:00Z", "KAKAO_MAP"),
      new RouteTiming.Leg(outbound, 2000, "2026-10-12T01:01:00Z", "KAKAO_MAP")
    );
    return new RouteTiming(
      "ITEM",
      "2",
      "2",
      "3",
      "3",
      1,
      37.5,
      127,
      37.5,
      127,
      "TRANSIT",
      inbound + outbound,
      10,
      3000,
      "2026-10-12T01:00:00Z",
      "KAKAO_MAP",
      "2026-11-01T21:00",
      stay
    );
  }

  List<RouteSchedule.Plan> compare(
    List<Map<String, Object>> items,
    RouteTiming proposed
  ) {
    return engine.compare(
      date,
      items,
      List.of(),
      List.of(),
      proposed,
      "TRANSIT"
    );
  }

  @Test
  void morningLimitMovesOnlyFollowingVisitsAndKeepsTravelMinutesSeparate() {
    var items = visits();
    var plans = compare(items, proposed(30, 20, 480, "08:00"));
    var plan = plans.getFirst();
    assertThat(plan.canApply()).isTrue();
    assertThat(plan.items())
      .extracting(i -> i.get("startTime"))
      .containsExactly("19:00", "20:00", "08:30", "10:00");
    assertThat(plan.timing().minutes()).isEqualTo(50);
    assertThat(plan.timing().overnight().arrival()).isEqualTo(
      "2026-11-01T21:40"
    );
    assertThat(plan.timing().overnight().departure()).isEqualTo(
      "2026-11-02T08:00"
    );
    assertThat(plans.get(1).canApply()).isFalse();
    assertThat(plans.get(1).conflicts()).anyMatch(s -> s.contains("출발 가능"));
    assertThat(items.get(2).get("startTime")).isEqualTo("08:00");
    assertThat(plan.items()).allSatisfy(i -> {
      assertThat(i.get("duration")).isEqualTo(60);
      assertThat(i.get("cost")).isEqualTo(1000);
    });
  }

  @Test
  void firstVisitCanStayAndPreviousVisitsShiftButFixedVisitBlocksIt() {
    var items = visits();
    items.get(1).put("startTime", "21:00");
    var plan = compare(items, proposed(30, 20, 600, "06:00")).get(1);
    assertThat(plan.canApply()).isTrue();
    assertThat(plan.items().get(1).get("startTime")).isEqualTo("19:50");
    assertThat(plan.items().get(2).get("startTime")).isEqualTo("08:00");
    items.get(1).put("fixed", true);
    assertThat(
      compare(items, proposed(30, 20, 600, "06:00"))
        .get(1)
        .canApply()
    ).isFalse();
  }

  @Test
  void arrivalAfterMidnightStillGetsMinimumStayAndNextDayRangeIsProtected() {
    var items = visits();
    items.get(1).put("startTime", "21:00");
    var plan = compare(items, proposed(170, 90, 360, "06:00")).getFirst();
    assertThat(plan.canApply()).isTrue();
    assertThat(plan.timing().overnight().arrival()).isEqualTo(
      "2026-11-02T01:00"
    );
    assertThat(plan.timing().overnight().departure()).isEqualTo(
      "2026-11-02T07:00"
    );
    assertThat(plan.items().get(2).get("startTime")).isEqualTo("08:40");
    assertThat(compare(items, proposed(700, 700, 960, "06:00"))).noneMatch(
      RouteSchedule.Plan::canApply
    );
  }

  @Test
  void bookingDuringStayAndLaterVisitEditsCannotBreakSavedConnection() {
    var items = visits();
    var proposed = proposed(30, 20, 480, "08:00");
    var reservation = new HashMap<String, Object>(
      Map.of(
        "id",
        "train",
        "label",
        "가상 야간편",
        "status",
        "USER_CONFIRMED",
        "scheduleLinked",
        true,
        "departure",
        "2026-11-02T00:00",
        "arrival",
        "2026-11-02T01:00",
        "beforeMinutes",
        0,
        "afterMinutes",
        0
      )
    );
    assertThat(
      engine.compare(
        date,
        items,
        List.of(reservation),
        List.of(),
        proposed,
        "TRANSIT"
      )
    ).noneMatch(RouteSchedule.Plan::canApply);
    var saved = compare(items, proposed).getFirst();
    var changed = saved.items();
    changed.get(2).put("startTime", "08:20");
    assertThat(
      RouteTiming.conflicts(date, changed, List.of(saved.timing()), List.of())
    ).isNotEmpty();
    changed.get(2).put("startTime", "08:30");
    changed.get(1).put("startTime", "21:00");
    assertThat(
      RouteTiming.conflicts(date, changed, List.of(saved.timing()), List.of())
    ).isNotEmpty();
  }

  @Test
  void selectedHotelAndLegacyJsonAreValidatedWithoutSchemaChange() {
    var json = new JsonCodec();
    var old = fixture.timing("ITEM", "1", "2", 10, null);
    var oldPayload = json.mapper().valueToTree(old).deepCopy();
    ((tools.jackson.databind.node.ObjectNode) oldPayload).remove("overnight");
    assertThat(
      json.read(oldPayload.toString(), RouteTiming.class).overnight()
    ).isNull();
    var plan = compare(visits(), proposed(30, 20, 480, "08:00")).getFirst();
    var hotels = List.<Map<String, Object>>of(
      new HashMap<>(
        Map.of("placeId", "hotel", "latitude", 37.5, "longitude", 127.0)
      )
    );
    assertThat(plan.timing().applies(plan.items(), hotels)).isTrue();
    assertThat(plan.timing().applies(plan.items(), List.of())).isFalse();
    hotels.getFirst().put("latitude", 36.0);
    assertThat(plan.timing().applies(plan.items(), hotels)).isFalse();
    assertThat(
      json.read(json.write(plan.timing()), RouteTiming.class)
    ).isEqualTo(plan.timing());
  }

  @Test
  void arrivalStrategyDoesNotDelayHotelArrivalAndProtectsExtraEveningStay() {
    var proposed = proposed(30, 20, 60, "06:00");
    var plan = compare(visits(), proposed).get(1);
    assertThat(plan.canApply()).isTrue();
    assertThat(plan.timing().originDeparture()).isEqualTo("2026-11-01T21:00");
    assertThat(plan.timing().overnight().arrival()).isEqualTo(
      "2026-11-01T21:40"
    );
    var reservation = new HashMap<String, Object>(
      Map.of(
        "id",
        "late",
        "label",
        "가상 저녁편",
        "status",
        "USER_CONFIRMED",
        "scheduleLinked",
        true,
        "departure",
        "2026-11-01T23:00",
        "arrival",
        "2026-11-02T00:00",
        "beforeMinutes",
        0,
        "afterMinutes",
        0
      )
    );
    assertThat(
      engine
        .compare(
          date,
          visits(),
          List.of(reservation),
          List.of(),
          proposed,
          "TRANSIT"
        )
        .get(1)
        .canApply()
    ).isFalse();
  }
}
