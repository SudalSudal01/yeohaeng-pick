package kr.yeohaengpick.trip;

import static kr.yeohaengpick.trip.RouteTiming.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * 조회한 한 구간을 기준으로 출발 방문 유지/도착 방문 유지 두 안을 계산합니다.
 * 방문 순서·날짜·체류·활동비를 유지하고, 다른 구간에 기존 대기 시간이 있으면 보존합니다.
 * 조회하지 않은 구간은 거리 추정이므로 결과를 여행 날짜의 운행 보장으로 해석하면 안 됩니다.
 */
@Component
public class RouteSchedule {

  private final Planner planner;
  private final BookingSchedule bookings;

  public RouteSchedule(Planner planner, BookingSchedule bookings) {
    this.planner = planner;
    this.bookings = bookings;
  }

  public record Plan(
    String strategy,
    String title,
    boolean canApply,
    String departure,
    String arrival,
    List<BookingSchedule.Shift> changes,
    List<String> conflicts,
    List<Map<String, Object>> items,
    RouteTiming timing
  ) {}

  public List<Plan> compare(
    LocalDate date,
    List<Map<String, Object>> original,
    List<Map<String, Object>> reserved,
    List<RouteTiming> existing,
    RouteTiming proposed,
    String mode
  ) {
    return List.of(
      plan(date, original, reserved, existing, proposed, mode, "DEPARTURE"),
      plan(date, original, reserved, existing, proposed, mode, "ARRIVAL")
    );
  }

  Plan plan(
    LocalDate date,
    List<Map<String, Object>> original,
    List<Map<String, Object>> reserved,
    List<RouteTiming> existing,
    RouteTiming proposed,
    String mode,
    String strategy
  ) {
    if (proposed.crossesDays(original)) return crossDayPlan(
      date,
      original,
      reserved,
      existing,
      proposed,
      mode,
      strategy
    );
    List<Map<String, Object>> items = new ArrayList<>();
    original.forEach(i -> items.add(new HashMap<>(i)));
    var day = items
      .stream()
      .filter(i -> number(i, "dayIndex") == proposed.dayIndex())
      .toList();
    var old = original
      .stream()
      .filter(i -> number(i, "dayIndex") == proposed.dayIndex())
      .toList();
    int target = 0;
    while (!day.get(target).get("id").equals(proposed.toItemId())) target++;
    List<RouteTiming> timings = new ArrayList<>(
      existing
        .stream()
        .filter(t -> !t.toItemId().equals(proposed.toItemId()))
        .toList()
    );
    List<String> conflicts = new ArrayList<>();
    int gap = proposed.minutes() + proposed.bufferMinutes();
    int departure;
    if (proposed.fromKind().equals("LODGING")) {
      departure = strategy.equals("ARRIVAL")
        ? time(day.get(target)) - gap
        : clock(proposed.originDeparture());
      if (departure < 6 * 60 || departure >= 22 * 60) conflicts.add(
        "숙소 출발 시간을 같은 날 06:00~22:00 안에 잡을 수 없어요."
      );
      if (strategy.equals("DEPARTURE")) {
        move(day.get(target), departure + gap, true, conflicts);
        forward(day, old, target + 1, existing, mode, conflicts);
      }
    } else if (strategy.equals("DEPARTURE")) {
      departure =
        time(day.get(target - 1)) + number(day.get(target - 1), "duration");
      int wait = Math.max(
        0,
        time(old.get(target)) -
          time(old.get(target - 1)) -
          number(old.get(target - 1), "duration") -
          RouteTiming.gap(
            existing,
            old.get(target - 1),
            old.get(target),
            planner,
            mode
          )
      );
      move(day.get(target), departure + gap + wait, true, conflicts);
      forward(day, old, target + 1, existing, mode, conflicts);
    } else {
      int desired =
        time(day.get(target)) - gap - number(day.get(target - 1), "duration");
      move(day.get(target - 1), desired, false, conflicts);
      for (int n = target - 2; n >= 0; n--) {
        int oldGap = RouteTiming.gap(
          existing,
          old.get(n),
          old.get(n + 1),
          planner,
          mode
        );
        int wait = Math.max(
          0,
          time(old.get(n + 1)) -
            time(old.get(n)) -
            number(old.get(n), "duration") -
            oldGap
        );
        move(
          day.get(n),
          time(day.get(n + 1)) - number(day.get(n), "duration") - oldGap - wait,
          false,
          conflicts
        );
      }
      departure =
        time(day.get(target - 1)) + number(day.get(target - 1), "duration");
    }
    var saved = new RouteTiming(
      proposed.fromKind(),
      proposed.fromId(),
      proposed.fromPlaceId(),
      proposed.toItemId(),
      proposed.toPlaceId(),
      proposed.dayIndex(),
      proposed.fromLatitude(),
      proposed.fromLongitude(),
      proposed.toLatitude(),
      proposed.toLongitude(),
      proposed.mode(),
      proposed.minutes(),
      proposed.bufferMinutes(),
      proposed.distanceMeters(),
      proposed.queriedAt(),
      proposed.source(),
      proposed.fromKind().equals("LODGING") ? clock(departure) : null
    );
    timings.add(saved);
    // 조정 범위 밖의 고정 예약과 기존 적용 구간도 마지막에 함께 검증합니다.
    var report = bookings.inspect(date, items, reserved);
    report.issues().forEach(i -> conflicts.add(i.message()));
    conflicts.addAll(
      RouteTiming.conflicts(date, items, timings, report.windows())
    );
    for (var i : day)
      if (
        time(i) < 6 * 60 || time(i) + number(i, "duration") > 22 * 60
      ) conflicts.add(
        i.get("placeName") + "을 같은 날 06:00~22:00 안에 배치할 수 없어요."
      );
    List<BookingSchedule.Shift> changes = new ArrayList<>();
    for (int n = 0; n < day.size(); n++) if (
      !day.get(n).get("startTime").equals(old.get(n).get("startTime"))
    ) changes.add(
      new BookingSchedule.Shift(
        day.get(n).get("id").toString(),
        day.get(n).get("placeName").toString(),
        proposed.dayIndex(),
        old.get(n).get("startTime").toString(),
        day.get(n).get("startTime").toString()
      )
    );
    return new Plan(
      strategy,
      strategy.equals("DEPARTURE") ? "출발 일정 유지" : "도착 시간 유지",
      conflicts.isEmpty(),
      displayTime(departure),
      displayTime(departure + gap),
      changes,
      conflicts.stream().distinct().toList(),
      items,
      saved
    );
  }

