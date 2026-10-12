package kr.yeohaengpick.trip;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * 직접 확인한 교통 예약과 방문 시간의 충돌을 계산하는 순수 엔진입니다.
 * 외부 시간표·좌석을 조회하거나 예약을 변경하지 않습니다. 여유 시간은 사용자의 입력이며,
 * 방문 장소 사이의 이동은 기존 거리 기반 추정입니다. 계산 결과가 실제 탑승을 보증하지는 않습니다.
 */
@Component
public class BookingSchedule {

  private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern(
    "yyyy-MM-dd'T'HH:mm"
  );
  private final Planner planner;

  public BookingSchedule(Planner planner) {
    this.planner = planner;
  }

  public record Window(
    String bookingId,
    String label,
    String departure,
    String arrival,
    String leaveBy,
    String resumeAt,
    String departurePlace,
    String arrivalPlace
  ) {}

  public record Issue(
    String kind,
    List<String> bookingIds,
    List<String> itemIds,
    String message
  ) {}

  public record Report(
    List<Window> windows,
    List<Issue> issues,
    int unlinkedCount
  ) {}

  public record Shift(
    String itemId,
    String name,
    int dayIndex,
    String before,
    String after
  ) {}

  public record Plan(
    String strategy,
    String title,
    boolean canApply,
    List<Shift> changes,
    int totalShiftMinutes,
    List<String> conflicts,
    List<Map<String, Object>> items
  ) {}

  /** 반열린 구간을 사용하여 일정이 여유 시간의 시작과 정확히 맞닿는 경우는 허용합니다. */
  private boolean overlaps(
    LocalDateTime a,
    LocalDateTime b,
    LocalDateTime c,
    LocalDateTime d
  ) {
    return a.isBefore(d) && c.isBefore(b);
  }

  private LocalDateTime start(LocalDate date, Map<String, Object> item) {
    // 장소 대안 미리보기는 하루 범위를 넘는 임시 시각도 만들 수 있습니다.
    // 24시 이상을 다음 날로 환산해야 오류 대신 충돌 안내를 반환할 수 있습니다.
    var parts = item.get("startTime").toString().split(":");
    return date
      .plusDays(number(item, "dayIndex"))
      .atStartOfDay()
      .plusMinutes(
        Integer.parseInt(parts[0]) * 60L + Integer.parseInt(parts[1])
      );
  }

  private int number(Map<String, Object> m, String key) {
    return ((Number) m.get(key)).intValue();
  }

  private String text(Map<String, Object> m, String key) {
    return Objects.toString(m.get(key), "");
  }

  private LocalDateTime leave(Window w) {
    return LocalDateTime.parse(w.leaveBy);
  }

  private LocalDateTime resume(Window w) {
    return LocalDateTime.parse(w.resumeAt);
  }

