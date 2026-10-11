package kr.yeohaengpick.trip;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import kr.yeohaengpick.catalog.Place;
import kr.yeohaengpick.common.DomainException;
import org.springframework.stereotype.Component;

/** 일정 계산 엔진. AI가 반환한 장소 순서도 여기서 날짜·중복·예산을 재검증한 뒤 사용합니다. */
@Component
public class Planner {

  public record Stop(
    String id,
    int dayIndex,
    int sortOrder,
    Place place,
    String startTime,
    boolean fixed,
    String bookingStatus
  ) {}

  public record Course(
    int variant,
    String title,
    String subtitle,
    String engine,
    List<Stop> items,
    int estimatedCost,
    int travelMinutes,
    List<String> warnings,
    List<Place> lodgings,
    int lodgingEstimate
  ) {}

  public void validate(TripRequest r) {
    long days = ChronoUnit.DAYS.between(r.startDate(), r.endDate()) + 1;
    if (
      days < 1 ||
      days > 14 ||
      new HashSet<>(r.regions()).size() != r.regions().size() ||
      r.regions().size() > days
    ) throw DomainException.bad(
      "여행은 1~14일로 설정하고, 지역 수는 여행 일수 이하로 선택해 주세요."
    );
  }

  public List<Course> build(
    TripRequest r,
    List<Place> places,
    List<String> aiOrder,
    String engine
  ) {
    validate(r);
    List<Course> courses = new ArrayList<>();
    int days = (int) ChronoUnit.DAYS.between(r.startDate(), r.endDate()) + 1;
    List<Place> lodgings = lodgings(r, places);
    for (int variant = 0; variant < 3; variant++) {
      List<Stop> stops = new ArrayList<>();
      Set<String> used = new HashSet<>();
      for (int day = 0; day < days; day++) {
        String region = r
          .regions()
          .get(
            Math.min(r.regions().size() - 1, (day * r.regions().size()) / days)
          );
        final int v = variant;
        List<Place> pool = new ArrayList<>(
          places
            .stream()
            .filter(
              p ->
                p.region().equals(region) &&
                !p.category().equals("숙소") &&
                !used.contains(p.id())
            )
            .toList()
        );
        pool.sort(Comparator.comparingDouble(p -> score(p, r, v, aiOrder)));
        if (pool.isEmpty()) throw DomainException.bad(
          region +
            "에 일정을 구성할 장소가 부족해요. 여행 일수를 줄이거나 지역을 추가해 주세요."
        );
        List<Place> selected = new ArrayList<>();
        // 맛집·카페와 방문 장소의 균형을 잡고, 동일 여행 안에서는 같은 장소를 반복하지 않습니다.
        for (String category : List.of("관광", "맛집", "카페", "전시")) {
          pool
            .stream()
            .filter(p ->
              category.equals("관광")
                ? List.of("관광", "자연", "체험").contains(p.category())
                : p.category().equals(category)
            )
            .filter(p -> !selected.contains(p))
            .findFirst()
            .ifPresent(selected::add);
        }
        if (selected.isEmpty()) selected.add(pool.getFirst());
        if (variant == 2 && pool.size() > selected.size()) pool
          .stream()
          .filter(p -> !selected.contains(p))
          .findFirst()
          .ifPresent(selected::add);
        // 지리상 가까운 후보부터 연결합니다. 최종 이동 시간은 API 조회 전까지 추정으로 표시합니다.
        Place anchor = lodgings
          .stream()
          .filter(p -> p.region().equals(region))
          .findFirst()
          .orElse(null);
        List<Place> ordered = nearest(selected, anchor, r.transport());
        int time =
          10 * 60 +
          (anchor == null
            ? 0
            : estimateMinutes(anchor, ordered.getFirst(), r.transport()));
        for (int i = 0; i < ordered.size(); i++) {
          Place p = ordered.get(i);
          if (i > 0) time += estimateMinutes(
            ordered.get(i - 1),
            p,
            r.transport()
          );
          if (time + p.duration() > 21 * 60) continue;
          stops.add(
            new Stop(
              UUID.randomUUID().toString(),
              day,
              i,
              p,
              String.format("%02d:%02d", time / 60, time % 60),
              false,
              "NONE"
            )
          );
          time += p.duration() + 20;
          used.add(p.id());
        }
      }
      int cost = stops
        .stream()
        .mapToInt(s -> s.place.cost() * r.partySize())
        .sum();
      int travel = travelMinutes(stops, r.transport());
      // 숙소를 선택했다면 매일 숙소에서 첫 장소로 이동하는 시간도 비교 합계에 포함합니다.
      for (int day = 0; day < days; day++) {
        final int index = day;
        var first = stops
          .stream()
          .filter(s -> s.dayIndex() == index)
          .findFirst();
        if (first.isPresent()) travel += lodgings
          .stream()
          .filter(p -> p.region().equals(first.get().place().region()))
          .mapToInt(p -> estimateMinutes(p, first.get().place(), r.transport()))
          .sum();
      }
      List<String> warnings = new ArrayList<>(
        List.of(
          "장소 비용·이동 시간은 추정이며 운영시간과 예약 가능 여부는 방문 전 확인이 필요해요.",
          "출발지 왕복 교통과 숙박비는 별도예요. 표시 금액은 일정 내 활동비 합계예요."
        )
      );
      if (cost > r.budget()) warnings.add(
        "활동비만으로 설정 예산을 " + (cost - r.budget()) + "원 초과해요."
      );
      int stayCost = lodgingEstimate(r, lodgings, days);
      if (stayCost > 0 && cost + stayCost > r.budget()) warnings.add(
        "활동비와 숙소 1객실 예시를 합하면 예산을 " +
          (cost + stayCost - r.budget()) +
          "원 초과해요. 실제 객실 수와 교통비는 별도 확인해 주세요."
      );
      if (
        places.stream().anyMatch(p -> p.source().equals("KAKAO"))
      ) warnings.add(
        "장소 검색 API가 제공하지 않는 입장료·객실 요금은 합계에 포함되지 않아요. 무료라는 의미가 아니에요."
      );
      if (!lodgings.isEmpty()) warnings.add(
        "선택한 숙소 좌표를 기준으로 첫 장소를 정했어요. 숙소 요금은 예시·추정이며 실제 객실 가격과 재고는 예약 사이트에서 확인해 주세요."
      );
      if (r.lodging() != null && !r.lodging().isBlank()) warnings.add(
        "직접 입력한 숙소 메모는 좌표가 확인되지 않아 동선에는 반영되지 않아요. 검색 후보를 선택하면 반영돼요."
      );
      if (r.regions().size() > 1) warnings.add(
        "지역 사이 장거리 이동은 별도예요. 실제 열차·버스 시간에 맞춰 방문 일정을 확인해 주세요."
      );
      courses.add(
        new Course(
          variant,
          List.of(
            "가볍게, 여유롭게",
            "우리 취향의 균형",
            "조금 더 특별하게"
          ).get(variant),
          List.of(
            "부담 적은 장소를 중심으로",
            "취향과 이동의 균형을 고려해",
            "유료 전시와 새로운 경험까지"
          ).get(variant),
          engine,
          stops,
          cost,
          travel,
          warnings,
          lodgings,
          lodgingEstimate(r, lodgings, days)
        )
      );
    }
    return courses;
  }

