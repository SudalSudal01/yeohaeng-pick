package kr.yeohaengpick.trip;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import kr.yeohaengpick.catalog.*;
import kr.yeohaengpick.common.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 저장 후에도 숙소를 비교합니다. 좌표 기반 비교와 실제 경로 조회·객실 예약을 구분합니다. */
@Service
public class LodgingChoiceService {

  private final TripService trips;
  private final TripMapper mapper;
  private final CatalogService catalog;
  private final Planner planner;
  private final JsonCodec json;
  private final kr.yeohaengpick.favorite.FavoriteService favorites;

  public LodgingChoiceService(
    TripService trips,
    TripMapper mapper,
    CatalogService catalog,
    Planner planner,
    JsonCodec json,
    kr.yeohaengpick.favorite.FavoriteService favorites
  ) {
    this.trips = trips;
    this.mapper = mapper;
    this.catalog = catalog;
    this.planner = planner;
    this.json = json;
    this.favorites = favorites;
  }

  public record Change(
    @jakarta.validation.constraints.NotBlank
    @jakarta.validation.constraints.Size(max = 30)
    String region,
    @jakarta.validation.constraints.NotBlank
    @jakarta.validation.constraints.Size(max = 100)
    String placeId,
    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.Size(max = 100)
    String query,
    @jakarta.validation.constraints.Min(0) int version,
    Boolean favorite
  ) {
    /** 기존 일반 숙소 후보 비교 입력은 favorite 생략/false로 같은 동작을 유지합니다. */
    public Change(String region, String placeId, String query, int version) {
      this(region, placeId, query, version, false);
    }

    /** 이전 앱이 필드를 보내지 않으면 null이므로 명시적으로 true인 요청만 개인 찜으로 처리합니다. */
    public boolean usesFavorite() {
      return Boolean.TRUE.equals(favorite);
    }
  }

  /** 같은 숙박일의 마지막 방문→숙소, 숙소→다음 날 첫 방문만 비교합니다. 방문 간 이동은 합산하지 않습니다. */
  public record Leg(
    int dayIndex,
    String direction,
    String placeName,
    Integer beforeMinutes,
    int afterMinutes
  ) {}

  public record Preview(
    Place candidate,
    String currentName,
    List<Leg> legs,
    Integer beforeMinutes,
    int afterMinutes,
    Integer minutesDelta,
    int clearedRouteCount,
    boolean resetsStay,
    List<String> conflicts,
    boolean canApply
  ) {}

  /** 여행의 마지막 방문 지역에서 밤을 보낸다고 가정합니다. 실제 숙박 날짜는 선택 뒤 별도로 확인합니다. */
  private List<Integer> nights(
    Map<String, Object> trip,
    List<Map<String, Object>> items,
    String region
  ) {
    int last = (int) ChronoUnit.DAYS.between(
      LocalDate.parse(trip.get("startDate").toString()),
      LocalDate.parse(trip.get("endDate").toString())
    );
    List<Integer> result = new ArrayList<>();
    for (int day = 0; day < last; day++) {
      final int selected = day;
      var visits = items
        .stream()
        .filter(i -> RouteTiming.number(i, "dayIndex") == selected)
        .toList();
      if (
        !visits.isEmpty() && region.equals(visits.getLast().get("region"))
      ) result.add(day);
    }
    return result;
  }

  private void validateRegion(
    Map<String, Object> trip,
    List<Map<String, Object>> items,
    String region,
    String query
  ) {
    if (
      region == null ||
      query == null ||
      query.length() > 100 ||
      !json.strings(trip.get("regions").toString()).contains(region) ||
      nights(trip, items, region).isEmpty()
    ) throw DomainException.bad(
      "여행 중 숙박할 지역과 검색어를 확인해 주세요. 당일치기에는 숙소를 추가할 수 없어요."
    );
  }

  public List<Place> candidates(
    String id,
    String member,
    String region,
    String query
  ) {
    var trip = trips.access(id, member);
    var items = mapper.items(id);
    validateRegion(trip, items, region, query);
    var current = mapper
      .lodgings(id)
      .stream()
      .filter(h -> region.equals(h.get("region")))
      .findFirst();
    return catalog
      .search(region, query.trim(), "숙소")
      .stream()
      .filter(p -> p.category().equals("숙소") && p.region().equals(region))
      .filter(
        p -> current.isEmpty() || !p.id().equals(current.get().get("placeId"))
      )
      .sorted(Comparator.comparingInt(p -> estimate(trip, items, region, p)))
      .limit(15)
      .toList();
  }

