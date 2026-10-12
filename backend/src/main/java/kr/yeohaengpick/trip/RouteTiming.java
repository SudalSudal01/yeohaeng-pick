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
  String originDeparture
) {
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
        if (n == 0) return false;
        var from = day.get(n - 1);
        return (
          fromId.equals(from.get("id")) &&
          fromPlaceId.equals(from.get("placeId")) &&
          coordinates(from, fromLatitude, fromLongitude)
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
      int departure = t.fromKind.equals("LODGING")
        ? clock(t.originDeparture)
        : items
            .stream()
            .filter(i -> t.fromId.equals(i.get("id")))
            .mapToInt(i -> time(i) + number(i, "duration"))
            .findFirst()
            .orElseThrow();
      if (departure + t.minutes + t.bufferMinutes > time(to)) conflicts.add(
        to.get("placeName") + "에 적용한 경로 시간만큼 이동할 여유가 없어요."
      );
      var a = date.plusDays(t.dayIndex).atStartOfDay().plusMinutes(departure);
      var b = a.plusMinutes(t.minutes + t.bufferMinutes);
      for (var w : windows)
        if (
          a.isBefore(LocalDateTime.parse(w.resumeAt())) &&
          LocalDateTime.parse(w.leaveBy()).isBefore(b)
        ) conflicts.add(
          to.get("placeName") +
            "까지의 이동·여유 시간이 " +
            w.label() +
            " 예약과 겹쳐요."
        );
    }
    return conflicts.stream().distinct().toList();
  }
}
