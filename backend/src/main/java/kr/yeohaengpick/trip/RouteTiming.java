package kr.yeohaengpick.trip;

import java.time.*;
import java.util.*;
import kr.yeohaengpick.catalog.Place;

/**
 * 사용자가 적용한 한 구간의 조회 기록입니다. 요금은 활동비/실제 지출로 옮기지 않습니다.
 * 방문 ID뿐 아니라 장소·좌표·순서도 확인하여 장소 교체 후 과거 경로가 재사용되지 않게 합니다.
 */
public record RouteTiming(
  String fromKind,
  String fromId,
  String fromPlaceId,
  String toItemId,
  String toPlaceId,
  int dayIndex,
  double fromLatitude,
  double fromLongitude,
  double toLatitude,
  double toLongitude,
  String mode,
  int minutes,
  int bufferMinutes,
  int distanceMeters,
  String queriedAt,
  String source,
  String originDeparture,
  Overnight overnight
) {
  /** 두 숙소 연결 구간의 조회 출처를 각각 보관합니다. 체류·대기 시간을 이동 분으로 합산하지 않습니다. */
  public record Leg(
    int minutes,
    int distanceMeters,
    String queriedAt,
    String source
  ) {}

  public record Overnight(
    String lodgingId,
    String lodgingName,
    double latitude,
    double longitude,
    int minimumStayMinutes,
    String notBefore,
    String arrival,
    String departure,
    Leg inbound,
    Leg outbound
  ) {
    public Overnight at(String arrival, String departure) {
      return new Overnight(
        lodgingId,
        lodgingName,
        latitude,
        longitude,
        minimumStayMinutes,
        notBefore,
        arrival,
        departure,
        inbound,
        outbound
      );
    }
  }

  /** 기존 단일 구간 생성 코드와 저장 JSON은 숙소 경유 값 없이 그대로 읽습니다. */
  public RouteTiming(
    String fromKind,
    String fromId,
    String fromPlaceId,
    String toItemId,
    String toPlaceId,
    int dayIndex,
    double fromLatitude,
    double fromLongitude,
    double toLatitude,
    double toLongitude,
    String mode,
    int minutes,
    int bufferMinutes,
    int distanceMeters,
    String queriedAt,
    String source,
    String originDeparture
  ) {
    this(
      fromKind,
      fromId,
      fromPlaceId,
      toItemId,
      toPlaceId,
      dayIndex,
      fromLatitude,
      fromLongitude,
      toLatitude,
      toLongitude,
      mode,
      minutes,
      bufferMinutes,
      distanceMeters,
      queriedAt,
      source,
      originDeparture,
      null
    );
  }

  /** 비교용 전체 소요 시간과 저장용 실제 이동 분을 분리할 때 사용하는 불변 복사입니다. */
  public RouteTiming withStay(
    Overnight stay,
    String departure,
    int minutes,
    int buffer
  ) {
    return new RouteTiming(
      fromKind,
      fromId,
      fromPlaceId,
      toItemId,
      toPlaceId,
      dayIndex,
      fromLatitude,
      fromLongitude,
      toLatitude,
      toLongitude,
      mode,
      minutes,
      buffer,
      distanceMeters,
      queriedAt,
      source,
      departure,
      stay
    );
  }

  public static int number(Map<String, Object> i, String key) {
    return ((Number) i.get(key)).intValue();
  }

  public static int time(Map<String, Object> i) {
    return clock(i.get("startTime").toString());
  }

  public static int clock(String s) {
    var p = s.split(":");
    return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
  }

  public static String clock(int minutes) {
    return String.format("%02d:%02d", minutes / 60, minutes % 60);
  }

  public boolean applies(
    List<Map<String, Object>> items,
    List<Map<String, Object>> lodgings
  ) {
    if (
      overnight != null &&
      !lodgings
        .stream()
        .anyMatch(
          l ->
            overnight.lodgingId().equals(l.get("placeId")) &&
            coordinates(l, overnight.latitude(), overnight.longitude())
        )
    ) return false;
    var day = items
      .stream()
      .filter(i -> number(i, "dayIndex") == dayIndex)
      .toList();
    for (int n = 0; n < day.size(); n++) {
      var to = day.get(n);
      if (
        !toItemId.equals(to.get("id")) ||
        !toPlaceId.equals(to.get("placeId")) ||
        !coordinates(to, toLatitude, toLongitude)
      ) continue;
      if (fromKind.equals("ITEM")) {
        // 다음 날 첫 방문은 바로 전날 마지막 방문에만 연결합니다.
        // 날짜가 비거나 중간 방문을 건너뛴 경로를 일정 전체의 이동으로 오인하지 않습니다.
        var previous = items
          .stream()
          .filter(i -> number(i, "dayIndex") == dayIndex - 1)
          .toList();
        if (n == 0 && previous.isEmpty()) return false;
        var from = n > 0 ? day.get(n - 1) : previous.getLast();
        return (
          fromId.equals(from.get("id")) &&
          fromPlaceId.equals(from.get("placeId")) &&
          coordinates(from, fromLatitude, fromLongitude) &&
          (n > 0 || originDeparture != null) &&
          (overnight == null || n == 0)
        );
      }
      return (
        n == 0 &&
        fromKind.equals("LODGING") &&
        lodgings
          .stream()
          .anyMatch(
            l ->
              fromId.equals(l.get("placeId")) &&
              to.get("region").equals(l.get("region")) &&
              coordinates(l, fromLatitude, fromLongitude)
          )
      );
    }
    return false;
  }

  /** 이동일이 다른 구간만 별도 출발 일시를 가집니다. 기존 같은 날/숙소 기록과 JSON 형식을 공유합니다. */
  public boolean crossesDays(List<Map<String, Object>> items) {
    return (
      fromKind.equals("ITEM") &&
      items
        .stream()
        .anyMatch(
          i -> fromId.equals(i.get("id")) && number(i, "dayIndex") != dayIndex
        )
    );
  }

  /** 한국 현지 날짜·시간으로 환산합니다. 자정을 넘는 이동을 도착일의 출발 시각으로 바꾸지 않습니다. */
  public LocalDateTime departureAt(
    LocalDate date,
    List<Map<String, Object>> items
  ) {
    if (crossesDays(items)) return LocalDateTime.parse(originDeparture);
    if (fromKind.equals("LODGING")) return date
      .plusDays(dayIndex)
      .atStartOfDay()
      .plusMinutes(clock(originDeparture));
    var from = items
      .stream()
      .filter(i -> fromId.equals(i.get("id")))
      .findFirst()
      .orElseThrow();
    return date
      .plusDays(number(from, "dayIndex"))
      .atStartOfDay()
      .plusMinutes(time(from) + number(from, "duration"));
  }

  private static boolean coordinates(
    Map<String, Object> p,
    double lat,
    double lon
  ) {
    return (
      Math.abs(((Number) p.get("latitude")).doubleValue() - lat) < 0.000001 &&
      Math.abs(((Number) p.get("longitude")).doubleValue() - lon) < 0.000001
    );
  }

  public static Place place(Map<String, Object> i) {
    return new Place(
      i.get("placeId").toString(),
      Objects.toString(i.get("placeName"), "숙소"),
      i.get("region").toString(),
      Objects.toString(i.get("category"), "숙소"),
      ((Number) i.get("latitude")).doubleValue(),
      ((Number) i.get("longitude")).doubleValue(),
      i.containsKey("duration") ? number(i, "duration") : 0,
      0,
      false,
      "",
      ""
    );
  }

  /** 이미 적용한 조회 구간은 그 값과 사용자의 여유 시간을 사용하고 나머지는 거리 추정을 유지합니다. */
  public static int gap(
    List<RouteTiming> timings,
    Map<String, Object> from,
    Map<String, Object> to,
    Planner planner,
    String mode
  ) {
    return timings
      .stream()
      .filter(
        t ->
          t.fromKind.equals("ITEM") &&
          t.fromId.equals(from.get("id")) &&
          t.toItemId.equals(to.get("id"))
      )
      .mapToInt(t -> t.minutes + t.bufferMinutes)
      .findFirst()
      .orElseGet(
        () -> 20 + planner.estimateMinutes(place(from), place(to), mode)
      );
  }

  /** 방문이 겹치지 않아도 이동 자체가 고정 교통편을 가로지르면 적용할 수 없습니다. */
  public static List<String> conflicts(
    LocalDate date,
    List<Map<String, Object>> items,
    List<RouteTiming> timings,
    List<BookingSchedule.Window> windows
  ) {
    List<String> conflicts = new ArrayList<>();
    for (var t : timings) {
      var to = items
        .stream()
        .filter(i -> t.toItemId.equals(i.get("id")))
        .findFirst()
        .orElseThrow();
      var a = t.departureAt(date, items);
      // 숙소 체류는 이동 합계에서는 빼되 보호할 시간 범위에는 포함합니다.
      // 이후 다른 방문을 조정해도 숙소 출발과 다음 방문 사이의 실제 조회 분을 줄일 수 없습니다.
      var b =
        t.overnight == null
          ? a.plusMinutes(t.minutes + t.bufferMinutes)
          : LocalDateTime.parse(t.overnight.departure()).plusMinutes(
              t.overnight.outbound().minutes() + t.bufferMinutes
            );
      var arrivalBy = date
        .plusDays(t.dayIndex)
        .atStartOfDay()
        .plusMinutes(time(to));
      if (b.isAfter(arrivalBy)) conflicts.add(
        to.get("placeName") + "에 적용한 경로 시간만큼 이동할 여유가 없어요."
      );
      if (t.crossesDays(items)) {
        var from = items
          .stream()
          .filter(i -> t.fromId.equals(i.get("id")))
          .findFirst()
          .orElseThrow();
        var finish = date
          .plusDays(number(from, "dayIndex"))
          .atStartOfDay()
          .plusMinutes(time(from) + number(from, "duration"));
        if (a.isBefore(finish)) conflicts.add(
          from.get("placeName") + " 방문을 마치기 전에 다음 날 이동이 시작돼요."
        );
      }
      for (var w : windows)
        if (
          a.isBefore(LocalDateTime.parse(w.resumeAt())) &&
          LocalDateTime.parse(w.leaveBy()).isBefore(b)
        ) conflicts.add(
          to.get("placeName") +
            (t.overnight == null
              ? "까지의 이동·여유 시간이 "
              : "까지의 숙소 이동·체류 시간이 ") +
            w.label() +
            " 예약과 겹쳐요."
        );
    }
    return conflicts.stream().distinct().toList();
  }
}