  public Report inspect(
    LocalDate date,
    List<Map<String, Object>> items,
    List<Map<String, Object>> bookings
  ) {
    List<Window> windows = new ArrayList<>();
    int unlinked = 0;
    for (var b : bookings) {
      if (!"USER_CONFIRMED".equals(b.get("status"))) continue;
      // 과거 출발 시간만 있는 기록, 실패·취소·확인 중 기록은 고정 시간으로 오인하지 않습니다.
      if (
        !Boolean.TRUE.equals(b.get("scheduleLinked")) ||
        text(b, "arrival").isBlank()
      ) {
        unlinked++;
        continue;
      }
      var departure = LocalDateTime.parse(text(b, "departure"));
      var arrival = LocalDateTime.parse(text(b, "arrival"));
      windows.add(
        new Window(
          text(b, "id"),
          text(b, "label"),
          departure.format(MINUTE),
          arrival.format(MINUTE),
          departure.minusMinutes(number(b, "beforeMinutes")).format(MINUTE),
          arrival.plusMinutes(number(b, "afterMinutes")).format(MINUTE),
          text(b, "departurePlace"),
          text(b, "arrivalPlace")
        )
      );
    }
    windows.sort(Comparator.comparing(Window::leaveBy));
    List<Issue> issues = new ArrayList<>();
    for (var w : windows)
      for (var i : items) {
        var a = start(date, i);
        if (
          overlaps(a, a.plusMinutes(number(i, "duration")), leave(w), resume(w))
        ) issues.add(
          new Issue(
            "VISIT_OVERLAP",
            List.of(w.bookingId),
            List.of(text(i, "id")),
            i.get("placeName") +
              (Boolean.TRUE.equals(i.get("fixed"))
                ? "의 고정 일정이 "
                : " 방문이 ") +
              w.label +
              "의 이동·여유 시간과 겹쳐요."
          )
        );
      }
    for (int i = 0; i < windows.size(); i++) for (
      int j = i + 1;
      j < windows.size();
      j++
    ) {
      var a = windows.get(i);
      var b = windows.get(j);
      if (overlaps(leave(a), resume(a), leave(b), resume(b))) issues.add(
        new Issue(
          "BOOKING_OVERLAP",
          List.of(a.bookingId, b.bookingId),
          List.of(),
          a.label +
            "과 " +
            b.label +
            "의 이동·여유 시간이 겹쳐요. 교통편 시간과 환승 여유를 확인해 주세요."
        )
      );
    }
    // 모든 시간 변경 엔진이 이 공통 검사를 사용합니다. 경로·숙소 비교에서도 사용자 확인 시간을 우회할 수 없습니다.
    VisitConditions.conflicts(items).forEach(message ->
      issues.add(new Issue("VISIT_WINDOW", List.of(), List.of(), message))
    );
    return new Report(windows, issues, unlinked);
  }

  /**
   * 방문일·순서·체류 시간·비용을 유지한 두 안입니다. 고정 방문은 절대 옮기지 않습니다.
   * 탐욕적으로 한 방향만 조정하므로 해가 없다는 결과는 다른 날 변경·방문 축소까지 불가능하다는 뜻은 아닙니다.
   */
  public List<Plan> alternatives(
    LocalDate date,
    List<Map<String, Object>> original,
    List<Map<String, Object>> bookings,
    String mode
  ) {
    return alternatives(date, original, bookings, mode, List.of());
  }

  /** 적용한 조회 시간이 있으면 예약 조정에서도 보존합니다. 숙소 출발은 자동 변경하지 않습니다. */
  public List<Plan> alternatives(
    LocalDate date,
    List<Map<String, Object>> original,
    List<Map<String, Object>> bookings,
    String mode,
    List<RouteTiming> timings
  ) {
    var report = inspect(date, original, bookings, timings);
    if (report.issues.isEmpty()) return List.of(
      new Plan(
        "EARLIER",
        "방문 시간 앞당기기",
        false,
        List.of(),
        0,
        List.of("교통 예약과 겹치는 방문 시간이 없어요."),
        original
      ),
      new Plan(
        "LATER",
        "방문 시간 늦추기",
        false,
        List.of(),
        0,
        List.of("교통 예약과 겹치는 방문 시간이 없어요."),
        original
      )
    );
    return List.of(
      plan(date, original, bookings, mode, report, "EARLIER", timings),
      plan(date, original, bookings, mode, report, "LATER", timings)
    );
  }

  /** 새 예약을 기록한 뒤에는 방문뿐 아니라 이미 적용한 이동 구간의 충돌도 화면에 알립니다. */
  public Report inspect(
    LocalDate date,
    List<Map<String, Object>> items,
    List<Map<String, Object>> bookings,
    List<RouteTiming> timings
  ) {
    var report = inspect(date, items, bookings);
    var issues = new ArrayList<>(report.issues());
    RouteTiming.conflicts(date, items, timings, report.windows()).forEach(
      message ->
        issues.add(new Issue("ROUTE_OVERLAP", List.of(), List.of(), message))
    );
    return new Report(report.windows(), issues, report.unlinkedCount());
  }

