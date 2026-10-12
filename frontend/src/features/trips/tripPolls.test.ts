import { describe, expect, it } from "vitest";
import type { TripPoll } from "../../shared/types";
import {
  formatKstWallDateTime,
  groupTripPolls,
  isLeadingPollOption,
  kstDateTimeInputValue,
  parseKstWallDateTime,
  pollOptionPercent,
  samePollSelection,
  selectedPollOptionIds,
  togglePollSelection,
  validateTripPollDraft,
} from "./tripPolls";

function poll(overrides: Partial<TripPoll> = {}): TripPoll {
  return {
    id: "poll-1",
    title: "어디에서 저녁을 먹을까요?",
    selectionType: "SINGLE",
    status: "OPEN",
    closesAt: null,
    closedAt: null,
    version: 0,
    createdAt: "2026-10-13T10:00:00",
    creatorName: "여행자",
    canManage: true,
    votedMemberCount: 1,
    eligibleMemberCount: 4,
    options: [
      {
        id: "second",
        label: "시장",
        sortOrder: 2,
        voteCount: 2,
        selectedByMe: false,
      },
      {
        id: "first",
        label: "식당",
        sortOrder: 1,
        voteCount: 2,
        selectedByMe: true,
      },
    ],
    ...overrides,
  };
}

describe("여행 투표 화면 계산", () => {
  it("현재 회원의 선택은 선택지 표시 순서대로 정리한다", () => {
    expect(
      selectedPollOptionIds(
        poll({
          options: poll().options.map((option) => ({
            ...option,
            selectedByMe: true,
          })),
        }),
      ),
    ).toEqual(["first", "second"]);
  });

  it("선택 순서가 달라도 같은 투표로 판단한다", () => {
    expect(samePollSelection(["one", "two"], ["two", "one"])).toBe(true);
    expect(samePollSelection(["one"], ["two"])).toBe(false);
  });

  it("단일 선택은 교체하고 복수 선택은 추가·해제한다", () => {
    expect(togglePollSelection("SINGLE", ["one"], "two")).toEqual(["two"]);
    expect(togglePollSelection("MULTIPLE", ["one"], "two")).toEqual([
      "one",
      "two",
    ]);
    expect(togglePollSelection("MULTIPLE", ["one"], "one")).toEqual([]);
  });

  it("진행 중과 끝난 투표를 나누고 최근 항목을 먼저 둔다", () => {
    const grouped = groupTripPolls([
      poll({ id: "old", createdAt: "2026-10-11T10:00:00" }),
      poll({ id: "closed", status: "CLOSED" }),
      poll({ id: "new", createdAt: "2026-10-14T10:00:00" }),
    ]);
    expect(grouped.open.map(({ id }) => id)).toEqual(["new", "old"]);
    expect(grouped.finished.map(({ id }) => id)).toEqual(["closed"]);
  });

  it("참여 가능 인원 기준 비율과 공동 1위를 계산한다", () => {
    const target = poll();
    expect(pollOptionPercent(target, 2)).toBe(50);
    expect(isLeadingPollOption(target, 2)).toBe(true);
    expect(
      isLeadingPollOption(
        poll({
          options: poll().options.map((option) => ({
            ...option,
            voteCount: 0,
          })),
        }),
        0,
      ),
    ).toBe(false);
    expect(pollOptionPercent(poll({ eligibleMemberCount: 0 }), 1)).toBe(0);
  });

  it("공백·중복 선택지와 지난 마감을 막고 정상 입력의 공백은 허용한다", () => {
    const now = new Date("2026-10-13T12:00:00+09:00");
    const valid = {
      title: " 점심 메뉴 ",
      selectionType: "MULTIPLE" as const,
      options: [" 국수 ", "비빔밥"],
      closesAt: "2026-10-13T13:00",
    };
    expect(validateTripPollDraft(valid, now)).toBeNull();
    expect(
      validateTripPollDraft({ ...valid, options: ["국수", " 국수 "] }, now),
    ).toContain("같은 내용");
    expect(
      validateTripPollDraft({ ...valid, options: ["SEOUL", "seoul"] }, now),
    ).toContain("같은 내용");
    expect(
      validateTripPollDraft({ ...valid, options: ["국수", ""] }, now),
    ).toContain("비어 있는");
    expect(
      validateTripPollDraft({ ...valid, closesAt: "2026-10-13T11:59" }, now),
    ).toContain("현재보다 뒤");
  });

  it("마감 벽시각은 실행 환경 시간대와 무관하게 한국 시간으로 해석한다", () => {
    expect(
      parseKstWallDateTime("2026-10-13T18:30:45.123456789")?.toISOString(),
    ).toBe("2026-10-13T09:30:45.123Z");
    expect(parseKstWallDateTime("2026-02-30T18:30")).toBeNull();
    expect(kstDateTimeInputValue(new Date("2026-10-13T09:30:45Z"))).toBe(
      "2026-10-13T18:30",
    );
    expect(formatKstWallDateTime("2026-10-13T18:30")).toContain("한국 시간");
  });

  it("KST 현재 시각과 비교해 같은 시각과 과거 마감을 거부한다", () => {
    const draft = {
      title: "저녁 메뉴",
      selectionType: "SINGLE" as const,
      options: ["국수", "비빔밥"],
      closesAt: "2026-10-13T18:30",
    };
    expect(
      validateTripPollDraft(draft, new Date("2026-10-13T09:29:59Z")),
    ).toBeNull();
    expect(
      validateTripPollDraft(draft, new Date("2026-10-13T09:30:00Z")),
    ).toContain("현재보다 뒤");
    expect(
      validateTripPollDraft(draft, new Date("2026-10-13T09:31:00Z")),
    ).toContain("현재보다 뒤");
  });
});
