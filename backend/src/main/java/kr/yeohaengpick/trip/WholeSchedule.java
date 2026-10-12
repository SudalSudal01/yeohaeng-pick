package kr.yeohaengpick.trip;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import kr.yeohaengpick.common.DomainException;
import org.springframework.stereotype.Component;

/**
 * 장소를 재선정하지 않고 전체 여행의 방문 시각을 비교하는 순수 엔진입니다.
 * 앞에서부터 가장 이른 시각, 뒤에서부터 가장 늦은 시각을 구하는 두 방향 계산이며
 * 모든 순서·날짜를 탐색하는 전역 최적화나 영업시간·운행 확인을 의미하지 않습니다.
 */
@Component
public class WholeSchedule {

  private final Planner planner;
  private final BookingSchedule bookings;

  public WholeSchedule(Planner planner, BookingSchedule bookings) {
    this.planner = planner;
    this.bookings = bookings;
  }

  public record Request(
    int version,
    String startTime,
    String endTime,
    BigDecimal bufferMinutes,
    String strategy
  ) {}

  public record Settings(int start, int end, int buffer) {}

  public record Day(
    int dayIndex,
    String beforeStart,
    String beforeEnd,
    String afterStart,
    String afterEnd
  ) {}

  public record Plan(
    String strategy,
    String title,
    boolean canApply,
    List<BookingSchedule.Shift> changes,
    List<Day> days,
    int queriedLegs,
    int estimatedLegs,
    List<String> warnings,
    List<String> conflicts,
    List<Map<String, Object>> items
  ) {}

  /** 소수 여유 시간을 묵시적으로 절삭하지 않고 요청 오류로 돌려줍니다. */
  public Settings validate(Request r) {
    int start, end, buffer;
    try {
      if (
        r.startTime() == null ||
        r.endTime() == null ||
        !r.startTime().matches("(?:[01]\\d|2[0-3]):[0-5]\\d") ||
        !r.endTime().matches("(?:[01]\\d|2[0-3]):[0-5]\\d") ||
        r.bufferMinutes() == null
      ) throw new IllegalArgumentException();
      start = RouteTiming.clock(r.startTime());
      end = RouteTiming.clock(r.endTime());
      buffer = r.bufferMinutes().intValueExact();
    } catch (IllegalArgumentException | ArithmeticException e) {
      throw DomainException.bad(
        "시작·마감 시각과 정수 단위의 이동 여유를 확인해 주세요."
      );
    }
    if (
      r.version() < 0 ||
      start < 360 ||
      end > 1320 ||
      end - start < 60 ||
      buffer < 0 ||
      buffer > 120
    ) throw DomainException.bad(
      "06:00~22:00 안에서 1시간 이상의 하루 범위와 0~120분의 여유를 선택해 주세요."
    );
    return new Settings(start, end, buffer);
  }

  public List<Plan> compare(
    LocalDate date,
    List<Map<String, Object>> original,
    List<Map<String, Object>> reserved,
    List<RouteTiming> timings,
    List<Map<String, Object>> hotels,
    String mode,
    Settings settings
  ) {
    return List.of(
      plan(date, original, reserved, timings, hotels, mode, settings, true),
      plan(date, original, reserved, timings, hotels, mode, settings, false)
    );
  }

  private LocalDateTime at(
    LocalDate date,
    Map<String, Object> item,
    int minute
  ) {
    return date
      .plusDays(RouteTiming.number(item, "dayIndex"))
      .atStartOfDay()
      .plusMinutes(minute);
  }

  private LocalDateTime start(LocalDate date, Map<String, Object> item) {
    return at(date, item, RouteTiming.time(item));
  }

  private LocalDateTime min(LocalDateTime a, LocalDateTime b) {
    return a.isBefore(b) ? a : b;
  }

  private LocalDateTime max(LocalDateTime a, LocalDateTime b) {
    return a.isAfter(b) ? a : b;
  }

  private boolean overlaps(
    LocalDateTime a,
    LocalDateTime b,
    BookingSchedule.Window w
  ) {
    return (
      a.isBefore(LocalDateTime.parse(w.resumeAt())) &&
      LocalDateTime.parse(w.leaveBy()).isBefore(b)
    );
  }

  private RouteTiming leg(
    List<RouteTiming> timings,
    Map<String, Object> from,
    Map<String, Object> to
  ) {
    return timings
      .stream()
      .filter(
        t ->
          t.fromKind().equals("ITEM") &&
          t.fromId().equals(from.get("id")) &&
          t.toItemId().equals(to.get("id"))
      )
      .findFirst()
      .orElse(null);
  }