  private double score(Place p, TripRequest r, int variant, List<String> ai) {
    double preference = r.preferences().contains(p.category()) ? -100 : 0;
    int index = ai.indexOf(p.id());
    if (index >= 0) preference -= 50.0 / (index + 1);
    return (
      preference +
      (variant == 0
        ? p.cost() / 1000.0
        : variant == 2
          ? -p.cost() / 2000.0
          : 0) +
      Math.abs(p.id().hashCode() % 13)
    );
  }

  /** 서버가 조회한 숙소만 사용합니다. 여행당 지역별 한 곳을 선택하며 원문 메모와 구분합니다. */
  public List<Place> lodgings(TripRequest r, List<Place> places) {
    var selected = places
      .stream()
      .filter(
        p -> r.lodgingIds().contains(p.id()) && p.category().equals("숙소")
      )
      .toList();
    if (
      selected.size() != r.lodgingIds().size() ||
      selected.stream().map(Place::region).distinct().count() != selected.size()
    ) throw DomainException.bad(
      "숙소는 지역별로 검색된 후보 한 곳을 선택해 주세요."
    );
    return selected;
  }

  private int lodgingEstimate(TripRequest r, List<Place> lodgings, int days) {
    int sum = 0;
    for (int day = 0; day < days - 1; day++) {
      String region = r
        .regions()
        .get(
          Math.min(r.regions().size() - 1, (day * r.regions().size()) / days)
        );
      sum += lodgings
        .stream()
        .filter(p -> p.region().equals(region))
        .mapToInt(Place::cost)
        .sum();
    }
    return sum;
  }

