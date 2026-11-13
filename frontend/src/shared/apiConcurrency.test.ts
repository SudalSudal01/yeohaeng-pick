import { afterEach, expect, it, vi } from "vitest";
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
  vi.resetModules();
});

it("동시에 시작한 변경 요청은 하나의 CSRF 발급 응답을 공유한다", async () => {
  let issue!: (value: Response) => void;
  const fetcher = vi.fn().mockImplementation((url: string) =>
    url.endsWith("/csrf")
      ? new Promise<Response>((resolve) => {
          issue = resolve;
        })
      : Promise.resolve(Response.json({ ok: true })),
  );
  vi.stubGlobal("fetch", fetcher);
  const { api } = await import("./api");
  const a = api("/trips", { method: "POST", body: { title: "a" } }),
    b = api("/messages", { method: "POST", body: { body: "b" } });
  issue(Response.json({ token: "shared-csrf" }));
  await Promise.all([a, b]);
  expect(fetcher).toHaveBeenCalledTimes(3);
  for (const call of fetcher.mock.calls.slice(1))
    expect(call[1].headers["X-XSRF-TOKEN"]).toBe("shared-csrf");
});

it("CSRF 발급을 기다리는 동안 세션이 끝나면 옛 입력을 새 세션으로 보내지 않는다", async () => {
  let issue!: (value: Response) => void;
  const fetcher = vi.fn().mockImplementation(
    () =>
      new Promise<Response>((resolve) => {
        issue = resolve;
      }),
  );
  vi.stubGlobal("fetch", fetcher);
  const { api, announceSessionExpired } = await import("./api");
  const reply = api("/trips", { method: "POST", body: { title: "old draft" } });
  const expected = expect(reply).rejects.toMatchObject({ status: 409 });
  announceSessionExpired();
  issue(Response.json({ token: "old-csrf" }));
  await expected;
  expect(fetcher).toHaveBeenCalledTimes(1);
});

it("늦게 도착한 옛 401은 새 로그인 세션을 종료시키지 않는다", async () => {
  let oldReply!: (value: Response) => void;
  const dispatchEvent = vi.fn();
  vi.stubGlobal("window", { dispatchEvent });
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementation((url: string) => {
      if (url === "/api/auth/me")
        return new Promise<Response>((resolve) => {
          oldReply = resolve;
        });
      if (url.endsWith("/csrf"))
        return Promise.resolve(Response.json({ token: "new-csrf" }));
      return Promise.resolve(Response.json({ id: "new-member" }));
    }),
  );
  const { api } = await import("./api");
  const earlier = api("/auth/me");
  const expected = expect(earlier).rejects.toMatchObject({ status: 409 });
  await api("/auth/login", {
    method: "POST",
    body: { email: "new@example.test", password: "test-only" },
  });
  oldReply(Response.json({ message: "old session" }, { status: 401 }));
  await expected;
  expect(dispatchEvent).not.toHaveBeenCalled();
});

it("응답 없는 조회는 시간 제한 후 종료하고 자동으로 다시 요청하지 않는다", async () => {
  vi.useFakeTimers();
  const fetcher = vi.fn(
    (_url: string, options: RequestInit) =>
      new Promise<Response>((_resolve, reject) =>
        options.signal?.addEventListener("abort", () =>
          reject(new Error("aborted")),
        ),
      ),
  );
  vi.stubGlobal("fetch", fetcher);
  const { api } = await import("./api");
  const pending = api("/messages");
  const expected = expect(pending).rejects.toMatchObject({ status: 0 });
  await vi.advanceTimersByTimeAsync(30001);
  await expected;
  expect(fetcher).toHaveBeenCalledTimes(1);
  expect(fetcher.mock.calls[0][1].signal?.aborted).toBe(true);
});

it("CSRF 조회가 먼저 세션 종료를 감지해도 비공개 화면 정리를 알린다", async () => {
  const dispatchEvent = vi.fn();
  vi.stubGlobal("window", { dispatchEvent });
  const fetcher = vi
    .fn()
    .mockResolvedValue(
      Response.json({ message: "인증 변경" }, { status: 401 }),
    );
  vi.stubGlobal("fetch", fetcher);
  const { api } = await import("./api");
  await expect(api("/trips", { method: "POST" })).rejects.toMatchObject({
    status: 401,
  });
  expect(dispatchEvent).toHaveBeenCalledTimes(1);
  expect(fetcher).toHaveBeenCalledTimes(1);
});

it("과거 쿠키로 시작한 공개 설정 조회의 늦은 401도 새 로그인을 종료하지 않는다", async () => {
  const dispatchEvent = vi.fn();
  vi.stubGlobal("window", { dispatchEvent });
  let release!: (response: Response) => void;
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementation((url: string) =>
      url === "/api/config"
        ? new Promise<Response>((resolve) => {
            release = resolve;
          })
        : Promise.resolve(
            Response.json(
              url.endsWith("/csrf") ? { token: "csrf" } : { id: "current" },
            ),
          ),
    ),
  );
  const { api } = await import("./api");
  const old = api("/config");
  const expected = expect(old).rejects.toMatchObject({ status: 401 });
  await api("/auth/login", {
    method: "POST",
    body: { email: "current@example.test", password: "test-only" },
  });
  release(Response.json({ message: "old cookie" }, { status: 401 }));
  await expected;
  expect(dispatchEvent).not.toHaveBeenCalled();
});

it("헤더와 본문 일부만 도착한 응답도 본문 완료까지 같은 시간 제한을 적용한다", async () => {
  vi.useFakeTimers();
  const fetcher = vi.fn((_url: string, options: RequestInit) => {
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode("{"));
        options.signal?.addEventListener("abort", () =>
          controller.error(new Error("aborted body")),
        );
      },
    });
    return Promise.resolve(new Response(stream, { status: 200 }));
  });
  vi.stubGlobal("fetch", fetcher);
  const { api } = await import("./api");
  const pending = api("/messages");
  const expected = expect(pending).rejects.toMatchObject({ status: 0 });
  await vi.advanceTimersByTimeAsync(30001);
  await expected;
  expect(fetcher).toHaveBeenCalledTimes(1);
});

it("401은 본문이 끝나지 않아도 헤더에서 즉시 비공개 자료 정리를 알린다", async () => {
  const dispatchEvent = vi.fn();
  vi.stubGlobal("window", { dispatchEvent });
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementation(() =>
      Promise.resolve(
        new Response(
          new ReadableStream({
            start(controller) {
              controller.enqueue(new TextEncoder().encode("{"));
            },
          }),
          { status: 401 },
        ),
      ),
    ),
  );
  const { api } = await import("./api");
  await expect(api("/messages")).rejects.toMatchObject({ status: 401 });
  expect(dispatchEvent).toHaveBeenCalledTimes(1);
});
