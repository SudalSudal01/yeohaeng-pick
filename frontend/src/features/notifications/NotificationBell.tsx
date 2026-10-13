import { useEffect } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Bell } from "lucide-react";
import { NavLink } from "react-router-dom";
import { api } from "../../shared/api";
import { notificationAccessLost } from "./notifications";
import "./notificationBell.css";

type UnreadCount = { unreadCount: number };

/**
 * 헤더에서는 내용 대신 미확인 개수만 가볍게 조회합니다. 30초 주기와 창 포커스 시
 * 재조회로 동행의 새 투표를 알리되, 실패했을 때 기존 내비게이션 사용을 막지 않습니다.
 */
export default function NotificationBell() {
  const client = useQueryClient();
  const query = useQuery({
    queryKey: ["notification-unread-count"],
    queryFn: () => api<UnreadCount>("/notifications/unread-count"),
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
    retry: false,
  });
  const accessLost = notificationAccessLost(query.error);
  useEffect(() => {
    if (!accessLost) return;
    // 헤더 조회에서 접근 종료를 먼저 발견해도 이전 계정의 목록과 숫자를 남기지 않습니다.
    void client.cancelQueries({ queryKey: ["notifications"] });
    client.removeQueries({ queryKey: ["notifications"] });
    client.setQueryData(["notification-unread-count"], { unreadCount: 0 });
  }, [accessLost, client]);
  const unreadCount = accessLost ? 0 : (query.data?.unreadCount ?? 0);
  const badge = unreadCount > 99 ? "99+" : String(unreadCount);

  return (
    <NavLink
      to="/notifications"
      className="icon-button notification-bell"
      aria-label={
        unreadCount
          ? `알림 센터, 읽지 않은 알림 ${unreadCount.toLocaleString()}개`
          : "알림 센터"
      }
    >
      <Bell size={21} aria-hidden="true" />
      {unreadCount > 0 && (
        <span className="notification-badge" aria-hidden="true">
          {badge}
        </span>
      )}
    </NavLink>
  );
}
