import { describe, expect, it } from "vitest";
import type { Place, Trip } from "../../shared/types";
import { lodgingChoiceBudget, lodgingRegions } from "./lodgingChoice";

const trip = {
  startDate: "2026-11-01",
  endDate: "2026-11-03",
  regions: ["서울", "부산", "강릉"],
  items: [
    { dayIndex: 0, region: "서울" },
    { dayIndex: 0, region: "부산" },
    { dayIndex: 1, region: "부산" },
    { dayIndex: 2, region: "강릉" },
  ],
  lodgings: [
    { region: "서울", totalAmount: 150000 },
    {
      region: "부산",
      totalAmount: 180000,
      roomCount: 2,
      guestCount: 4,
      checkInDate: "2026-11-01",
      checkOutDate: "2026-11-03",
    },
  ],
} as unknown as Trip;

describe("숙소 변경 비교의 범위", () => {
  it("각 숙박일의 마지막 방문 지역만 한 번 제안하고 당일치기는 제외한다", () => {
    expect(lodgingRegions(trip)).toEqual(["부산"]);
    expect(lodgingRegions({ ...trip, endDate: trip.startDate })).toEqual([]);
  });
  it("새 숙소의 미확인 가격에 이전 조건과 총액을 넘기지 않고 다른 지역 금액을 보존한다", () => {
    expect(
      lodgingChoiceBudget(trip, {
        region: "부산",
        source: "KAKAO",
        cost: 0,
      } as Place),
    ).toEqual({ total: 150000, entered: 150000, example: 0, unpriced: 1 });
  });
});
