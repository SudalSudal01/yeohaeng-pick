package kr.yeohaengpick.trip;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import kr.yeohaengpick.catalog.Place;
import kr.yeohaengpick.catalog.TravelRegions;
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
    // 추천·저장·공개 일정 가져오기 모두 같은 지역 계약을 검사하며 외부 조회 전에 거절합니다.
    r.regions().forEach(TravelRegions::require);
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
        pool.sort(
          Comparator.<Place>comparingDouble(p ->
            score(p, r, v, aiOrder)
          ).thenComparing(Place::id)
        );
        if (pool.isEmpty()) throw DomainException.bad(
          region +
            "에 일정을 구성할 장소가 부족해요. 여행 일수를 줄이거나 지역을 추가해 주세요."
        );
        List<Place> selected = new ArrayList<>();
        Place anchor = lodgings
          .stream()
          .filter(p -> p.region().equals(region))
          .findFirst()
          .orElse(null);
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
            .min(
              Comparator.<Place>comparingDouble(
                p ->
                  score(p, r, v, aiOrder) +
                  proximity(p, selected, anchor) *
                    distanceWeight(r.transport(), v)
              ).thenComparing(Place::id)
            )
            .ifPresent(selected::add);
        }
        if (selected.isEmpty()) selected.add(pool.getFirst());
        if (variant == 2 && pool.size() > selected.size()) pool
          .stream()
          .filter(p -> !selected.contains(p))
          .min(
            Comparator.<Place>comparingDouble(
              p ->
                score(p, r, v, aiOrder) +
                proximity(p, selected, anchor) *
                  distanceWeight(r.transport(), v)
            ).thenComparing(Place::id)
          )
          .ifPresent(selected::add);
        // 실제 검색은 같은 분류의 비용을 동일한 추정치로 반환합니다. 가격 가중치만으로는
        // 첫 두 코스가 같아지므로, 겹치는 날에는 조회된 같은 분류의 대안 하나를 선택합니다.
        // 여행 안에서 이미 방문한 장소는 pool에서 제외되어 날짜 간 중복도 발생하지 않습니다.
        List<Place> distinct = diversify(
          selected,
          pool,
          courses,
          day,
          r,
          v,
          aiOrder
        );
        // 지리상 가까운 후보부터 연결합니다. 최종 이동 시간은 API 조회 전까지 추정으로 표시합니다.
        List<Place> ordered = nearest(distinct, anchor, r.transport());
        int time = 10 * 60;
        Place previous = anchor;
        int order = 0;
        for (int i = 0; i < ordered.size(); i++) {
          Place p = ordered.get(i);
          // 제외된 장소를 경유한 것으로 시간을 더하지 않습니다. 실제 채택한 마지막 장소에서 계산합니다.
          int arrival =
            time +
            (previous == null
              ? 0
              : estimateMinutes(previous, p, r.transport()));
          if (arrival + p.duration() > 21 * 60) continue;
          stops.add(
            new Stop(
              UUID.randomUUID().toString(),
              day,
              order++,
              p,
              String.format("%02d:%02d", arrival / 60, arrival % 60),
              false,
              "NONE"
            )
          );
          time = arrival + p.duration() + 20;
          previous = p;
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
      if (!r.favoriteIds().isEmpty()) {
        var included = stops
          .stream()
          .map(s -> s.place().id())
          .collect(java.util.stream.Collectors.toSet());
        var omitted = places
          .stream()
          .filter(
            p -> r.favoriteIds().contains(p.id()) && !included.contains(p.id())
          )
          .map(Place::name)
          .toList();
        warnings.add(
          "선택한 찜 " +
            r.favoriteIds().size() +
            "곳 중 " +
            (r.favoriteIds().size() - omitted.size()) +
            "곳을 반영했어요. 찜은 우선 고려하며 분류 균형·이동·시간 제약에 따라 빠질 수 있어요."
        );
        if (!omitted.isEmpty()) warnings.add(
          "이 코스에 포함되지 않은 찜: " + String.join(" · ", omitted)
        );
      }
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
            "새로운 장소와 경험을 더 담아"
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
    // 후보 부족 또는 시간 제약으로 끝내 같은 구성이 나온 경우에는 차이를 꾸미지 않습니다.
    // 중복된 양쪽 카드에 안내를 넣어 어느 코스를 선택해도 비교의 한계를 알 수 있게 합니다.
    return courses
      .stream()
      .map(c -> {
        boolean duplicate = courses
          .stream()
          .anyMatch(other -> other != c && visits(other).equals(visits(c)));
        if (!duplicate) return c;
        var warnings = new ArrayList<>(c.warnings());
        warnings.add(
          "일부 코스의 방문 구성이 같아요. 후보나 방문 가능 시간이 제한되어 다른 구성을 만들지 못했어요. 여행 조건을 바꿔 확인해 주세요."
        );
        return new Course(
          c.variant(),
          c.title(),
          c.subtitle(),
          c.engine(),
          c.items(),
          c.estimatedCost(),
          c.travelMinutes(),
          warnings,
          c.lodgings(),
          c.lodgingEstimate()
        );
      })
      .toList();
  }

  /**
   * 같은 날의 장소 집합이 앞 코스와 같을 때만 최소 점수 차이의 대안으로 교체합니다.
   * 분류별 균형과 AI 평가·비용 점수를 유지하며, 이름이나 순서만 바꾼 코스를 만들지 않습니다.
   * 대안도 최종 동선/종료 시간 검사를 거치므로, 시간 제한으로 다시 겹치면 경고로 알립니다.
   */
  private List<Place> diversify(
    List<Place> selected,
    List<Place> pool,
    List<Course> courses,
    int day,
    TripRequest r,
    int variant,
    List<String> aiOrder
  ) {
    List<Set<String>> earlier = courses
      .stream()
      .<Set<String>>map(c ->
        new HashSet<>(
          c
            .items()
            .stream()
            .filter(s -> s.dayIndex() == day)
            .map(s -> s.place().id())
            .toList()
        )
      )
      .toList();
    Set<String> ids = new HashSet<>(selected.stream().map(Place::id).toList());
    if (!earlier.contains(ids)) return selected;
    List<Place> best = selected;
    double bestDelta = Double.POSITIVE_INFINITY;
    for (int i = 0; i < selected.size(); i++) {
      Place current = selected.get(i);
      // 세 코스를 다르게 보이게 만들기 위해 사용자가 고른 찜을 빼지 않습니다. 차이를 못 만들면 기존 경고로 알립니다.
      if (r.favoriteIds().contains(current.id())) continue;
      for (Place alternative : pool) {
        if (
          ids.contains(alternative.id()) ||
          !slot(current).equals(slot(alternative))
        ) continue;
        Set<String> changed = new HashSet<>(ids);
        changed.remove(current.id());
        changed.add(alternative.id());
        if (earlier.contains(changed)) continue;
        double delta =
          score(alternative, r, variant, aiOrder) -
          score(current, r, variant, aiOrder) +
          (proximity(
            alternative,
            selected
              .stream()
              .filter(p -> p != current)
              .toList(),
            null
          ) -
            proximity(
              current,
              selected
                .stream()
                .filter(p -> p != current)
                .toList(),
              null
            )) *
            distanceWeight(r.transport(), variant);
        if (delta < bestDelta) {
          bestDelta = delta;
          best = new ArrayList<>(selected);
          best.set(i, alternative);
        }
      }
    }
    return best;
  }

  /** 자연·체험은 기존 관광 방문 슬롯에 포함하되 식사·카페·전시 슬롯은 유지합니다. */
  private String slot(Place place) {
    return List.of("관광", "자연", "체험").contains(place.category())
      ? "관광"
      : place.category();
  }

  /** 순서만 다른 동일 방문 구성을 구별하려고 날짜와 장소 ID의 집합으로 비교합니다. */
  private Set<String> visits(Course course) {
    return new HashSet<>(
      course
        .items()
        .stream()
        .map(s -> s.dayIndex() + ":" + s.place().id())
        .toList()
    );
  }

  private double score(Place p, TripRequest r, int variant, List<String> ai) {
    double preference = r.preferences().contains(p.category()) ? -100 : 0;
    // 찜은 강제 방문/예약 고정이 아닙니다. 기존 분류 균형과 시간 검사 안에서 우선 평가합니다.
    if (r.favoriteIds().contains(p.id())) preference -= 250;
    int index = ai.indexOf(p.id());
    if (index >= 0) preference -= 50.0 / (index + 1);
    return (
      preference +
      (variant == 0 ? p.cost() / 1000.0 : variant == 2 ? -p.cost() / 2000.0 : 0)
    );
  }

  /** 장소를 다 고른 후 정렬하는 것만으로는 먼 후보를 줄일 수 없어 선택 단계부터 거리 부담을 반영합니다. */
  private double proximity(Place p, List<Place> selected, Place anchor) {
    double total = selected
      .stream()
      .mapToDouble(other -> distance(p, other))
      .sum();
    int count = selected.size();
    if (anchor != null) {
      total += distance(p, anchor);
      count++;
    }
    return count == 0 ? 0 : total / count;
  }

  private double distanceWeight(String mode, int variant) {
    return (
      (mode.equals("WALK") ? 28 : mode.equals("CAR") ? 8 : 14) *
      (variant == 0 ? 1.3 : variant == 2 ? 0.8 : 1)
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