  private List<Place> nearest(List<Place> places, Place anchor, String mode) {
    List<Place> rest = new ArrayList<>(places),
      ordered = new ArrayList<>();
    if (rest.isEmpty()) return rest;
    var firstPool = rest
      .stream()
      .filter(p -> !p.category().equals("맛집"))
      .toList();
    if (firstPool.isEmpty()) firstPool = List.copyOf(rest);
    Place first =
      anchor == null
        ? firstPool.getFirst()
        : firstPool
            .stream()
            .min(Comparator.comparingDouble(p -> distance(anchor, p)))
            .orElseThrow();
    ordered.add(first);
    rest.remove(first);
    int time =
      10 * 60 +
      (anchor == null ? 0 : estimateMinutes(anchor, first, mode)) +
      first.duration() +
      20;
    while (!rest.isEmpty()) {
      Place prev = ordered.getLast();
      // 거리만 최적화하면 점심이 늦어집니다. 정오 부근에는 남은 식사 후보를 먼저 연결합니다.
      var meal = rest
        .stream()
        .filter(p -> p.category().equals("맛집"))
        .toList();
      var nonMeal = rest
        .stream()
        .filter(p -> !p.category().equals("맛집"))
        .toList();
      var eligible =
        time >= 11 * 60 + 30 && !meal.isEmpty()
          ? meal
          : !nonMeal.isEmpty()
            ? nonMeal
            : rest;
      Place next = eligible
        .stream()
        .min(Comparator.comparingDouble(p -> distance(prev, p)))
        .orElseThrow();
      ordered.add(next);
      rest.remove(next);
      time += estimateMinutes(prev, next, mode) + next.duration() + 20;
    }
    return ordered;
  }

  public double distance(Place a, Place b) {
    double lat = Math.toRadians(b.latitude() - a.latitude()),
      lon = Math.toRadians(b.longitude() - a.longitude());
    double h =
      Math.pow(Math.sin(lat / 2), 2) +
      Math.cos(Math.toRadians(a.latitude())) *
        Math.cos(Math.toRadians(b.latitude())) *
        Math.pow(Math.sin(lon / 2), 2);
    return 6371 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
  }

  public int estimateMinutes(Place a, Place b, String mode) {
    return Math.max(
      5,
      (int) Math.ceil(
        ((distance(a, b) * 1.4) /
          (mode.equals("WALK") ? 4 : mode.equals("CAR") ? 30 : 18)) *
          60
      ) + (mode.equals("TRANSIT") ? 10 : 0)
    );
  }

  public int travelMinutes(List<Stop> stops, String mode) {
    int total = 0;
    for (int i = 1; i < stops.size(); i++) if (
      stops.get(i).dayIndex == stops.get(i - 1).dayIndex
    ) total += estimateMinutes(
      stops.get(i - 1).place,
      stops.get(i).place,
      mode
    );
    return total;
  }
}
