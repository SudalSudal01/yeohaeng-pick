package kr.yeohaengpick.trip;

import java.util.*;
import kr.yeohaengpick.catalog.*;
import kr.yeohaengpick.common.*;
import kr.yeohaengpick.integration.AiRankingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 여행 생성·공동 편집·변경 미리보기를 담당하는 서비스. 권한 검사는 프론트 표시 여부와 무관하게 매 요청에 수행합니다. */
@Service
public class TripService {

  private final TripMapper trips;
  private final CatalogService catalog;
  private final Planner planner;
  private final JsonCodec json;
  private final AiRankingService ai;
  private final BookingSchedule schedule;

  public TripService(
    TripMapper t,
    CatalogService c,
    Planner p,
    JsonCodec j,
    AiRankingService a,
    BookingSchedule schedule
  ) {
    trips = t;
    catalog = c;
    planner = p;
    json = j;
    ai = a;
    this.schedule = schedule;
  }

  public Map<String, Object> access(String id, String member) {
    var trip = trips.get(id);
    if (trip == null) throw new DomainException(404, "여행을 찾을 수 없어요.");
    if (trips.access(id, member) == 0) throw new DomainException(
      403,
      "초대를 수락한 동행만 이 여행에 접근할 수 있어요."
    );
    return trip;
  }

  public List<Planner.Course> recommend(TripRequest request) {
    planner.validate(request);
    var candidates = catalog.candidates(request.regions());
    var ranking = ai.rank(request, candidates);
    return planner.build(request, candidates, ranking.ids(), ranking.engine());
  }

  public record Create(
    @jakarta.validation.Valid
    @jakarta.validation.constraints.NotNull
    TripRequest conditions,
    @jakarta.validation.constraints.NotEmpty
    @jakarta.validation.constraints.Size(max = 100)
    List<Planner.Stop> items
  ) {}

  @Transactional
  public Object create(Create create, String member) {
    TripRequest r = create.conditions;
    planner.validate(r);
    if (
      create.items == null ||
      create.items.isEmpty() ||
      create.items.size() > 100
    ) throw DomainException.bad("저장할 일정을 먼저 선택해 주세요.");
    // 클라이언트가 전달한 값은 신뢰하지 않습니다. 장소 ID·좌표·가격을 서버 후보 목록과 대조합니다.
    var candidates = catalog.candidates(r.regions());
    var available = candidates
      .stream()
      .collect(java.util.stream.Collectors.toMap(Place::id, p -> p));
    var lodgingPlaces = planner.lodgings(r, candidates);
    String id = UUID.randomUUID().toString();
    var t = new HashMap<String, Object>();
    t.put("id", id);
    t.put("ownerId", member);
    t.put("title", r.title());
    t.put("regions", json.write(r.regions()));
    t.put("startDate", r.startDate());
    t.put("endDate", r.endDate());
    t.put("origin", r.origin());
    t.put("partySize", r.partySize());
    t.put("budget", r.budget());
    t.put("transport", r.transport());
    t.put("preferences", json.write(r.preferences()));
    t.put("lodging", Objects.requireNonNullElse(r.lodging(), ""));
    trips.insert(t);
    trips.member(id, member);
    int days =
      (int) java.time.temporal.ChronoUnit.DAYS.between(
        r.startDate(),
        r.endDate()
      ) + 1;
    Set<String> unique = new HashSet<>();
    for (var stop : create.items) {
      if (
        stop == null ||
        stop.place() == null ||
        stop.place().id() == null ||
        stop.startTime() == null
      ) throw DomainException.bad("일정 데이터가 누락됐어요.");
      Place p = available.get(stop.place().id());
      if (
        p == null ||
        stop.dayIndex() < 0 ||
        stop.dayIndex() >= days ||
        !unique.add(p.id()) ||
        !stop.startTime().matches("(?:[01]\\d|2[0-3]):[0-5]\\d")
      ) throw DomainException.bad(
        "일정 데이터가 유효하지 않아요. 코스를 다시 선택해 주세요."
      );
      trips.item(
        item(
          id,
          new Planner.Stop(
            UUID.randomUUID().toString(),
            stop.dayIndex(),
            stop.sortOrder(),
            p,
            stop.startTime(),
            false,
            "NONE"
          )
        )
      );
    }
    for (var lodging : lodgingPlaces) trips.lodging(id, lodging);
    for (String task : List.of(
      "교통편 예약 확인하기",
      "숙소 체크인 시간 확인하기",
      "날씨와 준비물 확인하기"
    ))
      trips.addTask(UUID.randomUUID().toString(), id, task);
    return detail(id, member);
  }

