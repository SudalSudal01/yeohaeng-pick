package kr.yeohaengpick.transport;

import java.time.*;
import java.util.*;
import kr.yeohaengpick.common.DomainException;
import kr.yeohaengpick.trip.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 두 실제 조회 영수증을 동일 여행·회원·버전·숙소로 묶습니다. 프론트가 보낸 이동 분은 받지 않습니다. */
@Service
public class OvernightService {

  private final TransportQuotes quotes;
  private final TripService trips;
  private final TripMapper mapper;
  private final OvernightSchedule schedule;

  public OvernightService(
    TransportQuotes quotes,
    TripService trips,
    TripMapper mapper,
    OvernightSchedule schedule
  ) {
    this.quotes = quotes;
    this.trips = trips;
    this.mapper = mapper;
    this.schedule = schedule;
  }

  public record Request(
    String inboundToken,
    String inboundOption,
    String outboundToken,
    String outboundOption,
    int version,
    Integer bufferMinutes,
    Integer minimumStayMinutes,
    String notBefore,
    String strategy
  ) {}

  private TransportRoutes.Option option(
    TransportQuotes.Quote quote,
    String id
  ) {
    return quote
      .options()
      .stream()
      .filter(o -> o.id().equals(id))
      .findFirst()
      .orElseThrow(() ->
        DomainException.bad("조회한 숙소 연결 경로를 선택해 주세요.")
      );
  }

  private RouteTiming.Leg leg(
    TransportQuotes.Quote quote,
    TransportRoutes.Option option
  ) {
    return new RouteTiming.Leg(
      option.minutes(),
      option.distanceMeters(),
      quote.queriedAt(),
      quote.source()
    );
  }

  private boolean same(TransportRoutes.Point point, Map<String, Object> saved) {
    return (
      Math.abs(
        point.latitude() - ((Number) saved.get("latitude")).doubleValue()
      ) < 0.000001 &&
      Math.abs(
        point.longitude() - ((Number) saved.get("longitude")).doubleValue()
      ) < 0.000001
    );
  }

  public List<RouteSchedule.Plan> preview(String id, String member, Request r) {
    var trip = trips.access(id, member);
    var inbound = quotes.get(r.inboundToken(), id, member);
    var outbound = quotes.get(r.outboundToken(), id, member);
    if (
      RouteTiming.number(trip, "version") != r.version() ||
      inbound.version() != r.version() ||
      outbound.version() != r.version()
    ) throw new DomainException(
      409,
      "여행이 변경됐어요. 최신 일정에서 숙소 경로를 다시 조회해 주세요."
    );
    if (
      r.bufferMinutes() == null ||
      r.bufferMinutes() < 0 ||
      r.bufferMinutes() > 120 ||
      r.minimumStayMinutes() == null ||
      r.minimumStayMinutes() < 60 ||
      r.minimumStayMinutes() > 960 ||
      r.minimumStayMinutes() % 30 != 0 ||
      r.notBefore() == null ||
      !r.notBefore().matches("(?:0[6-9]|1\\d|2[01]):[0-5]\\d")
    ) throw DomainException.bad(
      "구간 여유는 0~120분, 숙소 체류는 1~16시간(30분 단위), 다음 날 출발은 06:00~21:59로 입력해 주세요."
    );
    if (
      !inbound.fromRef().kind().equals("ITEM") ||
      !inbound.toRef().kind().equals("LODGING") ||
      !outbound.fromRef().kind().equals("LODGING") ||
      !outbound.toRef().kind().equals("ITEM") ||
      !inbound.toRef().id().equals(outbound.fromRef().id()) ||
      !inbound.mode().equals(outbound.mode())
    ) throw DomainException.bad(
      "같은 숙소와 이동 수단의 앞뒤 경로를 조회해 주세요."
    );
    var items = mapper.items(id);
    var lodgings = mapper.lodgings(id);
    var from = items
      .stream()
      .filter(i -> inbound.fromRef().id().equals(i.get("id")))
      .findFirst()
      .orElseThrow(() -> DomainException.bad("출발 방문이 변경됐어요."));
    var to = items
      .stream()
      .filter(i -> outbound.toRef().id().equals(i.get("id")))
      .findFirst()
      .orElseThrow(() -> DomainException.bad("도착 방문이 변경됐어요."));
    var lodging = lodgings
      .stream()
      .filter(l -> inbound.toRef().id().equals(l.get("placeId")))
      .findFirst()
      .orElseThrow(() ->
        DomainException.bad("여행에서 선택한 숙소를 조회해 주세요.")
      );
    if (
      !same(inbound.from(), from) ||
      !same(outbound.to(), to) ||
      !same(inbound.to(), lodging) ||
      !same(outbound.from(), lodging)
    ) throw DomainException.bad(
      "조회 이후 장소 위치가 변경됐어요. 다시 조회해 주세요."
    );
    var a = option(inbound, r.inboundOption());
    var b = option(outbound, r.outboundOption());
    var stay = new RouteTiming.Overnight(
      lodging.get("placeId").toString(),
      lodging.get("name").toString(),
      inbound.to().latitude(),
      inbound.to().longitude(),
      r.minimumStayMinutes(),
      r.notBefore(),
      null,
      null,
      leg(inbound, a),
      leg(outbound, b)
    );
    var timing = new RouteTiming(
      "ITEM",
      from.get("id").toString(),
      from.get("placeId").toString(),
      to.get("id").toString(),
      to.get("placeId").toString(),
      RouteTiming.number(to, "dayIndex"),
      inbound.from().latitude(),
      inbound.from().longitude(),
      outbound.to().latitude(),
      outbound.to().longitude(),
      inbound.mode(),
      a.minutes() + b.minutes(),
      r.bufferMinutes(),
      a.distanceMeters() + b.distanceMeters(),
      inbound.queriedAt(),
      inbound.source(),
      trip.get("startDate") + "T00:00",
      stay
    );
    if (
      !timing.applies(items, lodgings) || !timing.crossesDays(items)
    ) throw DomainException.bad(
      "전날 마지막 방문과 바로 다음 날 첫 방문을 숙소로 연결해 주세요."
    );
    return schedule.compare(
      LocalDate.parse(trip.get("startDate").toString()),
      items,
      mapper.externalBookings(id),
      trips.routeTimings(id, items, lodgings),
      timing,
      trip.get("transport").toString()
    );
  }

  @Transactional
  public Object apply(String id, String member, Request r) {
    var plan = preview(id, member, r)
      .stream()
      .filter(p -> p.strategy().equals(r.strategy()))
      .findFirst()
      .orElseThrow(() ->
        DomainException.bad("적용할 숙소 연결안을 선택해 주세요.")
      );
    if (!plan.canApply()) throw new DomainException(
      409,
      "예약·방문 시간 또는 최소 숙소 체류와 충돌해 적용할 수 없어요."
    );
    return trips.applyRouteTiming(id, member, r.version(), plan);
  }
}
