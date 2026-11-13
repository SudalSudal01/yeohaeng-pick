import type { Conditions } from "../../shared/types";
import { sharedTripDays } from "../trips/sharedTripDates";

export type PlanIssue = { step: number; field: string; message: string };

/** 단계 이동 전에 서버의 여행 조건 범위를 안내합니다. 서버 검증은 별도로 유지하며,
 * 마지막 추천 요청 때는 앞 단계도 다시 검사해 잘못된 날짜·숫자를 뒤늦게 보내지 않습니다. */
export function planIssue(
  form: Conditions,
  supportedRegions: string[],
  throughStep: number,
): PlanIssue | undefined {
  const issue = (step: number, field: string, message: string) => ({
    step,
    field,
    message,
  });
  if (!form.title.trim())
    return issue(0, "plan-title", "여행 이름을 입력해 주세요.");
  if (
    !form.regions.length ||
    form.regions.some((region) => !supportedRegions.includes(region))
  )
    return issue(
      0,
      "plan-title",
      "지원하는 여행 지역을 한 곳 이상 골라 주세요.",
    );
  const days = sharedTripDays(form.startDate, form.endDate);
  if (!Number.isInteger(days) || days < 1 || days > 14)
    return issue(
      0,
      "plan-start",
      "여행 날짜는 출발일부터 1~14일로 정해 주세요.",
    );
  if (days < form.regions.length)
    return issue(
      0,
      "plan-end",
      "선택한 지역 수만큼 여행 일수가 필요해요. 날짜나 지역을 조정해 주세요.",
    );
  if (!form.origin.trim())
    return issue(0, "plan-origin", "출발할 주소나 역을 입력해 주세요.");
  if (throughStep < 1) return undefined;
  if (
    !Number.isInteger(form.partySize) ||
    form.partySize < 1 ||
    form.partySize > 20
  )
    return issue(
      1,
      "plan-party-size",
      "여행 인원은 1~20명 사이의 정수로 입력해 주세요.",
    );
  if (
    !Number.isInteger(form.budget) ||
    form.budget < 10000 ||
    form.budget > 100000000
  )
    return issue(
      1,
      "plan-budget",
      "전체 예산은 10,000~100,000,000원 사이의 정수로 입력해 주세요.",
    );
}