  public List<Map<String, Object>> list(String member) {
    return trips.list(member).stream().map(this::normalize).toList();
  }

  public Map<String, Object> detail(String id, String member) {
    var result = normalize(access(id, member));
    result.remove("shareToken");
    var items = trips.items(id);
    var lodgings = trips.lodgings(id);
    var reserved = trips.externalBookings(id);
    var timings = routeTimings(id, items, lodgings);
    result.put("items", items);
    result.put(
      "members",
      trips
        .members(id)
        .stream()
        .map(m -> {
          m.put("preferences", json.strings((String) m.get("preferences")));
          return m;
        })
        .toList()
    );
    result.put("tasks", trips.tasks(id));
    result.put("expenses", trips.expenses(id));
    result.put("history", trips.historyList(id));
    result.put("lodgings", lodgings);
    result.put("routeTimings", timings);
    result.put("externalBookings", reserved);
    result.put(
      "schedule",
      schedule.inspect(
        java.time.LocalDate.parse(result.get("startDate").toString()),
        items,
        reserved,
        timings
      )
    );
    return result;
  }

  /** 서버가 생성한 JSON 기록 중 현재의 연속 구간에 일치하는 기록만 반환합니다. */
  public List<RouteTiming> routeTimings(
    String id,
    List<Map<String, Object>> items,
    List<Map<String, Object>> lodgings
  ) {
    return trips
      .routeTimings(id)
      .stream()
      .map(s -> json.read(s, RouteTiming.class))
      .filter(t -> t.applies(items, lodgings))
      .toList();
  }

  @Transactional
  public Object applyRouteTiming(
    String id,
    String member,
    int version,
    RouteSchedule.Plan plan
  ) {
    access(id, member);
    revision(id, version, member, "조회 경로 반영 · " + plan.title());
    replace(id, plan.items());
    trips.clearRouteTiming(id, plan.timing().toItemId());
    trips.routeTiming(id, plan.timing().toItemId(), json.write(plan.timing()));
    return detail(id, member);
  }

  /** 조회값 반영을 해제해도 현재 방문 시각은 유지합니다. 복원 시에는 남은 조회값과 예약을 다시 검사합니다. */
  @Transactional
  public Object removeRouteTiming(
    String id,
    String member,
    int version,
    String itemId
  ) {
    access(id, member);
    if (
      routeTimings(id, trips.items(id), trips.lodgings(id))
        .stream()
        .noneMatch(t -> t.toItemId().equals(itemId))
    ) throw new DomainException(404, "반영한 경로 기록을 찾을 수 없어요.");
    revision(id, version, member, "조회 경로 시간 반영 해제");
    trips.clearRouteTiming(id, itemId);
    return detail(id, member);
  }

  private Map<String, Object> normalize(Map<String, Object> source) {
    var t = new HashMap<>(source);
    t.remove("shareToken");
    t.put("regions", json.strings((String) t.get("regions")));
    t.put("preferences", json.strings((String) t.get("preferences")));
    return t;
  }

  private Map<String, Object> item(String trip, Planner.Stop s) {
    Place p = s.place();
    var m = new HashMap<String, Object>();
    m.put("id", s.id());
    m.put("tripId", trip);
    m.put("dayIndex", s.dayIndex());
    m.put("sortOrder", s.sortOrder());
    m.put("placeId", p.id());
    m.put("placeName", p.name());
    m.put("region", p.region());
    m.put("category", p.category());
    m.put("latitude", p.latitude());
    m.put("longitude", p.longitude());
    m.put("startTime", s.startTime());
    m.put("duration", p.duration());
    m.put("cost", p.cost());
    m.put("indoor", p.indoor());
    m.put("source", p.source());
    m.put("placeUrl", p.url());
    m.put("fixed", s.fixed());
    m.put("bookingStatus", s.bookingStatus());
    m.put("bookingReason", "");
    return m;
  }

  private Place place(Map<String, Object> m) {
    return new Place(
      (String) m.get("placeId"),
      (String) m.get("placeName"),
      (String) m.get("region"),
      (String) m.get("category"),
      ((Number) m.get("latitude")).doubleValue(),
      ((Number) m.get("longitude")).doubleValue(),
      ((Number) m.get("duration")).intValue(),
      ((Number) m.get("cost")).intValue(),
      (Boolean) m.get("indoor"),
      (String) m.get("source"),
      (String) m.get("placeUrl")
    );
  }

