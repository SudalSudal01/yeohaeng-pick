package kr.yeohaengpick.trip;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import kr.yeohaengpick.common.DomainException;

/**
 * 사용자가 직접 확인한 숙박 조건의 경계를 검증합니다. 객실 재고·최대 정원·예약 성공을 추정하지 않습니다.
 * 시각이 미입력이면 해당 날짜만 검사하며 임의의 호텔 체크인/아웃 시간을 넣지 않습니다.
 */
public final class LodgingStay {

  private LodgingStay() {}

  public record Request(
    int version,
    String checkInDate,
    String checkOutDate,
    BigDecimal guestCount,
    BigDecimal roomCount,
    BigDecimal totalAmount,
    String checkInTime,
    String checkOutTime
  ) {}

  public record Conditions(
    LocalDate checkInDate,
    LocalDate checkOutDate,
    Integer guestCount,
    Integer roomCount,
    Integer totalAmount,
    String checkInTime,
    String checkOutTime
  ) {}

  public static Conditions validate(Request r, Map<String, Object> trip) {
    if (r.checkInDate() == null && r.checkOutDate() == null) {
      if (
        r.guestCount() != null ||
        r.roomCount() != null ||
        r.totalAmount() != null ||
        !blank(r.checkInTime()) ||
        !blank(r.checkOutTime())
      ) throw DomainException.bad(
        "숙박 날짜와 객실 조건을 함께 입력해 주세요."
      );
      return new Conditions(null, null, null, null, null, null, null);
    }
    LocalDate in, out;
    try {
      if (
        r.checkInDate() == null ||
        r.checkOutDate() == null ||
        !r.checkInDate().matches("\\d{4}-\\d{2}-\\d{2}") ||
        !r.checkOutDate().matches("\\d{4}-\\d{2}-\\d{2}")
      ) throw new DateTimeException("date");
      in = LocalDate.parse(r.checkInDate());
      out = LocalDate.parse(r.checkOutDate());
    } catch (DateTimeException e) {
      throw DomainException.bad("체크인·체크아웃 날짜를 확인해 주세요.");
    }
    if (
      !out.isAfter(in) ||
      in.isBefore(LocalDate.parse(trip.get("startDate").toString())) ||
      out.isAfter(LocalDate.parse(trip.get("endDate").toString()))
    ) throw DomainException.bad(
      "숙박은 여행 기간 안에서 1박 이상으로 입력해 주세요."
    );
    // JSON 소수를 정수 DTO로 자동 절삭하지 않도록 정확한 정수 변환을 먼저 검사합니다.
    Integer guests = integer(r.guestCount()),
      rooms = integer(r.roomCount()),
      amount = integer(r.totalAmount());
    if (
      guests == null ||
      guests < 1 ||
      guests > RouteTiming.number(trip, "partySize") ||
      rooms == null ||
      rooms < 1 ||
      rooms > 20 ||
      (amount != null && (amount < 0 || amount > 100000000))
    ) throw DomainException.bad(
      "숙박 인원은 여행 인원 이내, 객실은 1~20개, 총 숙박비는 0~1억 원으로 입력해 주세요."
    );
    return new Conditions(
      in,
      out,
      guests,
      rooms,
      amount,
      clock(r.checkInTime()),
      clock(r.checkOutTime())
    );
  }

  private static Integer integer(BigDecimal value) {
    try {
      return value == null ? null : value.intValueExact();
    } catch (ArithmeticException e) {
      throw DomainException.bad("인원·객실·금액은 정수로 입력해 주세요.");
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String clock(String value) {
    if (blank(value)) return null;
    if (
      !value.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")
    ) throw DomainException.bad("숙소 이용 시각은 HH:mm으로 입력해 주세요.");
    return value;
  }

  /** 조건 수정과 새 경로 비교가 같은 규칙을 사용합니다. 미정 조건은 기존 여행의 동작을 제한하지 않습니다. */
  public static List<String> conflicts(
    Conditions c,
    String name,
    LocalDateTime arrival,
    LocalDateTime departure
  ) {
    if (c.checkInDate() == null) return List.of();
    var start = c
      .checkInDate()
      .atTime(
        c.checkInTime() == null
          ? LocalTime.MIN
          : LocalTime.parse(c.checkInTime())
      );
    var end = c
      .checkOutDate()
      .atTime(
        c.checkOutTime() == null
          ? LocalTime.MAX
          : LocalTime.parse(c.checkOutTime())
      );
    var issues = new ArrayList<String>();
    if (arrival != null && arrival.isBefore(start)) issues.add(
      name + " 도착이 입력한 체크인 조건보다 빨라요."
    );
    if (departure.isBefore(start) || departure.isAfter(end)) issues.add(
      name + " 출발이 입력한 숙박 기간·체크아웃 조건을 벗어나요."
    );
    if (arrival != null && arrival.isAfter(end)) issues.add(
      name + " 도착이 입력한 숙박 기간을 벗어나요."
    );
    return issues;
  }

  public static Conditions stored(Map<String, Object> hotel) {
    if (hotel.get("checkInDate") == null) return new Conditions(
      null,
      null,
      null,
      null,
      null,
      null,
      null
    );
    return new Conditions(
      LocalDate.parse(hotel.get("checkInDate").toString()),
      LocalDate.parse(hotel.get("checkOutDate").toString()),
      (Integer) hotel.get("guestCount"),
      (Integer) hotel.get("roomCount"),
      (Integer) hotel.get("totalAmount"),
      (String) hotel.get("checkInTime"),
      (String) hotel.get("checkOutTime")
    );
  }

  public static List<String> conflicts(
    Conditions c,
    String name,
    RouteTiming timing,
    LocalDate date,
    List<Map<String, Object>> items
  ) {
    if (timing.overnight() != null) return conflicts(
      c,
      name,
      LocalDateTime.parse(timing.overnight().arrival()),
      LocalDateTime.parse(timing.overnight().departure())
    );
    return conflicts(c, name, null, timing.departureAt(date, items));
  }

  public static boolean uses(RouteTiming timing, String hotelId) {
    return timing.overnight() != null
      ? timing.overnight().lodgingId().equals(hotelId)
      : timing.fromKind().equals("LODGING") && timing.fromId().equals(hotelId);
  }

  public static List<String> conflicts(
    RouteTiming timing,
    LocalDate date,
    List<Map<String, Object>> items,
    List<Map<String, Object>> hotels
  ) {
    return hotels
      .stream()
      .filter(h -> uses(timing, h.get("placeId").toString()))
      .flatMap(h ->
        conflicts(
          stored(h),
          h.get("name").toString(),
          timing,
          date,
          items
        ).stream()
      )
      .toList();
  }

  /** 원래 계산 결과를 유지하면서 숙박 조건 충돌을 추가해 미리보기와 적용의 판단을 일치시킵니다. */
  public static List<RouteSchedule.Plan> protect(
    List<RouteSchedule.Plan> plans,
    LocalDate date,
    List<Map<String, Object>> hotels
  ) {
    return plans
      .stream()
      .map(p -> {
        var issues = new ArrayList<>(p.conflicts());
        issues.addAll(conflicts(p.timing(), date, p.items(), hotels));
        return new RouteSchedule.Plan(
          p.strategy(),
          p.title(),
          issues.isEmpty(),
          p.departure(),
          p.arrival(),
          p.changes(),
          issues.stream().distinct().toList(),
          p.items(),
          p.timing()
        );
      })
      .toList();
  }
}