  public Preview preview(String id, String member, Change change) {
    var trip = trips.access(id, member);
    if (
      RouteTiming.number(trip, "version") != change.version()
    ) throw new DomainException(
      409,
      "다른 변경이 먼저 저장됐어요. 창을 닫고 최신 일정에서 다시 비교해 주세요."
    );
    var items = mapper.items(id);
    validateRegion(trip, items, change.region(), change.query());
    // 화면에서 넘어온 이름·좌표·가격은 받지 않고 같은 검색 조건의 서버 후보와 ID를 대조합니다.
    // 개인 찜은 본인의 목록에서 현재 장소를 확인합니다. 일반 검색 첫 결과에 없더라도 임의 ID를 허용하지 않습니다.
    if (
      change.usesFavorite() && !change.query().isBlank()
    ) throw DomainException.bad(
      "찜한 숙소는 저장한 장소 정보로 다시 확인해 주세요."
    );
    var choices = change.usesFavorite()
      ? favorites.resolveLodgings(
          member,
          List.of(change.placeId()),
          List.of(change.region())
        )
      : candidates(id, member, change.region(), change.query());
    var candidate = choices
      .stream()
      .filter(p -> p.id().equals(change.placeId()))
      .findFirst()
      .orElseThrow(() ->
        DomainException.bad(
          "현재 선택 가능한 숙소가 아니에요. 후보를 다시 검색해 주세요."
        )
      );
    var hotels = mapper.lodgings(id);
    var current = hotels
      .stream()
      .filter(h -> change.region().equals(h.get("region")))
      .findFirst();
    if (
      current.isPresent() && candidate.id().equals(current.get().get("placeId"))
    ) throw DomainException.bad(
      "이미 이 여행의 기준 숙소예요. 다른 후보를 골라 주세요."
    );
    var before = current.map(this::hotelPlace).orElse(null);
    List<Leg> legs = new ArrayList<>();
    for (int day : nights(trip, items, change.region())) {
      var today = items
        .stream()
        .filter(i -> RouteTiming.number(i, "dayIndex") == day)
        .toList();
      var tomorrow = items
        .stream()
        .filter(i -> RouteTiming.number(i, "dayIndex") == day + 1)
        .toList();
      addLeg(legs, trip, day, "INBOUND", today.getLast(), before, candidate);
      if (!tomorrow.isEmpty()) addLeg(
        legs,
        trip,
        day + 1,
        "OUTBOUND",
        tomorrow.getFirst(),
        before,
        candidate
      );
    }
    int afterTotal = legs.stream().mapToInt(Leg::afterMinutes).sum();
    Integer beforeTotal =
      before == null ? null : legs.stream().mapToInt(Leg::beforeMinutes).sum();
    int cleared = current
      .map(
        h ->
          (int) trips
            .routeTimings(id, items, hotels)
            .stream()
            .filter(t -> LodgingStay.uses(t, h.get("placeId").toString()))
            .count()
      )
      .orElse(0);
    List<String> conflicts = new ArrayList<>();
    if (
      current.isPresent() &&
      List.of("USER_CONFIRMED", "PARTNER_CONFIRMED").contains(
        current.get().get("bookingStatus")
      )
    ) conflicts.add(
      "예약 완료로 기록한 숙소예요. 실제 예약처에서 변경·취소 여부를 확인하고 기존 숙소의 예약 상태를 먼저 정리해 주세요."
    );
    return new Preview(
      candidate,
      before == null ? null : before.name(),
      legs,
      beforeTotal,
      afterTotal,
      beforeTotal == null ? null : afterTotal - beforeTotal,
      cleared,
      current.isPresent() && current.get().get("checkInDate") != null,
      List.copyOf(conflicts),
      conflicts.isEmpty()
    );
  }

  /** 적용 시 후보·버전·예약 보호를 다시 계산합니다. 미리보기 응답을 저장 명령으로 신뢰하지 않습니다. */
  @Transactional
  public Object apply(String id, String member, Change change) {
    var result = preview(id, member, change);
    if (!result.canApply()) throw new DomainException(
      409,
      result.conflicts().getFirst()
    );
    return trips.applyLodging(id, member, change.version(), result.candidate());
  }

  private int estimate(
    Map<String, Object> trip,
    List<Map<String, Object>> items,
    String region,
    Place hotel
  ) {
    int sum = 0;
    for (int day : nights(trip, items, region)) {
      var today = items
        .stream()
        .filter(i -> RouteTiming.number(i, "dayIndex") == day)
        .toList();
      var tomorrow = items
        .stream()
        .filter(i -> RouteTiming.number(i, "dayIndex") == day + 1)
        .toList();
      sum += planner.estimateMinutes(
        visitPlace(today.getLast()),
        hotel,
        trip.get("transport").toString()
      );
      if (!tomorrow.isEmpty()) sum += planner.estimateMinutes(
        hotel,
        visitPlace(tomorrow.getFirst()),
        trip.get("transport").toString()
      );
    }
    return sum;
  }

  private void addLeg(
    List<Leg> legs,
    Map<String, Object> trip,
    int day,
    String direction,
    Map<String, Object> visit,
    Place before,
    Place after
  ) {
    String mode = trip.get("transport").toString();
    Place place = visitPlace(visit);
    legs.add(
      new Leg(
        day,
        direction,
        place.name(),
        before == null ? null : planner.estimateMinutes(before, place, mode),
        planner.estimateMinutes(after, place, mode)
      )
    );
  }

  private Place hotelPlace(Map<String, Object> hotel) {
    return new Place(
      hotel.get("placeId").toString(),
      hotel.get("name").toString(),
      hotel.get("region").toString(),
      "숙소",
      ((Number) hotel.get("latitude")).doubleValue(),
      ((Number) hotel.get("longitude")).doubleValue(),
      0,
      RouteTiming.number(hotel, "nightlyEstimate"),
      true,
      hotel.get("source").toString(),
      hotel.get("placeUrl").toString()
    );
  }

  private Place visitPlace(Map<String, Object> visit) {
    return new Place(
      visit.get("placeId").toString(),
      visit.get("placeName").toString(),
      visit.get("region").toString(),
      visit.get("category").toString(),
      ((Number) visit.get("latitude")).doubleValue(),
      ((Number) visit.get("longitude")).doubleValue(),
      0,
      0,
      true,
      visit.get("source").toString(),
      visit.get("placeUrl").toString()
    );
  }
}