  public List<Place> alternatives(
    String trip,
    String item,
    String member,
    boolean rain
  ) {
    access(trip, member);
    var all = trips.items(trip);
    var current = all
      .stream()
      .filter(i -> i.get("id").equals(item))
      .findFirst()
      .orElseThrow(() -> new DomainException(404, "일정을 찾을 수 없어요."));
    Set<String> used = new HashSet<>(
      all
        .stream()
        .map(i -> (String) i.get("placeId"))
        .toList()
    );
    return catalog
      .search((String) current.get("region"), "")
      .stream()
      .filter(
        p ->
          !used.contains(p.id()) &&
          !p.category().equals("숙소") &&
          (!rain || p.indoor())
      )
      .limit(8)
      .toList();
  }

  public record Change(String itemId, String placeId, int version) {}

  public Map<String, Object> preview(String id, Change change, String member) {
    var trip = access(id, member);
    if (
      ((Number) trip.get("version")).intValue() != change.version
    ) throw new DomainException(
      409,
      "동행이 일정을 변경했어요. 최신 일정을 불러온 뒤 다시 비교해 주세요."
    );
    List<Map<String, Object>> old = trips.items(id);
    List<Map<String, Object>> next = old
      .stream()
      .map(HashMap<String, Object>::new)
      .map(m -> (Map<String, Object>) m)
      .toList();
    var target = next
      .stream()
      .filter(i -> i.get("id").equals(change.itemId))
      .findFirst()
      .orElseThrow(() -> new DomainException(404, "일정을 찾을 수 없어요."));
    if ((Boolean) target.get("fixed")) throw new DomainException(
      409,
      "고정된 일정이에요. 예약 상태와 고정 여부를 먼저 확인해 주세요."
    );
    Place replacement = alternatives(id, change.itemId, member, false)
      .stream()
      .filter(p -> p.id().equals(change.placeId))
      .findFirst()
      .orElseThrow(() ->
        DomainException.bad("현재 선택 가능한 후보가 아니에요.")
      );
    String oldName = (String) target.get("placeName");
    int day = ((Number) target.get("dayIndex")).intValue();
    target.putAll(
      item(
        id,
        new Planner.Stop(
          (String) target.get("id"),
          day,
          ((Number) target.get("sortOrder")).intValue(),
          replacement,
          (String) target.get("startTime"),
          false,
          "NONE"
        )
      )
    );
    boolean affected = false;
    Map<String, Object> previous = null;
    List<String> conflicts = new ArrayList<>();
    var lodgings = trips.lodgings(id);
    var timings = routeTimings(id, next, lodgings);
    for (var current : next) {
      if (((Number) current.get("dayIndex")).intValue() != day) continue;
      if (current.get("id").equals(change.itemId)) affected = true;
      if (affected && previous == null) {
        var anchor = lodgings
          .stream()
          .filter(p -> p.get("region").equals(current.get("region")))
          .findFirst();
        if (anchor.isPresent()) {
          int arrival =
            10 * 60 +
            planner.estimateMinutes(
              lodgingPlace(anchor.get()),
              place(current),
              (String) trip.get("transport")
            );
          var applied = timings
            .stream()
            .filter(
              t ->
                t.fromKind().equals("LODGING") &&
                t.toItemId().equals(current.get("id"))
            )
            .findFirst();
          if (applied.isPresent()) arrival =
            RouteTiming.clock(applied.get().originDeparture()) +
            applied.get().minutes() +
            applied.get().bufferMinutes();
          current.put(
            "startTime",
            String.format("%02d:%02d", arrival / 60, arrival % 60)
          );
        }
      }
      // 바뀐 장소까지의 이동도 다시 계산합니다. 이후 장소만 옮기면 앞 일정과 겹칠 수 있습니다.
      if (affected && previous != null) {
        int arrival =
          time(previous) +
          ((Number) previous.get("duration")).intValue() +
          RouteTiming.gap(
            timings,
            previous,
            current,
            planner,
            (String) trip.get("transport")
          );
        if ((Boolean) current.get("fixed")) {
          if (arrival > time(current)) conflicts.add(
            current.get("placeName") + "의 고정 시간에 도착할 수 없어요."
          );
        } else current.put(
          "startTime",
          String.format("%02d:%02d", arrival / 60, arrival % 60)
        );
        if (
          arrival + ((Number) current.get("duration")).intValue() > 22 * 60
        ) conflicts.add("하루 일정이 너무 늦게 끝나요.");
      }
      previous = current;
    }
    int party = ((Number) trip.get("partySize")).intValue();
    conflicts.addAll(
      RouteTiming.conflicts(
        java.time.LocalDate.parse(trip.get("startDate").toString()),
        next,
        timings,
        schedule
          .inspect(
            java.time.LocalDate.parse(trip.get("startDate").toString()),
            next,
            trips.externalBookings(id)
          )
          .windows()
      )
    );
    schedule
      .inspect(
        java.time.LocalDate.parse(trip.get("startDate").toString()),
        next,
        trips.externalBookings(id)
      )
      .issues()
      .forEach(issue -> conflicts.add(issue.message()));
    int cost = next
      .stream()
      .mapToInt(m -> ((Number) m.get("cost")).intValue() * party)
      .sum();
    int before = old
      .stream()
      .mapToInt(m -> ((Number) m.get("cost")).intValue() * party)
      .sum();
    return Map.of(
      "items",
      next,
      "estimatedCost",
      cost,
      "costDelta",
      cost - before,
      "travelDelta",
      travel(next, (String) trip.get("transport"), lodgings, timings) -
        travel(
          old,
          (String) trip.get("transport"),
          lodgings,
          routeTimings(id, old, lodgings)
        ),
      "canApply",
      conflicts.isEmpty(),
      "conflicts",
      conflicts,
      "summary",
      oldName + " → " + replacement.name()
    );
  }

