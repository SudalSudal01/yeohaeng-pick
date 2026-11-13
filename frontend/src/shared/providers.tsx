import {
  createContext,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  api,
  ApiError,
  announceSessionExpired,
  invalidateSessionRequests,
  SESSION_EXPIRED_EVENT,
} from "./api";
import type { Member, Config } from "./types";
import {
  runSessionTransition,
  sessionTransitionPending,
  clearSessionCache,
  clearRemoteSessionChanges,
  remoteSessionChange,
  sessionNotice,
  SESSION_CHANNEL,
  SESSION_TRANSITION_EVENT,
  startSessionChange,
} from "./sessionTransition";
import { X, CheckCircle2, AlertCircle } from "lucide-react";

/** me 확인은 현재 쿠키 계정만 조회합니다. 다른 탭의 계정 변경을 발견해도 이전 개인 자료를 새 회원에게 붙이지 않습니다. */
async function currentMember(client: ReturnType<typeof useQueryClient>) {
  try {
    const member = await api<Member>("/auth/me");
    const previous = client.getQueryData<Member | null>(["me"]);
    if (previous && previous.id !== member.id) {
      invalidateSessionRequests();
      void client.cancelQueries({
        predicate: (query) => query.queryKey[0] !== "me",
      });
      client.removeQueries({
        predicate: (query) => query.queryKey[0] !== "me",
      });
      client.getMutationCache().clear();
    }
    return member;
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      if (client.getQueryData<Member | null>(["me"])) announceSessionExpired();
      return null;
    }
    throw e;
  }
}

/** App에서 한 번만 구독합니다. 어떤 보호 요청이 먼저 401을 받아도 비공개 캐시를 비우고 인증 관찰자에게 즉시 알립니다. */
export function useSessionExpiryBoundary() {
  const client = useQueryClient();
  const [, changed] = useState(0);
  useEffect(() => {
    let channel: BroadcastChannel | undefined;
    let recovery: ReturnType<typeof setTimeout> | undefined;
    let closed = false;
    const recheck = () => {
      if (closed || sessionTransitionPending(client)) return;
      // disabled였던 관찰자가 다시 렌더되기 전에도 확인을 시작해 원래 비공개 경로에서 Loading을 유지합니다.
      void client
        .fetchQuery({
          queryKey: ["me"],
          queryFn: () => currentMember(client),
          staleTime: 0,
          retry: false,
        })
        .catch(() => {
          /* 오류는 me 관찰자의 상태로 전달합니다. */
        });
    };
    const expire = () => {
      clearSessionCache(client);
      changed((value) => value + 1);
    };
    const recoverMissingFinish = () => {
      if (recovery) clearTimeout(recovery);
      if (!sessionTransitionPending(client)) return;
      // 전환한 탭이 닫혀 완료 신호가 사라져도 무한 로딩하지 않습니다. 현재 쿠키를 서버에서 다시 확인합니다.
      recovery = setTimeout(() => {
        clearRemoteSessionChanges(client);
        startSessionChange(client);
        changed((value) => value + 1);
        recheck();
      }, 45000);
    };
    const localChange = (event: Event) => {
      const notice = sessionNotice((event as CustomEvent<unknown>).detail);
      if (!notice) return;
      try {
        channel?.postMessage(notice);
      } catch {
        /* 채널 미지원/종료 때도 서버의 기대 회원 검사는 유지합니다. */
      }
      changed((value) => value + 1);
    };
    try {
      if (typeof BroadcastChannel !== "undefined") {
        channel = new BroadcastChannel(SESSION_CHANNEL);
        channel.onmessage = (event: MessageEvent<unknown>) => {
          const notice = sessionNotice(event.data);
          if (!notice || closed) return;
          remoteSessionChange(client, notice);
          startSessionChange(client);
          changed((value) => value + 1);
          recoverMissingFinish();
          if (notice.phase === "finished") recheck();
        };
      }
    } catch {
      /* 탭 통신이 없는 환경은 창 활성화 재확인과 서버 기대 회원 검사로 보호합니다. */
    }
    window.addEventListener(SESSION_EXPIRED_EVENT, expire);
    window.addEventListener(SESSION_TRANSITION_EVENT, localChange);
    recoverMissingFinish();
    return () => {
      closed = true;
      if (recovery) clearTimeout(recovery);
      channel?.close();
      window.removeEventListener(SESSION_EXPIRED_EVENT, expire);
      window.removeEventListener(SESSION_TRANSITION_EVENT, localChange);
    };
  }, [client]);
}

