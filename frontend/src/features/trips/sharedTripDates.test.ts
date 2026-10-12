import { describe, expect, it } from "vitest";
import { importedEndDate, sharedTripDays } from "./sharedTripDates";

describe("공유 일정 날짜 이동", () => {
  it("당일치기·윤년·연말에도 원본의 여행 일수를 유지한다", () => {
    expect(sharedTripDays("2028-02-28", "2028-03-01")).toBe(3);
    expect(importedEndDate("2027-12-31", 3)).toBe("2028-01-02");
    expect(importedEndDate("2028-02-28", 3)).toBe("2028-03-01");
    expect(importedEndDate("2026-10-12", 1)).toBe("2026-10-12");
  });
  it("일광절약시간이 바뀌는 날도 하루를 24시간의 달력 간격으로 계산한다", () => {
    expect(sharedTripDays("2026-03-07", "2026-03-09")).toBe(3);
    expect(importedEndDate("2026-11-01", 2)).toBe("2026-11-02");
    expect(importedEndDate("", 3)).toBe("");
  });
});