  private int time(Map<String, Object> m) {
    String[] p = ((String) m.get("startTime")).split(":");
    return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
  }

  private Place lodgingPlace(Map<String, Object> lodging) {
    return new Place(
      (String) lodging.get("placeId"),
      (String) lodging.get("name"),
      (String) lodging.get("region"),
      "숙소",
      ((Number) lodging.get("latitude")).doubleValue(),
      ((Number) lodging.get("longitude")).doubleValue(),
      0,
      0,
      true,
      (String) lodging.get("source"),
      (String) lodging.get("placeUrl")
    );
  }

  private int travel(
    List<Map<String, Object>> items,
    String mode,
    List<Map<String, Object>> lodgings,
    List<RouteTiming> timings
  ) {
    int sum = 0;
    Set<Object> visitedDays = new HashSet<>();
    for (var item : items)
      if (
        visitedDays.add(item.get("dayIndex")) &&
        timings
          .stream()
          .noneMatch(
            t -> t.toItemId().equals(item.get("id")) && t.crossesDays(items)
          )
      ) sum += lodgings
        .stream()
        .filter(p -> p.get("region").equals(item.get("region")))
        .mapToInt(p ->
          timings
            .stream()
            .filter(
              t ->
                t.fromKind().equals("LODGING") &&
                t.toItemId().equals(item.get("id"))
            )
            .mapToInt(RouteTiming::minutes)
            .findFirst()
            .orElseGet(() ->
              planner.estimateMinutes(lodgingPlace(p), place(item), mode)
            )
        )
        .sum();
    for (int i = 1; i < items.size(); i++) if (
      items
        .get(i)
        .get("dayIndex")
        .equals(items.get(i - 1).get("dayIndex"))
    ) {
      var from = items.get(i - 1);
      var to = items.get(i);
      sum += timings
        .stream()
        .filter(
          t ->
            t.fromKind().equals("ITEM") &&
            t.fromId().equals(from.get("id")) &&
            t.toItemId().equals(to.get("id"))
        )
        .mapToInt(RouteTiming::minutes)
        .findFirst()
        .orElseGet(() -> planner.estimateMinutes(place(from), place(to), mode));
    }
    // 날짜 간 직접 이동을 적용했다면 그 조회 분도 합계에 포함합니다. 숙박/대기 시간은 포함하지 않습니다.
    return (
      sum +
      timings
        .stream()
        .filter(t -> t.crossesDays(items))
        .mapToInt(RouteTiming::minutes)
        .sum()
    );
  }

  private void revision(String id, int version, String member, String summary) {
    if (trips.bump(id, version) != 1) throw new DomainException(
      409,
      "다른 변경이 먼저 저장됐어요. 최신 일정을 확인해 주세요."
    );
    trips.history(
      UUID.randomUUID().toString(),
      id,
      member,
      version + 1,
      summary,
      json.write(trips.items(id))
    );
  }

