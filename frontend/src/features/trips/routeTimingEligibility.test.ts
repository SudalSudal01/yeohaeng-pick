import { describe, expect, it } from "vitest";
import { routeTimingEligibility } from "./routeTimingEligibility";
import type { Trip } from "../../shared/types";

/** 임의 검색 출발지·다른 날짜·역방향 구간을 일정 적용 대상으로 안내하지 않는지 확인합니다. */
const trip = {
  items: [
    { id: "a", dayIndex: 0, region: "서울" },
    { id: "b", dayIndex: 0, region: "서울" },
    { id: "c", dayIndex: 1, region: "부산" },
  ],
  lodgings: [{ placeId: "hotel", region: "서울" }],
} as Trip;
describe("일정에 반영할 경로 선택 안내", () => {
  it("같은 날의 정방향 연속 방문만 지원한다", () => {
    expect(routeTimingEligibility(trip, "ITEM:a", "ITEM:b")).toBe("");
    expect(routeTimingEligibility(trip, "ITEM:b", "ITEM:a")).not.toBe("");
    expect(routeTimingEligibility(trip, "ITEM:b", "ITEM:c")).not.toBe("");
  });
  it("같은 지역 숙소에서 첫 방문만 지원한다", () => {
    expect(routeTimingEligibility(trip, "LODGING:hotel", "ITEM:a")).toBe("");
    expect(routeTimingEligibility(trip, "LODGING:hotel", "ITEM:b")).not.toBe(
      "",
    );
    expect(routeTimingEligibility(trip, "LODGING:hotel", "ITEM:c")).not.toBe(
      "",
    );
  });
  it("검색한 출발지와 숙소 도착은 지도 조회 안내만 한다", () => {
    expect(routeTimingEligibility(trip, "ORIGIN:token", "ITEM:a")).not.toBe("");
    expect(routeTimingEligibility(trip, "ITEM:a", "LODGING:hotel")).not.toBe(
      "",
    );
  });
});
