import type { Room } from "../../shared/types";

export type ChatMessage = NonNullable<Room["messages"]>[number];
export type Recipient = { id: string; nickname: string };
export type RecipientPage = { items: Recipient[]; hasMore: boolean };
export type ConversationTab = "ACTIVE" | "INCOMING" | "OUTGOING";

/** 서버 페이지가 겹쳐도 같은 메시지를 두 번 표시하지 않고, 같은 시각의 기록은 ID로 순서를 고정합니다. */
export function mergeMessages(current: ChatMessage[], incoming: ChatMessage[]) {
  const byId = new Map(current.map((message) => [message.id, message]));
  incoming.forEach((message) => byId.set(message.id, message));
  return [...byId.values()].sort((left, right) => {
    const time = Date.parse(left.createdAt) - Date.parse(right.createdAt);
    if (Number.isFinite(time) && time !== 0) return time;
    // Oracle/H2 TIMESTAMP의 마이크로·나노초를 Date가 밀리초로 잘라 버리지 않게 비교합니다.
    const fraction = (value: string) =>
      (value.match(/\.(\d+)/)?.[1] || "").padEnd(9, "0").slice(0, 9);
    const precise = fraction(left.createdAt).localeCompare(
      fraction(right.createdAt),
    );
    return precise || left.id.localeCompare(right.id);
  });
}

/** 폴링 사이에 한 페이지보다 많은 메시지가 도착하면 중간 기록을 조용히 생략해서 붙이지 않습니다. */
export function hasMessageGap(current: ChatMessage[], latest: ChatMessage[]) {
  if (!current.length || !latest.length) return false;
  const ids = new Set(current.map((message) => message.id));
  return !latest.some((message) => ids.has(message.id));
}

/** 전송 완료가 늦게 도착해도 다른 방의 초안이나 사용자가 새로 입력한 문장을 지우지 않습니다. */
export function draftAfterSent(draft: string, sent: string) {
  return draft === sent ? "" : draft;
}

export function roomTitle(room: Room, memberId: string) {
  return room.tripId
    ? room.tripTitle || "여행 단체 대화"
    : (room.requesterId === memberId
        ? room.recipientName
        : room.requesterName) || "여행 회원";
}

export function otherMemberCode(room: Room, memberId: string) {
  return (room.requesterId === memberId ? room.recipientId : room.requesterId)
    .replaceAll("-", "")
    .slice(0, 8);
}

export function roomsForTab(
  rooms: Room[],
  tab: ConversationTab,
  memberId: string,
) {
  return rooms.filter((room) =>
    tab === "ACTIVE"
      ? room.status === "ACTIVE" || room.status === "CLOSED"
      : room.status === "PENDING" &&
        (tab === "INCOMING"
          ? room.recipientId === memberId
          : room.requesterId === memberId),
  );
}

export function canWrite(room: Room) {
  return room.status === "ACTIVE" && !room.blocked && room.canSend !== false;
}
