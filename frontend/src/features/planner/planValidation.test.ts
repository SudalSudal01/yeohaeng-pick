import { describe, expect, it } from "vitest";
import type { Conditions } from "../../shared/types";
import { planIssue } from "./planValidation";

const conditions: Conditions = {
  title: "서울 여행",
  regions: ["서울"],
  startDate: "2026-11-13",
  endDate: "2026-11-13",
  origin: "서울역",
  partySize: 2,
  budget: 300000,
  transport: "TRANSIT",
  preferences: [],
  lodging: "",
  lodgingIds: [],
};
describe("여행 조건 단계 검증", () => {
  it("당일치기와 연말을 넘는 14일 일정을 허용하고 15일/역전/빈 날짜는 날짜 단계로 안내한다", () => {
    expect(planIssue(conditions, ["서울"], 2)).toBeUndefined();
    expect(
      planIssue(
        { ...conditions, startDate: "2026-12-25", endDate: "2027-01-07" },
        ["서울"],
        2,
      ),
    ).toBeUndefined();
    for (const dates of [
      { startDate: "2026-12-25", endDate: "2027-01-08" },
      { startDate: "2026-11-14", endDate: "2026-11-13" },
      { startDate: "", endDate: "" },
    ])
      expect(planIssue({ ...conditions, ...dates }, ["서울"], 2)).toMatchObject(
        { step: 0, field: "plan-start" },
      );
  });
  it("두 지역의 당일치기나 지원하지 않는 지역을 추천 요청 전에 안내한다", () => {
    expect(
      planIssue(
        { ...conditions, regions: ["서울", "부산"] },
        ["서울", "부산"],
        2,
      )?.field,
    ).toBe("plan-end");
    expect(
      planIssue({ ...conditions, regions: ["알수없는곳"] }, ["서울"], 2)?.step,
    ).toBe(0);
  });
  it("현재 단계까지만 숫자를 검사하고 추천 때는 빈 값·소수·범위 밖 인원/예산을 모두 막는다", () => {
    expect(
      planIssue({ ...conditions, partySize: 0 }, ["서울"], 0),
    ).toBeUndefined();
    for (const partySize of [0, 1.5, 21, NaN])
      expect(planIssue({ ...conditions, partySize }, ["서울"], 2)?.field).toBe(
        "plan-party-size",
      );
    for (const budget of [0, 9999, 100000001, 10000.5, NaN])
      expect(planIssue({ ...conditions, budget }, ["서울"], 2)?.field).toBe(
        "plan-budget",
      );
  });
});
