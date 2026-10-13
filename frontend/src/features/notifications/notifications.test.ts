import { describe, expect, it } from "vitest";
import { ApiError } from "../../shared/api";
import {
  markAllNotificationsRead,
  markNotificationReadInPage,
  notificationCopy,
  notificationAccessLost,
  notificationPageParams,
  readNotificationPage,
  type NotificationPageData,
} from "./notifications";

const page: NotificationPageData = {
  page: 1,
  pageSize: 20,
  total: 2,
  totalPages: 1,
  unreadCount: 2,
  items: [
    {
      id: "one",
      type: "TRIP_POLL_CREATED",
      readAt: null,
      createdAt: "2026-10-13T10:00:00",
      actorName: "수달",
      tripId: "trip",
      tripTitle: "서울 여행",
      pollId: "poll-one",
      pollTitle: "저녁 메뉴",
    },
    {
      id: "two",
      type: "TRIP_POLL_CLOSED",
      readAt: null,
      createdAt: "2026-10-13T11:00:00",
      actorName: "여행자",
      tripId: "trip",
      tripTitle: "서울 여행",
      pollId: "poll-two",
      pollTitle: "출발 시간",
    },
  ],
};

describe("알림 센터 표시 계산", () => {
  it("잘못된 페이지는 1페이지로 보정하고 첫 페이지는 주소에서 생략한다", () => {
    expect(readNotificationPage(new URLSearchParams("page=-2"))).toBe(1);
    expect(readNotificationPage(new URLSearchParams("page=3"))).toBe(3);
    expect(notificationPageParams(1).toString()).toBe("");
    expect(notificationPageParams(3).toString()).toBe("page=3");
  });

  it("한 알림을 두 번 읽어도 미확인 개수는 한 번만 줄인다", () => {
    const first = markNotificationReadInPage(page, "one", "now")!;
    const second = markNotificationReadInPage(first, "one", "later")!;
    expect(first.unreadCount).toBe(1);
    expect(second.unreadCount).toBe(1);
    expect(second.items[0].readAt).toBe("now");
  });

  it("모두 읽음은 기존 읽음 시각을 보존하고 나머지만 채운다", () => {
    const partlyRead = markNotificationReadInPage(page, "one", "first")!;
    const result = markAllNotificationsRead(partlyRead, "all")!;
    expect(result.unreadCount).toBe(0);
    expect(result.items.map((item) => item.readAt)).toEqual(["first", "all"]);
  });

  it("서버 이벤트 코드를 사람에게 읽히는 문장으로 표시한다", () => {
    expect(notificationCopy(page.items[0])).toBe(
      "수달님이 새 투표를 만들었어요.",
    );
    expect(notificationCopy(page.items[1])).toBe(
      "여행자님이 투표를 마감했어요.",
    );
  });

  it("인증·여행 접근이 끝난 응답만 비공개 캐시 종료로 구분한다", () => {
    expect(notificationAccessLost(new ApiError(401, "로그인 필요"))).toBe(true);
    expect(notificationAccessLost(new ApiError(403, "접근 종료"))).toBe(true);
    expect(notificationAccessLost(new ApiError(404, "알림 없음"))).toBe(true);
    expect(notificationAccessLost(new ApiError(409, "충돌"))).toBe(false);
    expect(notificationAccessLost(new Error("네트워크 오류"))).toBe(false);
  });
});
