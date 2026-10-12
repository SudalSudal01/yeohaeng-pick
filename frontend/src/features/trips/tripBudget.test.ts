import { describe, expect, it } from "vitest";
import type { Trip } from "../../shared/types";
import { lodgingBudget } from "./tripBudget";

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
