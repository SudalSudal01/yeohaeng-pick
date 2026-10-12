import { describe, expect, it } from "vitest";
import type { Trip } from "../../shared/types";
import { lodgingBudget, activityBudget } from "./tripBudget";
import { activityPrice } from "../../shared/api";

describe("직접 입력 방문 비용", () => {
  it("0원 입력과 미확인 값을 구분하고 전체 인원 비용을 한 번만 계산한다", () => {
    const trip = {
      partySize: 2,
      items: [
        { source: "KAKAO", cost: 0, visitCost: null },
        { source: "KAKAO", cost: 20000, visitCost: 0 },
        { source: "KAKAO", cost: 0, visitCost: 3000 },
        { source: "SAMPLE", cost: 5000 },
      ],
    } as unknown as Trip;
    expect(activityBudget(trip)).toEqual({
      total: 16000,
      entered: 6000,
      unpriced: 1,
    });
    expect(activityPrice(trip.items[0])).toBe("요금 확인 필요");
    expect(activityPrice(trip.items[1])).toBe("0원 / 인 · 직접 입력");
    expect(activityPrice(trip.items[2])).toBe("3,000원 / 인 · 직접 입력");
  });
});

const base = {
  startDate: "2026-11-01",
  endDate: "2026-11-04",
  items: [
    { dayIndex: 0, region: "서울" },
    { dayIndex: 1, region: "서울" },
    { dayIndex: 2, region: "부산" },
  ],
  lodgings: [
    { region: "서울", source: "SAMPLE", nightlyEstimate: 70000 },
    { region: "부산", source: "KAKAO", nightlyEstimate: 0 },
  ],
} as unknown as Trip;
describe("숙박비 참고 합계", () => {
  it("이전 여행의 지역별 1객실 예시와 미확인 실제 가격을 구분한다", () => {
    expect(lodgingBudget(base)).toEqual({
      total: 140000,
      entered: 0,
      example: 140000,
      unpriced: 1,
    });
  });
  it("기간 전체·객실 전체 입력 금액에는 인원/객실/박 수를 다시 곱하지 않는다", () => {
    const trip = {
      ...base,
      lodgings: [
        {
          ...base.lodgings[0],
          checkInDate: "2026-11-01",
          checkOutDate: "2026-11-03",
          roomCount: 2,
          guestCount: 4,
          totalAmount: 260000,
        },
        { ...base.lodgings[1], totalAmount: 0 },
      ],
    };
    expect(lodgingBudget(trip)).toEqual({
      total: 260000,
      entered: 260000,
      example: 0,
      unpriced: 0,
    });
  });
  it("금액 미입력의 예시 숙소는 지정한 기간과 객실 수만 계산한다", () => {
    const trip = {
      ...base,
      lodgings: [
        {
          ...base.lodgings[0],
          checkInDate: "2026-11-02",
          checkOutDate: "2026-11-03",
          roomCount: 2,
          totalAmount: null,
        },
      ],
    };
    expect(lodgingBudget(trip)).toEqual({
      total: 140000,
      entered: 0,
      example: 140000,
      unpriced: 0,
    });
  });
});
