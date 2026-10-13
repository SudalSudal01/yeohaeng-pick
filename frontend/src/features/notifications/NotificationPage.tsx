import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, ArrowRight, Bell, Check, Vote } from "lucide-react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { api } from "../../shared/api";
import { useToast } from "../../shared/providers";
import { Empty, ErrorState, Loading } from "../../shared/ui";
import {
  markAllNotificationsRead,
  markNotificationReadInPage,
  notificationAccessLost,
  notificationCopy,
  notificationPageParams,
  readNotificationPage,
  readNotificationUnreadOnly,
  type NotificationPageData,
  type TravelNotification,
} from "./notifications";
import "./notifications.css";

const PAGE_SIZE = 20;

/** 동행 여행에서 생긴 알림을 최신순으로 확인하고 해당 투표로 바로 이동합니다. */
export default function NotificationPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const client = useQueryClient();
  const [params, setParams] = useSearchParams();
  const requestedPage = readNotificationPage(params);
  const unreadOnly = readNotificationUnreadOnly(params);
  const listRef = useRef<HTMLElement>(null);
  const pageFocus = useRef(false);
  const readMutationBusy = useRef(false);
  const [reading, setReading] = useState(false);
  const [markingAll, setMarkingAll] = useState(false);
  const [accessFailure, setAccessFailure] = useState<unknown>(null);
  const query = useQuery({
    queryKey: ["notifications", requestedPage, unreadOnly],
    queryFn: () =>
      api<NotificationPageData>(
        `/notifications?page=${requestedPage}&pageSize=${PAGE_SIZE}&unreadOnly=${unreadOnly}`,
      ),
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
    retry: false,
    enabled: accessFailure == null,
  });

  /** 접근이 끝난 응답을 받으면 이전 회원의 목록과 헤더 숫자를 한 렌더도 더 사용하지 않습니다. */
  function hidePrivateNotifications(error: unknown) {
    setAccessFailure(error);
    client.removeQueries({ queryKey: ["notifications"] });
    client.removeQueries({ queryKey: ["notification-unread-count"] });
    client.setQueryData(["notification-unread-count"], { unreadCount: 0 });
  }

  useEffect(() => {
    if (notificationAccessLost(query.error))
      hidePrivateNotifications(query.error);
  }, [query.error]);

  // 삭제·권한 변경으로 마지막 페이지가 줄면 서버가 보정한 페이지를 주소에도 반영합니다.
  useEffect(() => {
    if (
      !accessFailure &&
      !reading &&
      query.data &&
      query.data.page !== requestedPage
    ) {
      setParams(notificationPageParams(query.data.page, unreadOnly), {
        replace: true,
      });
    }
  }, [
    accessFailure,
    reading,
    query.data,
    requestedPage,
    unreadOnly,
    setParams,
  ]);

  // 페이지 버튼을 직접 누른 경우에만 결과 시작으로 이동합니다. 주기 갱신은 읽던 위치를 유지합니다.
  useEffect(() => {
    if (pageFocus.current && !query.isFetching) {
      pageFocus.current = false;
      listRef.current?.focus({ preventScroll: true });
      listRef.current?.scrollIntoView({ block: "start" });
    }
  }, [query.isFetching]);

  /** 폴링 응답과 낙관 갱신이 서로 덮지 않게 관련 조회를 멈추고 읽음 변경을 하나씩 수행합니다. */
  async function beginReadMutation(all = false) {
    if (readMutationBusy.current) return false;
    readMutationBusy.current = true;
    setReading(true);
    setMarkingAll(all);
    await Promise.all([
      client.cancelQueries({ queryKey: ["notifications"] }),
      client.cancelQueries({ queryKey: ["notification-unread-count"] }),
    ]);
    return true;
  }

  /** 성공·실패 모두 서버 값을 다시 조회합니다. 접근 종료 때는 빈 캐시만 stale 처리하고 재호출하지 않습니다. */
  async function finishReadMutation(accessWasLost: boolean) {
    const refetchType = accessWasLost ? "none" : "active";
    await Promise.all([
      client.invalidateQueries({ queryKey: ["notifications"], refetchType }),
      client.invalidateQueries({
        queryKey: ["notification-unread-count"],
        refetchType,
      }),
    ]);
    readMutationBusy.current = false;
    setReading(false);
    setMarkingAll(false);
  }

  /** 목록과 헤더 개수를 함께 낙관적으로 바꾸고 실패하면 중단 직전 값으로 되돌립니다. */
  async function markRead(notification: TravelNotification) {
    if (notification.readAt) return true;
    if (!(await beginReadMutation())) return false;
    const key = ["notifications", requestedPage, unreadOnly] as const;
    const previousPage = client.getQueryData<NotificationPageData>(key);
    const previousCount = client.getQueryData<{ unreadCount: number }>([
      "notification-unread-count",
    ]) ?? { unreadCount: previousPage?.unreadCount ?? 0 };
    const readAt = new Date().toISOString();
    client.setQueryData<NotificationPageData>(key, (current) =>
      markNotificationReadInPage(current, notification.id, readAt, unreadOnly),
    );
    client.setQueryData<{ unreadCount: number }>(
      ["notification-unread-count"],
      (current) => ({
        unreadCount: Math.max(
          0,
          (current?.unreadCount ?? previousPage?.unreadCount ?? 1) - 1,
        ),
      }),
    );
    let accessWasLost = false;
    try {
      await api(`/notifications/${notification.id}/read`, { method: "PUT" });
      return true;
    } catch (error) {
      accessWasLost = notificationAccessLost(error);
      if (accessWasLost) hidePrivateNotifications(error);
      else {
        // 읽음 작업은 직렬화하고 폴링을 중단했으므로 이 스냅샷은 다른 성공을 되돌리지 않습니다.
        client.setQueryData(key, previousPage);
        client.setQueryData(["notification-unread-count"], previousCount);
      }
      toast((error as Error).message, true);
      return false;
    } finally {
      await finishReadMutation(accessWasLost);
    }
  }

  async function openNotification(notification: TravelNotification) {
    if (!(await markRead(notification))) return;
    navigate(`/trips/${notification.tripId}#trip-polls`);
  }

  async function markAllRead() {
    if (!(await beginReadMutation(true))) return;
    const key = ["notifications", requestedPage, unreadOnly] as const;
    const previousPage = client.getQueryData<NotificationPageData>(key);
    const previousCount = client.getQueryData<{ unreadCount: number }>([
      "notification-unread-count",
    ]) ?? { unreadCount: previousPage?.unreadCount ?? 0 };
    const readAt = new Date().toISOString();
    client.setQueryData<NotificationPageData>(key, (current) =>
      markAllNotificationsRead(current, readAt, unreadOnly),
    );
    client.setQueryData(["notification-unread-count"], { unreadCount: 0 });
    let accessWasLost = false;
    try {
      await api("/notifications/read-all", { method: "PUT" });
      toast("모든 알림을 읽음으로 표시했어요.");
    } catch (error) {
      accessWasLost = notificationAccessLost(error);
      if (accessWasLost) hidePrivateNotifications(error);
      else {
        client.setQueryData(key, previousPage);
        client.setQueryData(["notification-unread-count"], previousCount);
      }
      toast((error as Error).message, true);
    } finally {
      await finishReadMutation(accessWasLost);
    }
  }

  const privateAccessError =
    accessFailure ?? (notificationAccessLost(query.error) ? query.error : null);
  const data = privateAccessError ? undefined : query.data;
  const first = data ? (data.page - 1) * data.pageSize + 1 : 0;
  return (
    <div className="page notification-page">
      <div className="notification-heading">
        <div>
          <span className="eyebrow">TRIP UPDATES</span>
          <h1>여행 알림</h1>
          <p>동행이 만든 새 투표와 마감한 투표를 한곳에서 확인해요.</p>
        </div>
        {data && (data.unreadCount > 0 || markingAll) && (
          <button
            className="button secondary"
            onClick={markAllRead}
            disabled={reading}
          >
            <Check size={17} />
            {markingAll ? "읽음 처리 중…" : "모두 읽음"}
          </button>
        )}
      </div>

      {/* 실제 버튼의 누름 상태로 필터를 설명합니다. 읽음 저장 중에는 주소를 바꾸지 않아
          실패 복원 대상과 현재 화면이 달라지는 일을 방지합니다. */}
      <div
        className="notification-filters"
        role="group"
        aria-label="알림 표시 범위"
      >
        {[false, true].map((onlyUnread) => (
          <button
            key={String(onlyUnread)}
            className="button secondary"
            aria-pressed={unreadOnly === onlyUnread}
            disabled={reading}
            onClick={() => {
              setParams(notificationPageParams(1, onlyUnread));
            }}
          >
            {onlyUnread ? "읽지 않음" : "전체 알림"}
          </button>
        ))}
      </div>

      <section
        className="notification-results"
        ref={listRef}
        tabIndex={-1}
        aria-label="여행 알림 목록"
        aria-busy={query.isFetching}
      >
        {privateAccessError ? (
          <ErrorState
            error={privateAccessError}
            retry={async () => {
              setAccessFailure(null);
              const result = await query.refetch();
              if (notificationAccessLost(result.error))
                hidePrivateNotifications(result.error);
            }}
          />
        ) : query.isPending ? (
          <Loading text="여행 알림을 불러오고 있어요" />
        ) : query.error && (!data || !data.items.length) ? (
          <ErrorState error={query.error} retry={() => query.refetch()} />
        ) : !data?.items.length ? (
          <Empty
            title={
              unreadOnly
                ? "읽지 않은 알림이 없어요"
                : "새로운 여행 알림이 없어요"
            }
            text={
              unreadOnly
                ? "모두 확인했어요. 읽은 알림은 ‘전체 알림’에서 다시 볼 수 있어요."
                : "동행이 투표를 만들거나 마감하면 이곳에서 바로 알려 드릴게요."
            }
          />
        ) : (
          <>
            {query.error && (
              <div className="notice notification-refresh-error" role="status">
                최신 알림을 확인하지 못했어요. 마지막으로 불러온 목록을 보여
                드리고 있어요.
                <button
                  className="text-button"
                  onClick={() => void query.refetch()}
                >
                  다시 확인
                </button>
              </div>
            )}
            <div
              className="notification-summary"
              role="status"
              aria-live="polite"
            >
              <strong>
                {unreadOnly ? "미확인 결과" : "전체"}{" "}
                {data.total.toLocaleString()}개 · 읽지 않음{" "}
                {data.unreadCount.toLocaleString()}개
              </strong>
              <span>
                최신순 · {first.toLocaleString()}–
                {(first + data.items.length - 1).toLocaleString()}번째 알림
              </span>
            </div>
            <div className="notification-list">
              {data.items.map((notification) => (
                <article
                  className={`notification-item ${notification.readAt ? "is-read" : "is-unread"}`}
                  key={notification.id}
                >
                  <span className="notification-kind-icon" aria-hidden="true">
                    {notification.type === "TRIP_POLL_CLOSED" ? (
                      <Check size={20} />
                    ) : (
                      <Vote size={20} />
                    )}
                  </span>
                  <button
                    className="notification-open"
                    disabled={reading}
                    onClick={() => void openNotification(notification)}
                  >
                    <span className="notification-state">
                      {!notification.readAt && (
                        <span
                          className="notification-unread-dot"
                          aria-hidden="true"
                        />
                      )}
                      {notification.readAt ? "읽은 알림" : "읽지 않은 알림"}
                    </span>
                    <strong>{notificationCopy(notification)}</strong>
                    <span className="notification-trip-title">
                      {notification.tripTitle} · {notification.pollTitle}
                    </span>
                    <time dateTime={notification.createdAt}>
                      {new Date(notification.createdAt).toLocaleString("ko-KR")}
                    </time>
                  </button>
                  {!notification.readAt && (
                    <button
                      className="button secondary small-button notification-read-button"
                      aria-label={`${notification.pollTitle} 투표 알림 읽음 표시`}
                      disabled={reading}
                      onClick={async () => {
                        if (await markRead(notification)) {
                          // 미확인 항목을 지우면 누른 버튼도 없어지므로 결과 영역으로 포커스를 이어 줍니다.
                          if (unreadOnly)
                            listRef.current?.focus({ preventScroll: true });
                        }
                      }}
                    >
                      읽음 표시
                    </button>
                  )}
                </article>
              ))}
            </div>
            {data.totalPages > 1 && (
              <nav
                className="notification-pagination"
                aria-label="알림 페이지 이동"
              >
                <button
                  className="button secondary"
                  disabled={reading || data.page <= 1}
                  onClick={() => {
                    pageFocus.current = true;
                    setParams(
                      notificationPageParams(data.page - 1, unreadOnly),
                    );
                  }}
                >
                  <ArrowLeft size={17} />
                  이전
                </button>
                <span aria-current="page">
                  {data.page.toLocaleString()} /{" "}
                  {data.totalPages.toLocaleString()} 페이지
                </span>
                <button
                  className="button secondary"
                  disabled={reading || data.page >= data.totalPages}
                  onClick={() => {
                    pageFocus.current = true;
                    setParams(
                      notificationPageParams(data.page + 1, unreadOnly),
                    );
                  }}
                >
                  다음
                  <ArrowRight size={17} />
                </button>
              </nav>
            )}
          </>
        )}
      </section>
      {query.isFetching && !query.isPending && (
        <p className="notification-refresh" role="status">
          <Bell size={15} /> 최신 알림을 확인하고 있어요.
        </p>
      )}
    </div>
  );
}
