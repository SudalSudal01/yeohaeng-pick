package kr.yeohaengpick.trip;

import static kr.yeohaengpick.trip.RouteTiming.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * 전날 마지막 방문→숙소→다음 날 첫 방문을 연결하는 두 안을 계산합니다.
 * 기존 시간 엔진에 이동·체류·대기의 전체 범위를 전달해 고정 예약과 양일 방문을 보호합니다.
 * 저장 시에는 두 조회 구간의 이동 분만 남깁니다. 숙소 체류는 실제 수면이나 객실 예약을 뜻하지 않습니다.
 */
@Component
public class OvernightSchedule {

  private final RouteSchedule routes;
  private final BookingSchedule bookings;

  public OvernightSchedule(RouteSchedule routes, BookingSchedule bookings) {
    this.routes = routes;
    this.bookings = bookings;
  }

  private String iso(LocalDateTime time) {
    return time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
  }

  public List<RouteSchedule.Plan> compare(
    LocalDate date,
    List<Map<String, Object>> items,
    List<Map<String, Object>> reserved,
    List<RouteTiming> existing,
    RouteTiming proposed,
    String mode
  ) {
    var stay = proposed.overnight();
    var from = items
      .stream()
      .filter(i -> proposed.fromId().equals(i.get("id")))
      .findFirst()
      .orElseThrow();
    var to = items
      .stream()
      .filter(i -> proposed.toItemId().equals(i.get("id")))
      .findFirst()
      .orElseThrow();
    var finish = date
      .plusDays(number(from, "dayIndex"))
      .atStartOfDay()
      .plusMinutes(time(from) + number(from, "duration"));
    var first = date
      .plusDays(number(to, "dayIndex"))
      .atStartOfDay()
      .plusMinutes(time(to));
    var morning = date
      .plusDays(proposed.dayIndex())
      .atTime(LocalTime.parse(stay.notBefore()));
    int inbound = stay.inbound().minutes() + proposed.bufferMinutes();
    int outbound = stay.outbound().minutes() + proposed.bufferMinutes();
    var plans = new ArrayList<RouteSchedule.Plan>();
    for (String strategy : List.of("DEPARTURE", "ARRIVAL")) {
      var arrivalAtHotel = finish.plusMinutes(inbound);
      var leaveHotel = arrivalAtHotel.plusMinutes(stay.minimumStayMinutes());
      if (leaveHotel.isBefore(morning)) leaveHotel = morning;
      int total = strategy.equals("DEPARTURE")
        ? Math.toIntExact(
            Duration.between(
              finish,
              leaveHotel.plusMinutes(outbound)
            ).toMinutes()
          )
        : inbound + stay.minimumStayMinutes() + outbound;
      // 이 임시 값은 비교에만 사용합니다. 저장 기록의 minutes에는 체류 시간이 섞이지 않습니다.
      var bridge = proposed.withStay(null, iso(finish), total, 0);
      var base = routes.plan(
        date,
        items,
        reserved,
        existing,
        bridge,
        mode,
        strategy
      );
      var latestDeparture = LocalDateTime.parse(
        base.timing().originDeparture()
      );
      var departure = latestDeparture;
      if (strategy.equals("ARRIVAL")) {
        // 다음 날 방문을 유지할 여유가 충분하면 전날 방문 종료 직후 숙소로 갑니다.
        // 최소 체류에 맞추려고 숙소 도착을 불필요하게 새벽까지 늦추지 않습니다.
        var adjustedFrom = base
          .items()
          .stream()
          .filter(i -> proposed.fromId().equals(i.get("id")))
          .findFirst()
          .orElseThrow();
        var adjustedFinish = date
          .plusDays(number(adjustedFrom, "dayIndex"))
          .atStartOfDay()
          .plusMinutes(time(adjustedFrom) + number(adjustedFrom, "duration"));
        if (adjustedFinish.isBefore(departure)) departure = adjustedFinish;
      }
      arrivalAtHotel = departure.plusMinutes(inbound);
      if (strategy.equals("ARRIVAL")) leaveHotel = first.minusMinutes(outbound);
      var conflicts = new ArrayList<>(base.conflicts());
      if (leaveHotel.isBefore(morning)) conflicts.add(
        "첫 방문을 지키려면 입력한 숙소 출발 가능 시각보다 일찍 나서야 해요."
      );
      if (
        Duration.between(arrivalAtHotel, leaveHotel).toMinutes() <
        stay.minimumStayMinutes()
      ) conflicts.add("입력한 최소 숙소 체류 시간을 확보할 수 없어요.");
      var saved = proposed.withStay(
        stay.at(iso(arrivalAtHotel), iso(leaveHotel)),
        iso(departure),
        proposed.minutes(),
        proposed.bufferMinutes()
      );
      // 비교 엔진의 가장 늦은 출발보다 일찍 숙소로 간 경우 추가로 확보한 저녁 체류도 예약과 대조합니다.
      // 원래 범위에 이미 잡힌 충돌은 중복 안내하지 않습니다.
      if (departure.isBefore(latestDeparture)) {
        var end = leaveHotel.plusMinutes(outbound);
        for (var window : bookings
          .inspect(date, base.items(), reserved)
          .windows()) {
          var startAt = LocalDateTime.parse(window.leaveBy());
          var endAt = LocalDateTime.parse(window.resumeAt());
          boolean previouslyOverlapped =
            latestDeparture.isBefore(endAt) && startAt.isBefore(end);
          if (
            !previouslyOverlapped &&
            departure.isBefore(endAt) &&
            startAt.isBefore(end)
          ) conflicts.add(
            "숙소 이동·체류가 " + window.label() + " 예약과 겹쳐요."
          );
        }
      }
      plans.add(
        new RouteSchedule.Plan(
          strategy,
          strategy.equals("DEPARTURE")
            ? "전날 일정 유지"
            : "다음 날 첫 방문 유지",
          conflicts.isEmpty(),
          java.time.temporal.ChronoUnit.DAYS.between(
            date,
            departure.toLocalDate()
          ) +
            1 +
            "일차 " +
            departure
              .toLocalTime()
              .format(DateTimeFormatter.ofPattern("HH:mm")),
          base.arrival(),
          base.changes(),
          conflicts.stream().distinct().toList(),
          base.items(),
          saved
        )
      );
    }
    return plans;
  }
}
