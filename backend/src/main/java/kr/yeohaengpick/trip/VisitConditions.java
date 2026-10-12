package kr.yeohaengpick.trip;

import java.math.BigDecimal;
import java.util.*;
import kr.yeohaengpick.common.DomainException;

/** 사용자 확인 조건의 검증과 공통 충돌 계산입니다. 영업·입장 가능 여부나 결제 성공을 보증하지 않습니다. */
public final class VisitConditions {

  private VisitConditions() {}

  public record Request(
    int version,
    String startTime,
    String endTime,
    BigDecimal cost,
    String note
  ) {}

  public record Values(String start, String end, Integer cost, String note) {}

  /** 한 방문일에 적용할 단일 연속 구간입니다. 휴게 시간·요일·계절은 사용자가 방문 날짜에 맞춰 확인합니다. */
  public static Values validate(Request r, int duration) {
    if (r.version() < 0) throw DomainException.bad(
      "일정 버전을 확인해 주세요."
    );
    String start = Objects.requireNonNullElse(r.startTime(), "").trim();
    String end = Objects.requireNonNullElse(r.endTime(), "").trim();
    if (!start.isEmpty() || !end.isEmpty()) {
      if (
        !start.matches("(?:[01]\\d|2[0-3]):[0-5]\\d") ||
        !end.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")
      ) throw DomainException.bad(
        "방문 가능 시작과 마감 시각을 함께 입력해 주세요."
      );
      if (
        RouteTiming.clock(start) < 360 ||
        RouteTiming.clock(end) > 1320 ||
        RouteTiming.clock(end) - RouteTiming.clock(start) < duration
      ) throw DomainException.bad(
        "06:00~22:00 안에서 머무르는 시간 이상인 방문 가능 범위를 입력해 주세요."
      );
    }
    Integer cost = null;
    if (r.cost() != null) {
      try {
        cost = r.cost().intValueExact();
      } catch (ArithmeticException e) {
        throw DomainException.bad("비용은 원 단위 정수로 입력해 주세요.");
      }
      // 최대 방문 100개·인원 20명에도 전체 활동비 합계가 int 범위를 넘지 않도록 제한합니다.
      if (cost < 0 || cost > 500000) throw DomainException.bad(
        "1인당 예상 비용은 0~500,000원으로 입력해 주세요."
      );
    }
    String note = Objects.requireNonNullElse(r.note(), "").trim();
    if (
      note.length() > 500 || note.matches("(?s).*[\\p{Cntrl}&&[^\\n\\r\\t]].*")
    ) throw DomainException.bad("확인 메모는 500자 이내로 입력해 주세요.");
    return new Values(
      start.isEmpty() ? null : start,
      end.isEmpty() ? null : end,
      cost,
      note
    );
  }

  public static int earliest(Map<String, Object> item, int fallback) {
    return item.get("visitStart") == null
      ? fallback
      : Math.max(
          fallback,
          RouteTiming.clock(item.get("visitStart").toString())
        );
  }

  public static int latestStart(Map<String, Object> item, int fallback) {
    return item.get("visitEnd") == null
      ? fallback
      : Math.min(
          fallback,
          RouteTiming.clock(item.get("visitEnd").toString()) -
            RouteTiming.number(item, "duration")
        );
  }

  public static int cost(Map<String, Object> item) {
    return RouteTiming.number(
      item,
      item.get("visitCost") == null ? "cost" : "visitCost"
    );
  }

  /** 비교 엔진과 복원은 방문 시작뿐 아니라 체류 종료까지 범위 안에 있는지 동일하게 검사합니다. */
  public static List<String> conflicts(List<Map<String, Object>> items) {
    var issues = new ArrayList<String>();
    for (var i : items)
      if (
        i.get("visitStart") != null &&
        (RouteTiming.time(i) < earliest(i, 0) ||
          RouteTiming.time(i) > latestStart(i, 1440))
      ) issues.add(
        i.get("placeName") +
          " 방문이 직접 입력한 가능 시간 " +
          i.get("visitStart") +
          "~" +
          i.get("visitEnd") +
          "을 벗어나요. 전체 방문 시간 조정에서 비교하거나 조건을 확인해 주세요."
      );
    return issues;
  }
}
