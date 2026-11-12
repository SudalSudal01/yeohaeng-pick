import { describe, expect, it } from "vitest";
import type { Room } from "../../shared/types";
import {
  canWrite,
  draftAfterSent,
  hasMessageGap,
  mergeMessages,
  roomsForTab,
} from "./messageState";

const message = (id: string, second = 0) => ({
  id,
  senderId: "a",
  nickname: "회원",
  body: id,
  createdAt: `2026-11-12T10:00:${String(second).padStart(2, "0")}`,
});
const room = (
  id: string,
  status: Room["status"],
  requesterId = "a",
  recipientId = "b",
): Room => ({
  id,
  status,
  requesterId,
  recipientId,
  requesterName: "가",
  recipientName: "나",
});

describe("메시지 페이지 연결", () => {
  it("이전 페이지와 새 폴링 결과를 중복 없이 합치고 같은 시각의 순서를 고정한다", () => {
    const merged = mergeMessages(
      [message("b"), message("c", 1)],
      [{ ...message("b"), nickname: "변경 이름" }, message("a")],
    );
    expect(merged.map((item) => item.id)).toEqual(["a", "b", "c"]);
    expect(merged[1].nickname).toBe("변경 이름");
  });
  it("최신 묶음에 기존 기록이 하나도 겹치지 않으면 중간 누락 가능성을 알린다", () => {
    expect(hasMessageGap([message("a")], [message("z", 2)])).toBe(true);
    expect(hasMessageGap([message("a")], [message("a"), message("z", 2)])).toBe(
      false,
    );
    expect(hasMessageGap([], [message("z")])).toBe(false);
  });
  it("같은 밀리초 안의 DB 미세시각을 ID보다 먼저 비교한다", () => {
    const earlier = {
      ...message("z"),
      createdAt: "2026-11-12T10:00:00.123001",
    };
    const later = { ...message("a"), createdAt: "2026-11-12T10:00:00.123999" };
    expect(mergeMessages([later], [earlier]).map((item) => item.id)).toEqual([
      "z",
      "a",
    ]);
  });
  it("늦게 끝난 전송은 이미 바뀐 초안을 지우지 않는다", () => {
    expect(draftAfterSent("새로 쓴 문장", "먼저 보낸 문장")).toBe(
      "새로 쓴 문장",
    );
    expect(draftAfterSent("먼저 보낸 문장", "먼저 보낸 문장")).toBe("");
  });
  it("받은 요청과 보낸 요청을 일반 대화와 분리한다", () => {
    const rooms = [
      room("active", "ACTIVE"),
      room("in", "PENDING", "b", "a"),
      room("out", "PENDING"),
      room("declined", "DECLINED"),
      room("closed", "CLOSED"),
    ];
    expect(roomsForTab(rooms, "ACTIVE", "a").map((item) => item.id)).toEqual([
      "active",
      "closed",
    ]);
    expect(roomsForTab(rooms, "INCOMING", "a").map((item) => item.id)).toEqual([
      "in",
    ]);
    expect(roomsForTab(rooms, "OUTGOING", "a").map((item) => item.id)).toEqual([
      "out",
    ]);
  });
  it("수락 전·거절·종료·차단 또는 서버 전송 불가 판정은 작성할 수 없다", () => {
    expect(canWrite(room("a", "ACTIVE"))).toBe(true);
    expect(canWrite({ ...room("a", "ACTIVE"), blocked: true })).toBe(false);
    expect(canWrite({ ...room("a", "ACTIVE"), canSend: false })).toBe(false);
    for (const status of ["PENDING", "DECLINED", "CLOSED"] as const)
      expect(canWrite(room("a", status))).toBe(false);
  });
});
