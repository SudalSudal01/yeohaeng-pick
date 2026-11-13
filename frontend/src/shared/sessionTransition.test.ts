import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { afterEach, expect, it, vi } from "vitest";
import {
  clearSessionCache,
  remoteSessionChange,
  runSessionTransition,
  sessionNotice,
  sessionTransitionPending,
  SESSION_TRANSITION_EVENT,
} from "./sessionTransition";
afterEach(() => vi.unstubAllGlobals());

it("첫 인증 요청이 끝나기 전에 다른 로그인/로그아웃 HTTP를 시작하지 않는다", async () => {
  const client = new QueryClient();
  let finish!: () => void;
  let started!: () => void;
  const ready = new Promise<void>((resolve) => {
    started = resolve;
  });
  const first = runSessionTransition(
    client,
    () =>
      new Promise<void>((resolve) => {
        finish = resolve;
        started();
      }),
  );
  await ready;
  const next = vi.fn(async () => "new account");
  await expect(runSessionTransition(client, next)).rejects.toMatchObject({
    status: 409,
  });
  expect(next).not.toHaveBeenCalled();
  finish();
  await first;
  await expect(runSessionTransition(client, next)).resolves.toBe("new account");
});

it("인증 서버 오류 뒤에는 사용자가 다시 시도할 수 있다", async () => {
  const client = new QueryClient();
  await expect(
    runSessionTransition(client, async () => {
      throw new Error("network");
    }),
  ).rejects.toThrow("network");
  await expect(runSessionTransition(client, async () => "retry")).resolves.toBe(
    "retry",
  );
});

it("다른 탭이 인증 쿠키를 변경 중이면 새 인증 HTTP를 실행하지 않는다", async () => {
  vi.stubGlobal("navigator", {
    locks: {
      request: vi.fn((_name, _options, callback) =>
        Promise.resolve(callback(null)),
      ),
    },
  });
  const action = vi.fn(async () => "new session");
  await expect(
    runSessionTransition(new QueryClient(), action),
  ).rejects.toMatchObject({ status: 409 });
  expect(action).not.toHaveBeenCalled();
});

it("캐시 정리는 me Query와 관찰자를 유지해 null과 다음 회원을 같은 화면 구독자에게 알린다", () => {
  const client = new QueryClient();
  client.setQueryData(["me"], { id: "member-a" });
  client.setQueryData(["trip", "private-id"], { title: "private trip" });
  const observer = new QueryObserver(client, {
    queryKey: ["me"],
    enabled: false,
  });
  const unsubscribe = observer.subscribe(() => {});
  const before = client.getQueryCache().find({ queryKey: ["me"], exact: true });
  clearSessionCache(client);
  expect(client.getQueryCache().find({ queryKey: ["me"], exact: true })).toBe(
    before,
  );
  expect(observer.getCurrentResult().data).toBeNull();
  expect(client.getQueryData(["trip", "private-id"])).toBeUndefined();
  client.setQueryData(["me"], { id: "member-b" });
  expect(observer.getCurrentResult().data).toEqual({ id: "member-b" });
  unsubscribe();
  client.clear();
});

it("탭 알림에는 비개인 전환 표시만 전달하고 겹친 전환은 모두 끝나야 재확인한다", async () => {
  const client = new QueryClient();
  const events: CustomEvent[] = [];
  vi.stubGlobal("window", {
    dispatchEvent: (event: CustomEvent) => {
      events.push(event);
      return true;
    },
  });
  await runSessionTransition(client, async () => "private member value");
  expect(events.map((event) => event.type)).toEqual([
    SESSION_TRANSITION_EVENT,
    SESSION_TRANSITION_EVENT,
  ]);
  expect(events.map((event) => event.detail.phase)).toEqual([
    "started",
    "finished",
  ]);
  expect(events[0].detail.id).toBe(events[1].detail.id);
  expect(Object.keys(events[0].detail).sort()).toEqual(["id", "phase"]);
  expect(
    sessionNotice({
      phase: "started",
      id: "one",
      nickname: "must-not-forward",
    }),
  ).toEqual({ phase: "started", id: "one" });
  expect(sessionNotice({ phase: "unknown", id: "one" })).toBeUndefined();
  remoteSessionChange(client, { phase: "started", id: "one" });
  remoteSessionChange(client, { phase: "started", id: "two" });
  remoteSessionChange(client, { phase: "finished", id: "one" });
  expect(sessionTransitionPending(client)).toBe(true);
  const competing = vi.fn(async () => "must not send login HTTP");
  await expect(runSessionTransition(client, competing)).rejects.toMatchObject({
    status: 409,
  });
  expect(competing).not.toHaveBeenCalled();
  remoteSessionChange(client, { phase: "finished", id: "two" });
  expect(sessionTransitionPending(client)).toBe(false);
});
