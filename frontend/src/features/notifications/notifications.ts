import { ApiError } from "../../shared/api";

export type NotificationType = "TRIP_POLL_CREATED" | "TRIP_POLL_CLOSED";

export type TravelNotification = {
  id: string;
  type: NotificationType;
  readAt: string | null;
  createdAt: string;
  actorName: string;
  tripId: string;
  tripTitle: string;
  pollId: string;
  pollTitle: string;
};

export type NotificationPageData = {
  items: TravelNotification[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
  unreadCount: number;
};

/** 인증이 끝났거나 현재 여행 접근 권한이 사라진 응답은 비공개 캐시를 즉시 가려야 합니다. */
export const notificationAccessLost = (error: unknown) =>
  error instanceof ApiError && [401, 403, 404].includes(error.status);

/** 직접 수정한 주소도 서버가 받을 수 있는 양의 페이지 번호로 정리합니다. */
export function readNotificationPage(params: URLSearchParams) {
  const raw = params.get("page") ?? "1";
  return /^[1-9]\d*$/.test(raw) ? Math.min(Number(raw), 1_000_000) : 1;
}

/** 1페이지는 주소에서 생략해 알림 센터 링크를 짧고 읽기 쉽게 유지합니다. */
export function notificationPageParams(page: number) {
  const params = new URLSearchParams();
  if (page > 1) params.set("page", String(page));
  return params;
}

/** 알림 종류를 그대로 노출하지 않고 사용자가 이해할 수 있는 행동 문장으로 바꿉니다. */
export function notificationCopy(notification: TravelNotification) {
  return notification.type === "TRIP_POLL_CLOSED"
    ? `${notification.actorName}님이 투표를 마감했어요.`
    : `${notification.actorName}님이 새 투표를 만들었어요.`;
}

/** 서버 목록 캐시 한 건을 읽음으로 바꾸며 중복 클릭에도 미확인 수를 한 번만 줄입니다. */
export function markNotificationReadInPage(
  data: NotificationPageData | undefined,
  id: string,
  readAt: string,
) {
  if (!data) return data;
  const target = data.items.find((item) => item.id === id);
  if (!target || target.readAt) return data;
  return {
    ...data,
    unreadCount: Math.max(0, data.unreadCount - 1),
    items: data.items.map((item) =>
      item.id === id ? { ...item, readAt } : item,
    ),
  };
}

/** 모두 읽음 응답을 기다리는 동안에도 화면 전체를 즉시 읽음 상태로 맞춥니다. */
export function markAllNotificationsRead(
  data: NotificationPageData | undefined,
  readAt: string,
) {
  if (!data) return data;
  return {
    ...data,
    unreadCount: 0,
    items: data.items.map((item) => ({
      ...item,
      readAt: item.readAt ?? readAt,
    })),
  };
}