  private int gap(
    List<RouteTiming> timings,
    Map<String, Object> from,
    Map<String, Object> to,
    String mode,
    Settings s
  ) {
    var applied = leg(timings, from, to);
    // 조회 기록의 여유는 이미 사용자가 적용한 값입니다. 비교용 여유로 덮어쓰지 않습니다.
    return applied == null
      ? planner.estimateMinutes(
          RouteTiming.place(from),
          RouteTiming.place(to),
          mode
        ) + s.buffer()
      : applied.minutes() + applied.bufferMinutes();
  }

  private Plan plan(
    LocalDate date,
    List<Map<String, Object>> original,
    List<Map<String, Object>> reserved,
    List<RouteTiming> timings,
    List<Map<String, Object>> hotels,
    String mode,
    Settings s,
    boolean early
  ) {
    var items = new ArrayList<Map<String, Object>>();
    original.forEach(i -> items.add(new HashMap<>(i)));
    items.sort(
      Comparator.<Map<String, Object>>comparingInt(i ->
        RouteTiming.number(i, "dayIndex")
      ).thenComparingInt(i -> RouteTiming.number(i, "sortOrder"))
    );
    var report = bookings.inspect(date, original, reserved);
    var conflicts = new ArrayList<String>();
    var warnings = new ArrayList<String>();
    var windows = new ArrayList<>(report.windows());
    if (!early) Collections.reverse(windows);
    int queried = 0,
      estimated = 0;
    var days = new TreeMap<Integer, List<Map<String, Object>>>();
    items.forEach(i ->
      days
        .computeIfAbsent(RouteTiming.number(i, "dayIndex"), k ->
          new ArrayList<>()
        )
        .add(i)
    );
    for (var day : days.values()) {
      for (int n = 0; n < day.size() - 1; n++) {
        if (leg(timings, day.get(n), day.get(n + 1)) == null) estimated++;
        else queried++;
      }
      for (int step = 0; step < day.size(); step++) {
        int n = early ? step : day.size() - step - 1;
        var item = day.get(n);
        int duration = RouteTiming.number(item, "duration");
        int outgoing =
          n + 1 < day.size() ? gap(timings, item, day.get(n + 1), mode, s) : 0;
        var lower = at(date, item, VisitConditions.earliest(item, s.start()));
        var upper = at(
          date,
          item,
          VisitConditions.latestStart(item, s.end() - duration)
        );
        if (early && n > 0) {
          var previous = day.get(n - 1);
          lower = max(
            lower,
            start(date, previous).plusMinutes(
              RouteTiming.number(previous, "duration") +
                gap(timings, previous, item, mode, s)
            )
          );
        }
        if (!early && n + 1 < day.size()) upper = min(
          upper,
          start(date, day.get(n + 1)).minusMinutes(duration + outgoing)
        );
        // 날짜 간 이동과 숙소 출발은 독립적인 고정 앵커입니다. 조회 시각·최소 체류를 재작성하지 않습니다.
        for (var t : timings) {
          if (
            t.toItemId().equals(item.get("id")) &&
            (t.crossesDays(items) || t.fromKind().equals("LODGING"))
          ) {
            var arrival =
              t.overnight() == null
                ? t
                    .departureAt(date, items)
                    .plusMinutes(t.minutes() + t.bufferMinutes())
                : LocalDateTime.parse(t.overnight().departure()).plusMinutes(
                    t.overnight().outbound().minutes() + t.bufferMinutes()
                  );
            lower = max(lower, arrival);
          }
          if (t.fromId().equals(item.get("id")) && t.crossesDays(items)) upper =
            min(upper, t.departureAt(date, items).minusMinutes(duration));
        }
        var saved = start(date, item);
        var candidate = Boolean.TRUE.equals(item.get("fixed"))
          ? saved
          : early
            ? lower
            : upper;
        // 방문뿐 아니라 직후 이동·여유까지 한 구간으로 피합니다. 이동 중 탑승 예약을 가로지르는 안을 막습니다.
        // 마지막 최종 검사도 별도로 수행하여 고정 방문을 만난 뒤 생긴 순서 충돌을 놓치지 않습니다.
        for (var w : windows)
          if (
            overlaps(candidate, candidate.plusMinutes(duration + outgoing), w)
          ) {
            if (Boolean.TRUE.equals(item.get("fixed"))) {
              conflicts.add(
                item.get("placeName") +
                  "의 고정 방문·직후 이동이 " +
                  w.label() +
                  " 예약과 겹쳐요."
              );
            } else candidate = early
              ? LocalDateTime.parse(w.resumeAt())
              : LocalDateTime.parse(w.leaveBy()).minusMinutes(
                  duration + outgoing
                );
          }
        if (candidate.isBefore(lower) || candidate.isAfter(upper)) {
          conflicts.add(
            item.get("placeName") +
              (Boolean.TRUE.equals(item.get("fixed"))
                ? "의 고정 시간과 "
                : "을 ") +
              "하루 범위·방문 가능 시간·앞뒤 이동 조건에 함께 맞출 수 없어요."
          );
        }
        // 불가능한 임시 시각을 DB 형식으로 만들지 않습니다. 충돌이 있으면 해당 안 전체의 적용이 차단됩니다.
        if (
          candidate.toLocalDate().equals(saved.toLocalDate()) &&
          !candidate.isBefore(at(date, item, 360)) &&
          !candidate.plusMinutes(duration).isAfter(at(date, item, 1320))
        ) {
          item.put(
            "startTime",
            RouteTiming.clock(candidate.getHour() * 60 + candidate.getMinute())
          );
        } else conflicts.add(
          item.get("placeName") + "의 방문이 같은 날 06:00~22:00를 벗어나요."
        );
      }
    }
    // 불변 조건을 결과 전체에서 재검증합니다. 이 판단을 서버 적용 때도 다시 계산합니다.
    bookings
      .inspect(date, items, reserved, timings)
      .issues()
      .forEach(i -> conflicts.add(i.message()));
    for (var day : days.values())
      for (int n = 0; n < day.size() - 1; n++) {
        var from = day.get(n);
        var to = day.get(n + 1);
        int required = gap(timings, from, to, mode, s);
        var departure = start(date, from).plusMinutes(
          RouteTiming.number(from, "duration")
        );
        if (
          departure.plusMinutes(required).isAfter(start(date, to))
        ) conflicts.add(
          to.get("placeName") + "까지 이동·여유 시간이 부족해요."
        );
        for (var w : report.windows())
          if (
            overlaps(departure, departure.plusMinutes(required), w)
          ) conflicts.add(
            to.get("placeName") +
              "까지의 이동·여유 시간이 " +
              w.label() +
              " 예약과 겹쳐요."
          );
      }
    for (var t : timings)
      conflicts.addAll(LodgingStay.conflicts(t, date, items, hotels));
    int missingConnections = 0;
    for (var entry : days.entrySet())
      if (
        entry.getKey() > 0 &&
        timings
          .stream()
          .noneMatch(t ->
            t.toItemId().equals(entry.getValue().getFirst().get("id"))
          )
      ) missingConnections++;
    if (missingConnections > 0) warnings.add(
      "날짜 간 이동·숙소 출발 " +
        missingConnections +
        "구간은 아직 경로를 반영하지 않았어요. 별도 비교에서 확인해 주세요."
    );
    if (report.unlinkedCount() > 0) warnings.add(
      "일정에 연결하지 않은 교통 예약 " +
        report.unlinkedCount() +
        "건은 시간 보호에서 제외돼요."
    );
    var changes = new ArrayList<BookingSchedule.Shift>();
    var old = new HashMap<Object, Map<String, Object>>();
    original.forEach(i -> old.put(i.get("id"), i));
    for (var i : items)
      if (
        !Objects.equals(
          i.get("startTime"),
          old.get(i.get("id")).get("startTime")
        )
      ) changes.add(
        new BookingSchedule.Shift(
          i.get("id").toString(),
          i.get("placeName").toString(),
          RouteTiming.number(i, "dayIndex"),
          old.get(i.get("id")).get("startTime").toString(),
          i.get("startTime").toString()
        )
      );
    var summary = new ArrayList<Day>();
    days.forEach((d, visits) -> {
      var before = visits
        .stream()
        .map(i -> old.get(i.get("id")))
        .toList();
      // 충돌 중인 원본은 방문 순서와 시각 순서가 다를 수 있습니다. 실제 최솟값/최댓값으로 하루 범위를 표시합니다.
      summary.add(
        new Day(
          d,
          firstTime(before),
          lastTime(before),
          firstTime(visits),
          lastTime(visits)
        )
      );
    });
    return new Plan(
      early ? "EARLY" : "LATE",
      early ? "일찍 마치는 안" : "늦게 시작하는 안",
      conflicts.isEmpty() && !changes.isEmpty(),
      changes,
      summary,
      queried,
      estimated,
      warnings,
      conflicts.stream().distinct().toList(),
      items
    );
  }

  private String firstTime(List<Map<String, Object>> visits) {
    return RouteTiming.clock(
      visits.stream().mapToInt(RouteTiming::time).min().orElseThrow()
    );
  }

  private String lastTime(List<Map<String, Object>> visits) {
    return RouteTiming.clock(
      visits
        .stream()
        .mapToInt(i -> RouteTiming.time(i) + RouteTiming.number(i, "duration"))
        .max()
        .orElseThrow()
    );
  }
}