  /**
   * 전날 마지막→다음 날 첫 방문에서는 휴식·숙박 시간을 이동 시간으로 세지 않습니다.
   * 입력한 출발을 지키는 안과 다음 날 방문을 지키는 안을 각각 계산하며 이동 자체는 자정을 넘을 수 있습니다.
   * 방문 날짜·체류·순서를 유지하고 양일의 06~22시 범위와 기존 고정 예약을 마지막에 함께 검사합니다.
   */
  private Plan crossDayPlan(
    LocalDate date,
    List<Map<String, Object>> original,
    List<Map<String, Object>> reserved,
    List<RouteTiming> existing,
    RouteTiming proposed,
    String mode,
    String strategy
  ) {
    var items = new ArrayList<Map<String, Object>>();
    original.forEach(i -> items.add(new HashMap<>(i)));
    int toDay = proposed.dayIndex();
    var before = items
      .stream()
      .filter(i -> number(i, "dayIndex") == toDay - 1)
      .toList();
    var after = items
      .stream()
      .filter(i -> number(i, "dayIndex") == toDay)
      .toList();
    var oldBefore = original
      .stream()
      .filter(i -> number(i, "dayIndex") == toDay - 1)
      .toList();
    var oldAfter = original
      .stream()
      .filter(i -> number(i, "dayIndex") == toDay)
      .toList();
    var sourceDate = date.plusDays(toDay - 1);
    var targetDate = date.plusDays(toDay);
    int gap = proposed.minutes() + proposed.bufferMinutes();
    var conflicts = new ArrayList<String>();
    LocalDateTime departure;
    LocalDateTime arrival;
    if (strategy.equals("DEPARTURE")) {
      departure = LocalDateTime.parse(proposed.originDeparture());
      arrival = departure.plusMinutes(gap);
      if (arrival.toLocalDate().isAfter(targetDate)) conflicts.add(
        "이동이 다음 날 방문일보다 늦게 끝나요."
      );
      else if (
        arrival.toLocalDate().equals(targetDate) &&
        arrival.toLocalTime().toSecondOfDay() / 60 > time(after.getFirst())
      ) {
        move(
          after.getFirst(),
          arrival.toLocalTime().toSecondOfDay() / 60,
          true,
          conflicts
        );
        forward(after, oldAfter, 1, existing, mode, conflicts);
      }
    } else {
      arrival = targetDate.atStartOfDay().plusMinutes(time(after.getFirst()));
      departure = arrival.minusMinutes(gap);
      if (departure.toLocalDate().isBefore(sourceDate)) conflicts.add(
        "전날 방문일보다 먼저 출발해야 해서 적용할 수 없어요."
      );
      else if (departure.toLocalDate().equals(sourceDate)) {
        var last = before.getLast();
        int desired =
          departure.toLocalTime().toSecondOfDay() / 60 -
          number(last, "duration");
        if (desired < time(last)) {
          move(last, desired, false, conflicts);
          for (int n = before.size() - 2; n >= 0; n--) {
            int oldGap = RouteTiming.gap(
              existing,
              oldBefore.get(n),
              oldBefore.get(n + 1),
              planner,
              mode
            );
            int wait = Math.max(
              0,
              time(oldBefore.get(n + 1)) -
                time(oldBefore.get(n)) -
                number(oldBefore.get(n), "duration") -
                oldGap
            );
            move(
              before.get(n),
              Math.min(
                time(before.get(n)),
                time(before.get(n + 1)) -
                  number(before.get(n), "duration") -
                  oldGap -
                  wait
              ),
              false,
              conflicts
            );
          }
        }
      }
    }
    var saved = new RouteTiming(
      proposed.fromKind(),
      proposed.fromId(),
      proposed.fromPlaceId(),
      proposed.toItemId(),
      proposed.toPlaceId(),
      proposed.dayIndex(),
      proposed.fromLatitude(),
      proposed.fromLongitude(),
      proposed.toLatitude(),
      proposed.toLongitude(),
      proposed.mode(),
      proposed.minutes(),
      proposed.bufferMinutes(),
      proposed.distanceMeters(),
      proposed.queriedAt(),
      proposed.source(),
      departure.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
    );
    var timings = new ArrayList<>(
      existing
        .stream()
        .filter(t -> !t.toItemId().equals(proposed.toItemId()))
        .toList()
    );
    timings.add(saved);
    var report = bookings.inspect(date, items, reserved);
    report.issues().forEach(i -> conflicts.add(i.message()));
    conflicts.addAll(
      RouteTiming.conflicts(date, items, timings, report.windows())
    );
    for (var i : items)
      if (
        number(i, "dayIndex") == toDay || number(i, "dayIndex") == toDay - 1
      ) {
        if (
          time(i) < 360 || time(i) + number(i, "duration") > 1320
        ) conflicts.add(
          i.get("placeName") + "을 방문일의 06:00~22:00 안에 배치할 수 없어요."
        );
      }
    var changes = new ArrayList<BookingSchedule.Shift>();
    for (int n = 0; n < items.size(); n++) if (
      !items.get(n).get("startTime").equals(original.get(n).get("startTime"))
    ) {
      var i = items.get(n);
      var old = original.get(n);
      changes.add(
        new BookingSchedule.Shift(
          i.get("id").toString(),
          i.get("placeName").toString(),
          number(i, "dayIndex"),
          old.get("startTime").toString(),
          i.get("startTime").toString()
        )
      );
    }
    return new Plan(
      strategy,
      strategy.equals("DEPARTURE") ? "이동 출발 유지" : "다음 날 도착 유지",
      conflicts.isEmpty(),
      dayTime(date, departure),
      dayTime(date, arrival),
      changes,
      conflicts.stream().distinct().toList(),
      items,
      saved
    );
  }