  private Plan plan(
    LocalDate date,
    List<Map<String, Object>> original,
    List<Map<String, Object>> bookings,
    String mode,
    Report report,
    String strategy,
    List<RouteTiming> timings
  ) {
    boolean later = strategy.equals("LATER");
    List<Map<String, Object>> items = new ArrayList<>();
    for (var i : original) items.add(new HashMap<>(i));
    items.sort(
      Comparator.<Map<String, Object>>comparingInt(i ->
        number(i, "dayIndex")
      ).thenComparingInt(i -> number(i, "sortOrder"))
    );
    List<String> conflicts = new ArrayList<>();
    // 앞당기기는 뒤에서부터, 늦추기는 앞에서부터 진행해야 방문 순서와 앞뒤 이동 간격이 유지됩니다.
    var order = new ArrayList<>(items);
    if (!later) Collections.reverse(order);
    Map<String, Object> neighbor = null;
    for (var i : order) {
      var saved = start(date, i);
      var candidate = saved;
      int duration = number(i, "duration");
      var boundDate = saved.toLocalDate().atStartOfDay();
      var visitBound = boundDate.plusMinutes(
        later
          ? VisitConditions.earliest(i, 360)
          : VisitConditions.latestStart(i, 1320 - duration)
      );
      if (
        later ? candidate.isBefore(visitBound) : candidate.isAfter(visitBound)
      ) candidate = visitBound;
      if (
        neighbor != null &&
        number(neighbor, "dayIndex") == number(i, "dayIndex")
      ) {
        int gap = later
          ? RouteTiming.gap(timings, neighbor, i, planner, mode)
          : RouteTiming.gap(timings, i, neighbor, planner, mode);
        var bound = later
          ? start(date, neighbor).plusMinutes(
              number(neighbor, "duration") + gap
            )
          : start(date, neighbor).minusMinutes(duration + gap);
        if (
          later ? candidate.isBefore(bound) : candidate.isAfter(bound)
        ) candidate = bound;
      }
      // 한 예약을 피한 결과 다른 예약에 들어갈 수 있어 정렬된 모든 시간 구간을 반복해서 확인합니다.
      var windows = new ArrayList<>(report.windows);
      if (!later) Collections.reverse(windows);
      for (var w : windows)
        if (
          overlaps(
            candidate,
            candidate.plusMinutes(duration),
            leave(w),
            resume(w)
          )
        ) candidate = later ? resume(w) : leave(w).minusMinutes(duration);
      if (Boolean.TRUE.equals(i.get("fixed"))) {
        if (!candidate.equals(saved)) conflicts.add(
          i.get("placeName") + "의 고정 시간은 옮길 수 없어요."
        );
      } else if (
        !candidate.toLocalDate().equals(saved.toLocalDate()) ||
        candidate.toLocalTime().isBefore(LocalTime.of(6, 0)) ||
        candidate
          .plusMinutes(duration)
          .isAfter(saved.toLocalDate().atTime(22, 0))
      ) {
        conflicts.add(
          i.get("placeName") + "을 같은 날 06:00~22:00 안에 배치할 수 없어요."
        );
      } else i.put(
        "startTime",
        candidate.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
      );
      neighbor = i;
    }
    inspect(date, items, bookings).issues.forEach(i ->
      conflicts.add(i.message)
    );
    conflicts.addAll(
      RouteTiming.conflicts(date, items, timings, report.windows)
    );
    List<Shift> changes = new ArrayList<>();
    int shift = 0;
    var oldById = new HashMap<Object, Map<String, Object>>();
    original.forEach(i -> oldById.put(i.get("id"), i));
    for (var i : items) {
      var old = oldById.get(i.get("id"));
      if (!old.get("startTime").equals(i.get("startTime"))) {
        changes.add(
          new Shift(
            text(i, "id"),
            text(i, "placeName"),
            number(i, "dayIndex"),
            text(old, "startTime"),
            text(i, "startTime")
          )
        );
        shift += (int) Math.abs(
          ChronoUnit.MINUTES.between(start(date, old), start(date, i))
        );
      }
    }
    if (changes.isEmpty() && conflicts.isEmpty()) conflicts.add(
      "조정할 방문 시간이 없어요."
    );
    return new Plan(
      strategy,
      later ? "방문 시간 늦추기" : "방문 시간 앞당기기",
      conflicts.isEmpty(),
      changes,
      shift,
      conflicts.stream().distinct().toList(),
      items
    );
  }
}
