import type { TripPoll } from "../../shared/types";

export type TripPollDraft = {
  title: string;
  selectionType: TripPoll["selectionType"];
  options: string[];
  closesAt: string;
};

const KOREA_OFFSET_MILLISECONDS = 9 * 60 * 60 * 1000;
const KOREA_WALL_CLOCK =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?$/;

/**
 * 서버의 closesAt/closedAt은 오프셋 없는 한국 벽시각입니다.
 * 브라우저의 현재 시간대가 개입하지 않도록 각 숫자를 직접 읽어 KST 순간으로 바꿉니다.
 */
export function parseKstWallDateTime(value: string) {
  const match = KOREA_WALL_CLOCK.exec(value);
  if (!match) return null;

  const [
    ,
    yearText,
    monthText,
    dayText,
    hourText,
    minuteText,
    secondText,
    fraction,
  ] = match;
  const parts = {
    year: Number(yearText),
    month: Number(monthText),
    day: Number(dayText),
    hour: Number(hourText),
    minute: Number(minuteText),
    second: Number(secondText ?? 0),
    millisecond: Number((fraction ?? "").padEnd(3, "0").slice(0, 3)),
  };
  const wallClock = new Date(0);
  wallClock.setUTCFullYear(parts.year, parts.month - 1, parts.day);
  wallClock.setUTCHours(
    parts.hour,
    parts.minute,
    parts.second,
    parts.millisecond,
  );

  // Date가 2월 30일 같은 값을 다음 달로 보정하므로 원래 입력과 다시 비교해 거부합니다.
  if (
    wallClock.getUTCFullYear() !== parts.year ||
    wallClock.getUTCMonth() !== parts.month - 1 ||
    wallClock.getUTCDate() !== parts.day ||
    wallClock.getUTCHours() !== parts.hour ||
    wallClock.getUTCMinutes() !== parts.minute ||
    wallClock.getUTCSeconds() !== parts.second
  )
    return null;

  return new Date(wallClock.getTime() - KOREA_OFFSET_MILLISECONDS);
}

/** 서버에서 받은 한국 벽시각임을 화면에도 명시해 해외 시간대에서의 오해를 막습니다. */
export function formatKstWallDateTime(value: string) {
  const date = parseKstWallDateTime(value);
  if (!date) return "시각 확인 필요";
  return `${date.toLocaleString("ko-KR", {
    timeZone: "Asia/Seoul",
    month: "long",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  })} (한국 시간)`;
}

/** 서버 선택 순서와 무관하게 같은 선택인지 비교해, 저장 버튼이 필요한 때만 열리게 합니다. */
export function samePollSelection(left: string[], right: string[]) {
  if (left.length !== right.length) return false;
  const rightIds = new Set(right);
  return left.every((id) => rightIds.has(id));
}

/** 현재 회원의 선택만 추려 로컬 입력의 초기값으로 사용합니다. */
export function selectedPollOptionIds(poll: TripPoll) {
  return poll.options
    .filter((option) => option.selectedByMe)
    .sort((left, right) => left.sortOrder - right.sortOrder)
    .map((option) => option.id);
}

/** 단일 선택은 기존 값을 교체하고, 복수 선택은 같은 항목을 다시 누르면 해제합니다. */
export function togglePollSelection(
  selectionType: TripPoll["selectionType"],
  selected: string[],
  optionId: string,
) {
  if (selectionType === "SINGLE") return [optionId];
  return selected.includes(optionId)
    ? selected.filter((id) => id !== optionId)
    : [...selected, optionId];
}

/** 진행 중 투표를 먼저 보여 주고, 각 그룹 안에서는 최근 생성한 투표를 먼저 보여 줍니다. */
export function groupTripPolls(polls: TripPoll[]) {
  const newestFirst = (left: TripPoll, right: TripPoll) =>
    new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime();
  return {
    open: polls.filter((poll) => poll.status === "OPEN").sort(newestFirst),
    finished: polls.filter((poll) => poll.status !== "OPEN").sort(newestFirst),
  };
}

/** 복수 선택의 총 표 수가 참여 인원보다 커질 수 있어, 선택지 하나의 비율만 참여 가능 인원으로 계산합니다. */
export function pollOptionPercent(poll: TripPoll, voteCount: number) {
  if (poll.eligibleMemberCount <= 0) return 0;
  return Math.min(
    100,
    Math.round((voteCount / poll.eligibleMemberCount) * 100),
  );
}

/** 0표를 1위로 강조하지 않고 실제 표가 있는 공동 1위만 찾습니다. */
export function isLeadingPollOption(poll: TripPoll, voteCount: number) {
  const highest = Math.max(
    0,
    ...poll.options.map((option) => option.voteCount),
  );
  return highest > 0 && voteCount === highest;
}

export function pollStatusLabel(status: TripPoll["status"]) {
  if (status === "CLOSED") return "마감";
  if (status === "EXPIRED") return "기간 종료";
  return "진행 중";
}

/** 입력값은 저장 직전에 공백을 정리합니다. 화면과 서버가 서로 다른 선택지로 해석하지 않게 중복도 막습니다. */
export function validateTripPollDraft(draft: TripPollDraft, now = new Date()) {
  const title = draft.title.trim();
  const options = draft.options.map((option) => option.trim());
  if (!title) return "투표 질문을 입력해 주세요.";
  if (title.length > 120) return "투표 질문은 120자까지 입력할 수 있어요.";
  if (options.length < 2 || options.length > 10)
    return "선택지는 2개부터 10개까지 만들 수 있어요.";
  if (options.some((option) => !option))
    return "비어 있는 선택지에 내용을 입력해 주세요.";
  if (options.some((option) => option.length > 100))
    return "선택지는 각각 100자까지 입력할 수 있어요.";
  // 서버와 같은 대소문자 무시 규칙을 먼저 적용해 제출 뒤에만 중복 오류가 나타나지 않게 합니다.
  if (
    new Set(options.map((option) => option.toLocaleLowerCase("ko-KR"))).size !==
    options.length
  )
    return "같은 내용의 선택지가 있어요. 서로 다르게 적어 주세요.";
  if (draft.closesAt) {
    const closesAt = parseKstWallDateTime(draft.closesAt);
    if (!closesAt || closesAt.getTime() <= now.getTime())
      return "마감 시각은 현재보다 뒤로 정해 주세요.";
  }
  return null;
}

/** datetime-local 입력 계약에 맞춰 같은 순간을 분 단위의 한국 벽시각으로 바꿉니다. */
export function kstDateTimeInputValue(date: Date) {
  return new Date(date.getTime() + KOREA_OFFSET_MILLISECONDS)
    .toISOString()
    .slice(0, 16);
}