  private void replace(String id, List<Map<String, Object>> items) {
    trips.clearItems(id);
    for (var i : items) trips.item(i);
    // 장소 교체로 무효해진 구간을 삭제합니다. 나중에 이력을 복원해도 과거 경로가 부활하지 않습니다.
    var lodgings = trips.lodgings(id);
    for (var payload : trips.routeTimings(id)) {
      var timing = json.read(payload, RouteTiming.class);
      if (!timing.applies(items, lodgings)) trips.clearRouteTiming(
        id,
        timing.toItemId()
      );
    }
  }

  @SuppressWarnings("unchecked")
  @Transactional
  public Object apply(String id, Change c, String member) {
    var comparison = preview(id, c, member);
    if (!((Boolean) comparison.get("canApply"))) throw new DomainException(
      409,
      "고정 일정과 충돌해 변경할 수 없어요."
    );
    revision(id, c.version, member, (String) comparison.get("summary"));
    replace(id, (List<Map<String, Object>>) comparison.get("items"));
    return detail(id, member);
  }

  public record Booking(
    String itemId,
    String status,
    String reason,
    boolean fixed,
    int version
  ) {}

  /** 숙소 상태는 일정 스냅샷과 별도로 관리하므로 방문 장소를 복원해도 예약 정보가 유지됩니다. */
  @Transactional
  public Object lodgingBooking(String id, Booking b, String member) {
    access(id, member);
    if (
      b.status == null ||
      !List.of(
        "NONE",
        "NEEDS_CONFIRMATION",
        "USER_CONFIRMED",
        "FAILED",
        "CANCELLED"
      ).contains(b.status) ||
      b.reason == null ||
      b.reason.length() > 150
    ) throw DomainException.bad("예약 상태를 확인해 주세요.");
    if (
      trips
        .lodgings(id)
        .stream()
        .noneMatch(p -> p.get("placeId").equals(b.itemId))
    ) throw new DomainException(404, "숙소를 찾을 수 없어요.");
    revision(id, b.version, member, "숙소 예약 상태 변경");
    trips.lodgingBooking(id, b.itemId, b.status, b.reason);
    return detail(id, member);
  }

  @Transactional
  public Object booking(String id, Booking b, String member) {
    access(id, member);
    if (
      !List.of(
        "NONE",
        "NEEDS_CONFIRMATION",
        "USER_CONFIRMED",
        "FAILED",
        "CANCELLED"
      ).contains(b.status) ||
      b.reason == null ||
      b.reason.length() > 150
    ) throw DomainException.bad("예약 상태를 확인해 주세요.");
    var items = trips.items(id);
    var target = items
      .stream()
      .filter(i -> i.get("id").equals(b.itemId))
      .findFirst()
      .orElseThrow(() -> new DomainException(404, "일정을 찾을 수 없어요."));
    revision(
      id,
      b.version,
      member,
      target.get("placeName") + " 예약 상태 변경"
    );
    target.put("bookingStatus", b.status);
    target.put("bookingReason", b.reason);
    target.put("fixed", b.status.equals("USER_CONFIRMED") || b.fixed);
    replace(id, items);
    return detail(id, member);
  }

  @SuppressWarnings("unchecked")
  @Transactional
  public Object undo(String id, String history, int version, String member) {
    var trip = access(id, member);
    var h = trips.historyById(history);
    if (h == null || !h.get("tripId").equals(id)) throw new DomainException(
      404,
      "변경 이력을 찾을 수 없어요."
    );
    List<Map<String, Object>> snapshot = json.read(
      (String) h.get("snapshot"),
      List.class
    );
    for (var current : trips.items(id))
      if ((Boolean) current.get("fixed")) {
        var original = snapshot
          .stream()
          .filter(i -> i.get("id").equals(current.get("id")))
          .findFirst();
        if (
          original.isEmpty() ||
          !original.get().get("placeId").equals(current.get("placeId")) ||
          !original.get().get("startTime").equals(current.get("startTime")) ||
          !original
            .get()
            .get("bookingStatus")
            .equals(current.get("bookingStatus"))
        ) throw new DomainException(
          409,
          "현재 고정된 예약이 바뀌는 복원이어서 적용할 수 없어요."
        );
      }
    if (
      !schedule
        .inspect(
          java.time.LocalDate.parse(trip.get("startDate").toString()),
          snapshot,
          trips.externalBookings(id)
        )
        .issues()
        .isEmpty()
    ) throw new DomainException(
      409,
      "현재 교통 예약의 이동·여유 시간과 겹치는 복원이어서 적용할 수 없어요."
    );
    if (
      !RouteTiming.conflicts(
        java.time.LocalDate.parse(trip.get("startDate").toString()),
        snapshot,
        routeTimings(id, snapshot, trips.lodgings(id)),
        schedule
          .inspect(
            java.time.LocalDate.parse(trip.get("startDate").toString()),
            snapshot,
            trips.externalBookings(id)
          )
          .windows()
      ).isEmpty()
    ) throw new DomainException(
      409,
      "적용한 경로의 이동 시간과 충돌하는 복원이어서 적용할 수 없어요."
    );
    revision(id, version, member, "이전 일정 복원");
    replace(id, snapshot);
    return detail(id, member);
  }

