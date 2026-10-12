package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.*;
import kr.yeohaengpick.trip.*;
import org.junit.jupiter.api.Test;

/** 실제 일정에서 문제가 되는 경계·자정·고정 예약을 작은 데이터로 검증합니다. 외부 경로 API는 호출하지 않습니다. */
class BookingScheduleTest {

  private final BookingSchedule engine = new BookingSchedule(new Planner());
  private final LocalDate date = LocalDate.of(2026, 11, 1);

  private Map<String, Object> visit(
    String id,
    int day,
    String time,
    boolean fixed
  ) {
    var m = new HashMap<String, Object>();
    m.putAll(
      Map.of(
        "id",
        id,
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
    m.putAll(
      Map.of(
        "placeId",
        id,
        "placeName",
        "방문 " + id,
        "region",
        "서울",
        "category",
        "관광",
        "latitude",
        37.5,
        "longitude",
        127.0,
        "cost",
        1000,
        "source",
        "SAMPLE",
        "placeUrl",
        ""
      )
    );
    return m;
  }

  private Map<String, Object> booking(String id, String from, String to) {
    return new HashMap<>(
      Map.of(
        "id",
        id,
        "label",
        id,
        "departure",
        from,
        "arrival",
        to,
        "beforeMinutes",
        30,
        "afterMinutes",
        30,
        "scheduleLinked",
        true,
        "status",
        "USER_CONFIRMED"
      )
    );
  }

  @Test
  void halfOpenBoundariesAndCancelledOrUnlinkedBookingsDoNotBlock() {
    var b = booking("열차", "2026-11-01T12:00", "2026-11-01T13:00");
    assertThat(
      engine
        .inspect(
          date,
          List.of(visit("1", 0, "10:30", false), visit("2", 0, "13:30", false)),
          List.of(b)
        )
        .issues()
    ).isEmpty();
    assertThat(
      engine
        .inspect(date, List.of(visit("1", 0, "11:00", false)), List.of(b))
        .issues()
    ).hasSize(1);
    b.put("status", "CANCELLED");
    assertThat(
      engine
        .inspect(date, List.of(visit("1", 0, "12:00", false)), List.of(b))
        .windows()
    ).isEmpty();
    b.put("status", "USER_CONFIRMED");
    b.put("scheduleLinked", false);
    assertThat(
      engine.inspect(date, List.of(), List.of(b)).unlinkedCount()
    ).isEqualTo(1);
  }

  @Test
  void overnightJourneyAndBookingOverlapAreCheckedAcrossDates() {
    var b = booking("야간버스", "2026-11-01T23:00", "2026-11-02T07:00");
    var c = booking("다음열차", "2026-11-02T07:20", "2026-11-02T08:00");
    var report = engine.inspect(
      date,
      List.of(visit("1", 1, "06:30", false)),
      List.of(b, c)
    );
    assertThat(report.issues()).anyMatch(i ->
      i.kind().equals("BOOKING_OVERLAP")
    );
    assertThat(report.issues()).anyMatch(
      i ->
        i.kind().equals("VISIT_OVERLAP") && i.bookingIds().contains("야간버스")
    );
  }

  @Test
  void bothAlternativesKeepOrderDurationAndFixedVisitsAndNeverMutateInput() {
    var visits = List.of(
      visit("1", 0, "10:00", false),
      visit("2", 0, "12:00", false),
      visit("3", 0, "17:00", true)
    );
    var bookings = List.of(
      booking("열차", "2026-11-01T12:00", "2026-11-01T13:00")
    );
    var plans = engine.alternatives(date, visits, bookings, "WALK");
    assertThat(plans).allMatch(BookingSchedule.Plan::canApply);
    assertThat(plans.get(0).items().get(1).get("startTime")).isEqualTo("10:30");
    assertThat(plans.get(1).items().get(1).get("startTime")).isEqualTo("13:30");
    assertThat(plans).allSatisfy(p -> {
      assertThat(p.items().get(2).get("startTime")).isEqualTo("17:00");
      assertThat(engine.inspect(date, p.items(), bookings).issues()).isEmpty();
      assertThat(p.items())
        .extracting(i -> i.get("duration"))
        .containsExactly(60, 60, 60);
    });
    assertThat(visits.get(1).get("startTime")).isEqualTo("12:00");
  }

  @Test
  void overlappingFixedVisitOrWholeDayJourneyCannotBeSilentlyMoved() {
    var bookings = List.of(
      booking("열차", "2026-11-01T12:00", "2026-11-01T13:00")
    );
    assertThat(
      engine.alternatives(
        date,
        List.of(visit("1", 0, "12:00", true)),
        bookings,
        "TRANSIT"
      )
    ).noneMatch(BookingSchedule.Plan::canApply);
    assertThat(
      engine.alternatives(
        date,
        List.of(visit("1", 0, "12:00", false)),
        List.of(booking("종일", "2026-11-01T06:00", "2026-11-01T22:00")),
        "CAR"
      )
    ).noneMatch(BookingSchedule.Plan::canApply);
  }
}