  private String dayTime(LocalDate start, LocalDateTime time) {
    long day =
      java.time.temporal.ChronoUnit.DAYS.between(start, time.toLocalDate()) + 1;
    return day < 1
      ? "여행일 이전"
      : day + "일차 " + time.format(DateTimeFormatter.ofPattern("HH:mm"));
  }

  /** 실패한 안도 화면에 표시합니다. 전날/다음 날로 벗어난 값을 음수 시각처럼 보이지 않게 안내합니다. */
  private String displayTime(int minutes) {
    return minutes < 0 || minutes >= 24 * 60 ? "하루 범위 밖" : clock(minutes);
  }

  private void forward(
    List<Map<String, Object>> day,
    List<Map<String, Object>> old,
    int first,
    List<RouteTiming> existing,
    String mode,
    List<String> conflicts
  ) {
    for (int n = first; n < day.size(); n++) {
      int gap = RouteTiming.gap(
        existing,
        old.get(n - 1),
        old.get(n),
        planner,
        mode
      );
      int wait = Math.max(
        0,
        time(old.get(n)) -
          time(old.get(n - 1)) -
          number(old.get(n - 1), "duration") -
          gap
      );
      move(
        day.get(n),
        time(day.get(n - 1)) + number(day.get(n - 1), "duration") + gap + wait,
        true,
        conflicts
      );
    }
  }

  /** 고정 방문은 대기 시간으로 흡수할 수 있을 때만 유지하고, 늦은 도착/이른 출발 요구는 충돌로 돌려줍니다. */
  private void move(
    Map<String, Object> item,
    int desired,
    boolean forward,
    List<String> conflicts
  ) {
    if (Boolean.TRUE.equals(item.get("fixed"))) {
      if (forward ? desired > time(item) : desired < time(item)) conflicts.add(
        item.get("placeName") + "의 고정 시간은 옮길 수 없어요."
      );
    } else if (desired < 0 || desired >= 24 * 60) conflicts.add(
      item.get("placeName") + "을 같은 날에 배치할 수 없어요."
    );
    else item.put("startTime", clock(desired));
  }
}