  @Transactional
  public Object share(String id, boolean enable, String member) {
    var t = access(id, member);
    owner(t, member);
    String token = enable
      ? (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace(
          "-",
          ""
        )
      : null;
    trips.share(id, token);
    return Map.of("token", Objects.requireNonNullElse(token, ""));
  }

  public Object shared(String token) {
    String id = trips.byShare(token);
    if (id == null) throw new DomainException(
      404,
      "공유가 종료됐거나 유효하지 않은 링크예요."
    );
    var t = trips.get(id);
    var result = new HashMap<String, Object>();
    for (String k : List.of("title", "startDate", "endDate", "transport"))
      result.put(k, t.get(k));
    result.put("regions", json.strings((String) t.get("regions")));
    result.put(
      "items",
      trips
        .items(id)
        .stream()
        .map(i -> {
          var safe = new HashMap<String, Object>();
          for (String k : List.of(
            "dayIndex",
            "sortOrder",
            "placeName",
            "region",
            "category",
            "startTime",
            "duration",
            "latitude",
            "longitude",
            "placeUrl"
          ))
            safe.put(k, i.get(k));
          return safe;
        })
        .toList()
    );
    return result;
  }

  private void owner(Map<String, Object> t, String member) {
    if (!t.get("ownerId").equals(member)) throw new DomainException(
      403,
      "여행 소유자만 관리할 수 있어요."
    );
  }

  /** 최신 예약 기록을 기준으로 매번 다시 계산합니다. 클라이언트가 전달한 변경 시각은 저장하지 않습니다. */
  public List<BookingSchedule.Plan> schedulePreview(
    String id,
    int version,
    String member
  ) {
    var trip = access(id, member);
    if (
      ((Number) trip.get("version")).intValue() != version
    ) throw new DomainException(
      409,
      "동행이 여행을 변경했어요. 최신 여행에서 다시 비교해 주세요."
    );
    return schedule.alternatives(
      java.time.LocalDate.parse(trip.get("startDate").toString()),
      trips.items(id),
      trips.externalBookings(id),
      (String) trip.get("transport"),
      routeTimings(id, trips.items(id), trips.lodgings(id))
    );
  }

  @Transactional
  public Object scheduleApply(
    String id,
    int version,
    String strategy,
    String member
  ) {
    var plan = schedulePreview(id, version, member)
      .stream()
      .filter(p -> p.strategy().equals(strategy))
      .findFirst()
      .orElseThrow(() ->
        DomainException.bad("일정 조정 방법을 확인해 주세요.")
      );
    if (!plan.canApply()) throw new DomainException(
      409,
      "고정 시간과 하루 범위를 지키는 조정이 어려워요. 예약 시간이나 방문 계획을 먼저 확인해 주세요."
    );
    revision(id, version, member, "교통 예약에 맞춰 " + plan.title());
    replace(id, plan.items());
    return detail(id, member);
  }

  @Transactional
  public void delete(String id, String member) {
    var t = access(id, member);
    owner(t, member);
    trips.delete(id);
  }

  public Object task(
    String id,
    String member,
    String content,
    String taskId,
    Boolean done
  ) {
    access(id, member);
    if (taskId != null) trips.task(taskId, id, Boolean.TRUE.equals(done));
    else {
      if (
        content == null || content.isBlank() || content.length() > 200
      ) throw DomainException.bad("준비할 내용을 200자 이내로 입력해 주세요.");
      trips.addTask(UUID.randomUUID().toString(), id, content.trim());
    }
    return trips.tasks(id);
  }

  public Object expense(String id, String member, String label, int amount) {
    access(id, member);
    if (
      label == null ||
      label.isBlank() ||
      label.length() > 100 ||
      amount <= 0 ||
      amount > 100000000
    ) throw DomainException.bad("지출 내용과 금액을 확인해 주세요.");
    trips.expense(UUID.randomUUID().toString(), id, member, label, amount);
    return trips.expenses(id);
  }
}