/** 로그인 상태는 서버에서 확인합니다. 새로고침·로그아웃·다른 계정 로그인 시 이전 회원 데이터 캐시를 비웁니다. */
export function useAuth() {
  const client = useQueryClient();
  const query = useQuery({
    queryKey: ["me"],
    // 로그인 응답의 본문을 기다리는 동안 me 조회가 옛 서버 세션을 화면에 다시 넣지 않게 합니다.
    enabled: !sessionTransitionPending(client),
    queryFn: () => currentMember(client),
    retry: false,
    refetchOnWindowFocus: true,
    refetchOnReconnect: true,
  });
  const clearPrivate = () => {
    clearSessionCache(client);
  };
  const login = (email: string, password: string) =>
    runSessionTransition(client, async () => {
      // Set-Cookie는 본문보다 먼저 적용될 수 있습니다. 실패/유실 결과를 기다리는 동안에도
      // 이전 계정의 화면·캐시를 새 서버 세션과 섞지 않도록 전환 시작 즉시 정리합니다.
      startSessionChange(client);
      try {
        const member = await api<Member>("/auth/login", {
          method: "POST",
          body: { email, password },
        });
        if (
          !member ||
          typeof member.id !== "string" ||
          !member.id ||
          typeof member.nickname !== "string" ||
          typeof member.email !== "string" ||
          !["USER", "ADMIN"].includes(member.role) ||
          !Array.isArray(member.preferences)
        )
          throw new ApiError(
            502,
            "로그인 응답을 확인하지 못했어요. 다시 로그인해 주세요.",
          );
        clearPrivate();
        client.setQueryData(["me"], member);
      } catch (error) {
        clearPrivate();
        throw error;
      }
    });
  const logout = () =>
    runSessionTransition(client, async () => {
      startSessionChange(client);
      try {
        await api("/auth/logout", {
          method: "POST",
          expectedMemberId: query.data?.id,
        });
      } finally {
        clearPrivate();
      }
    });
  return {
    user: query.data,
    loading:
      query.isPending ||
      sessionTransitionPending(client) ||
      (!query.data && query.isFetching),
    login,
    logout,
  };
}
export function useConfig() {
  return useQuery({
    queryKey: ["config"],
    queryFn: () => api<Config>("/config"),
    staleTime: 60000,
  });
}
type Toast = { id: number; text: string; error: boolean };
const ToastContext = createContext<(text: string, error?: boolean) => void>(
  () => {},
);
export const useToast = () => useContext(ToastContext);
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const dismiss = (id: number) =>
    setToasts((t) => t.filter((x) => x.id !== id));
  const notify = (text: string, error = false) => {
    const id = Date.now() + Math.random();
    setToasts((t) => [...t.slice(-2), { id, text, error }]);
    setTimeout(() => dismiss(id), 6000);
  };
  return (
    <ToastContext value={notify}>
      {children}
      <div className="toasts" aria-live="polite">
        {toasts.map((t) => (
          <div className={`toast ${t.error ? "error" : ""}`} key={t.id}>
            {t.error ? <AlertCircle size={19} /> : <CheckCircle2 size={19} />}
            <span>{t.text}</span>
            <button
              className="icon-button"
              aria-label="알림 닫기"
              onClick={() => dismiss(t.id)}
            >
              <X size={17} />
            </button>
          </div>
        ))}
      </div>
    </ToastContext>
  );
}
