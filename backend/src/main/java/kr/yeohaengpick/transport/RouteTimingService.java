package kr.yeohaengpick.transport;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import kr.yeohaengpick.common.DomainException;
import kr.yeohaengpick.trip.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 조회 영수증과 현재 여행을 연결합니다. 미리보기·적용 모두 동일한 서버 계산을 거칩니다. */
@Service
public class RouteTimingService {

  private final TransportQuotes quotes;
  private final TripService trips;
  private final TripMapper mapper;
  private final RouteSchedule schedule;

  public RouteTimingService(
    TransportQuotes quotes,
    TripService trips,
    TripMapper mapper,
    RouteSchedule schedule
  ) {
    this.quotes = quotes;
    this.trips = trips;
    this.mapper = mapper;
    this.schedule = schedule;
  }

  public record Request(
    String quoteToken,
    String optionId,
    int version,
    Integer bufferMinutes,
    String departureTime,
    String strategy
  ) {}

  public List<RouteSchedule.Plan> preview(String id, String member, Request r) {
    var trip = trips.access(id, member);
    var q = quotes.get(r.quoteToken(), id, member);
    if (
      RouteTiming.number(trip, "version") != r.version() ||
      q.version() != r.version()
    ) throw new DomainException(
      409,
      "조회 이후 여행이 변경됐어요. 최신 일정에서 경로를 다시 조회해 주세요."
    );
    if (
      r.bufferMinutes() == null ||
      r.bufferMinutes() < 0 ||
      r.bufferMinutes() > 120
    ) throw DomainException.bad("추가 여유 시간을 0~120분으로 입력해 주세요.");
    var option = q
      .options()
      .stream()
      .filter(o -> o.id().equals(r.optionId()))
      .findFirst()
      .orElseThrow(() ->
        DomainException.bad("조회한 경로 중 하나를 선택해 주세요.")
      );
    var items = mapper.items(id);
    var lodgings = mapper.lodgings(id);
    if (
      !q.toRef().kind().equals("ITEM") ||
      !List.of("ITEM", "LODGING").contains(q.fromRef().kind())
    ) throw DomainException.bad(
      "연속 방문 사이 또는 선택한 숙소에서 첫 방문까지 적용할 수 있어요."
    );
    var to = items
      .stream()
      .filter(i -> q.toRef().id().equals(i.get("id")))
      .findFirst()
      .orElseThrow(() ->
        DomainException.bad("도착 방문이 변경됐어요. 다시 조회해 주세요.")
      );
    String fromPlaceId;
    if (q.fromRef().kind().equals("LODGING")) {
      fromPlaceId = q.fromRef().id();
      if (
        r.departureTime() == null ||
        !r.departureTime().matches("(?:[01]\\d|2[0-3]):[0-5]\\d")
      ) throw DomainException.bad("숙소 출발 시간을 입력해 주세요.");
    } else fromPlaceId = items
      .stream()
      .filter(i -> q.fromRef().id().equals(i.get("id")))
      .map(i -> i.get("placeId").toString())
      .findFirst()
      .orElseThrow(() -> DomainException.bad("출발 방문을 찾을 수 없어요."));
    var timing = new RouteTiming(
      q.fromRef().kind(),
      q.fromRef().id(),
      fromPlaceId,
      q.toRef().id(),
      to.get("placeId").toString(),
      RouteTiming.number(to, "dayIndex"),
      q.from().latitude(),
      q.from().longitude(),
      q.to().latitude(),
      q.to().longitude(),
      q.mode(),
      option.minutes(),
      r.bufferMinutes(),
      option.distanceMeters(),
      q.queriedAt(),
      q.source(),
      r.departureTime()
    );
    if (!timing.applies(items, lodgings)) throw DomainException.bad(
      "같은 날 연속 방문, 전날 마지막→다음 날 첫 방문, 같은 지역 숙소→첫 방문 중에서 선택해 주세요."
    );
    if (timing.crossesDays(items)) {
      // 이동 날짜는 클라이언트의 방문 필드가 아닌 현재 저장 일정에서 제한합니다.
      // 기존 departureTime 계약을 확장하되 같은 날/숙소의 HH:mm 요청은 그대로 지원합니다.
      LocalDateTime departure;
      try {
        departure = LocalDateTime.parse(
          Objects.toString(r.departureTime(), "")
        );
      } catch (java.time.DateTimeException e) {
        throw DomainException.bad(
          "날짜 간 이동의 출발 날짜와 시간을 입력해 주세요."
        );
      }
      var start = LocalDate.parse(trip.get("startDate").toString());
      var arrivalDate = start.plusDays(timing.dayIndex());
      if (
        departure.toLocalDate().isBefore(arrivalDate.minusDays(1)) ||
        departure.toLocalDate().isAfter(arrivalDate) ||
        departure.getSecond() != 0 ||
        departure.getNano() != 0
      ) throw DomainException.bad(
        "이동 출발은 출발 방문일 또는 다음 방문일 안에서 분 단위로 선택해 주세요."
      );
    }
    return LodgingStay.protect(
      schedule.compare(
        LocalDate.parse(trip.get("startDate").toString()),
        items,
        mapper.externalBookings(id),
        trips.routeTimings(id, items, lodgings),
        timing,
        trip.get("transport").toString()
      ),
      LocalDate.parse(trip.get("startDate").toString()),
      lodgings
    );
  }

  @Transactional
  public Object apply(String id, String member, Request r) {
    var plan = preview(id, member, r)
      .stream()
      .filter(p -> p.strategy().equals(r.strategy()))
      .findFirst()
      .orElseThrow(() -> DomainException.bad("적용할 비교안을 선택해 주세요."));
    if (!plan.canApply()) throw new DomainException(
      409,
      "예약이나 하루 시간 범위와 충돌해 적용할 수 없어요. 다른 안을 확인해 주세요."
    );
    return trips.applyRouteTiming(id, member, r.version(), plan);
  }

  public Object remove(
    String id,
    String member,
    RouteTimingController.Remove r
  ) {
    return trips.removeRouteTiming(id, member, r.version(), r.itemId());
  }
}
