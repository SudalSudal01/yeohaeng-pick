import type { QueryClient } from "@tanstack/react-query";
import {
  ApiError,
  commitExpectedMember,
  invalidateSessionRequests,
} from "./api";

const changing = new WeakSet<QueryClient>();
const remoteChanging = new WeakMap<QueryClient, Set<string>>();
export const SESSION_TRANSITION_EVENT = "yeohaeng:session-transition";
export const SESSION_CHANNEL = "yeohaeng-pick:session-changes";
export type SessionNotice = { phase: "started" | "finished"; id: string };
/** 탭 사이에는 전환 여부와 임의 식별자만 전달합니다. 회원 정보·토큰·비밀번호를 보내지 않습니다. */
export function sessionNotice(value: unknown): SessionNotice | undefined {
  if (!value || typeof value !== "object") return undefined;
  const data = value as Partial<SessionNotice>;
  if (
    (data.phase !== "started" && data.phase !== "finished") ||
    typeof data.id !== "string" ||
    !data.id ||
    data.id.length > 128
  )
    return undefined;
  return { phase: data.phase, id: data.id };
}
export function remoteSessionChange(
  client: QueryClient,
  notice: SessionNotice,
) {
  const pending = remoteChanging.get(client) || new Set<string>();
  if (notice.phase === "started") pending.add(notice.id);
  else pending.delete(notice.id);
  remoteChanging.set(client, pending);
}
export function clearRemoteSessionChanges(client: QueryClient) {
  remoteChanging.delete(client);
}
export const sessionTransitionPending = (client: QueryClient) =>
  changing.has(client) || Boolean(remoteChanging.get(client)?.size);

export function clearSessionCache(client: QueryClient) {
  commitExpectedMember(null);
  void client.cancelQueries();
  // me Query를 없애면 App의 기존 관찰자가 옛 회원을 계속 표시할 수 있습니다. 같은 Query 객체를 유지합니다.
  client.removeQueries({ predicate: (query) => query.queryKey[0] !== "me" });
  client.getMutationCache().clear();
  client.setQueryData(["me"], null);
}

export function startSessionChange(client: QueryClient) {
  invalidateSessionRequests();
  clearSessionCache(client);
}

/** 로그인·로그아웃·재설정·탈퇴가 서로 다른 순서로 끝나 쿠키와 화면 계정이 엇갈리지 않게 합니다.
 * 화면이 바뀌어 useAuth가 다시 생겨도 같은 QueryClient는 진행 중 인증 변경을 공유합니다. */
export async function runSessionTransition<T>(
  client: QueryClient,
  action: () => Promise<T>,
) {
  if (sessionTransitionPending(client))
    throw new ApiError(
      409,
      "로그인 상태를 변경하고 있어요. 잠시 후 다시 시도해 주세요.",
    );
  changing.add(client);
  const id =
    globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random()}`;
  let announced = false;
  const announce = (phase: SessionNotice["phase"]) => {
    if (typeof window !== "undefined")
      window.dispatchEvent(
        new CustomEvent(SESSION_TRANSITION_EVENT, { detail: { phase, id } }),
      );
  };
  const execute = () => {
    announced = true;
    announce("started");
    return action();
  };
  try {
    // HTTPS/localhost의 Web Locks가 있으면 같은 출처의 다른 탭과도 쿠키 변경을 직렬화합니다.
    // 진행 중이면 자동 재전송하지 않고 사용자가 다시 시도하도록 안내합니다.
    if (typeof navigator !== "undefined" && navigator.locks)
      return await navigator.locks.request(
        "yeohaeng-pick:session",
        { ifAvailable: true },
        async (lock) => {
          if (!lock)
            throw new ApiError(
              409,
              "다른 창에서 로그인 상태를 변경하고 있어요. 잠시 후 다시 시도해 주세요.",
            );
          return execute();
        },
      );
    return await execute();
  } finally {
    changing.delete(client);
    if (announced) announce("finished");
  }
}
