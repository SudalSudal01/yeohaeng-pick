import { afterEach, expect, it, vi } from "vitest";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.resetModules();
});

it("App에 커밋된 회원을 요청 시작 때 캡처해 CSRF 대기 중 다른 회원 헤더로 바꾸지 않는다", async () => {
  let security!: (value: Response) => void;
  const fetcher = vi
    .fn()
    .mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          security = resolve;
        }),
    )
    .mockResolvedValueOnce(Response.json({ saved: true }));
  vi.stubGlobal("fetch", fetcher);
  const { api, commitExpectedMember } = await import("./api");
  commitExpectedMember("member-a");
  const saving = api("/auth/profile", {
    method: "PUT",
    body: { nickname: "a" },
  });
  commitExpectedMember("member-b");
  security(Response.json({ token: "security-token" }));
  await saving;
  expect(fetcher.mock.calls[1][1].headers["X-Travel-Member"]).toBe("member-a");
});

it("문맥을 종료한 상태의 비공개 변경은 보안 토큰도 요청하기 전에 차단한다", async () => {
  const fetcher = vi.fn();
  vi.stubGlobal("fetch", fetcher);
  const { api, commitExpectedMember } = await import("./api");
  commitExpectedMember(null);
  await expect(
    api("/trips", { method: "POST", body: {} }),
  ).rejects.toMatchObject({ status: 401 });
  expect(fetcher).not.toHaveBeenCalled();
});

it("me는 기대 회원 헤더 없이 현재 쿠키의 계정을 재확인한다", async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(Response.json({ id: "member-b" }));
  vi.stubGlobal("fetch", fetcher);
  const { api, commitExpectedMember } = await import("./api");
  commitExpectedMember("member-a");
  await expect(api("/auth/me")).resolves.toMatchObject({ id: "member-b" });
  expect(fetcher.mock.calls[0][1].headers["X-Travel-Member"]).toBeUndefined();
});

it("탈퇴는 문맥 종료 전 캡처한 회원을 명시해 서버가 새 쿠키와의 불일치를 검사할 수 있다", async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(Response.json({ token: "security-token" }))
    .mockResolvedValueOnce(new Response(null, { status: 204 }));
  vi.stubGlobal("fetch", fetcher);
  const { api, invalidateSessionRequests, commitExpectedMember } =
    await import("./api");
  commitExpectedMember("member-a");
  invalidateSessionRequests();
  await api("/auth/account", {
    method: "DELETE",
    body: { confirmed: true },
    expectedMemberId: "member-a",
  });
  expect(fetcher.mock.calls[1][1].headers["X-Travel-Member"]).toBe("member-a");
  expect(fetcher.mock.calls[1][1].credentials).toBe("same-origin");
});

it("계정 불일치는 오류 본문이 멈춰도 헤더에서 바로 화면 문맥을 종료한다", async () => {
  const dispatchEvent = vi.fn();
  vi.stubGlobal("window", { dispatchEvent });
  const response = new Response("never consumed", {
    status: 409,
    headers: { "X-Travel-Account-Changed": "true" },
  });
  const read = vi.spyOn(response, "text");
  vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(response));
  const { api, commitExpectedMember, SESSION_EXPIRED_EVENT } =
    await import("./api");
  commitExpectedMember("member-a");
  await expect(api("/trips")).rejects.toMatchObject({ status: 401 });
  expect(read).not.toHaveBeenCalled();
  expect(dispatchEvent).toHaveBeenCalledTimes(1);
  expect(dispatchEvent.mock.calls[0][0].type).toBe(SESSION_EXPIRED_EVENT);
  await expect(api("/trips", { method: "POST" })).rejects.toMatchObject({
    status: 401,
  });
});

it("새 로그인 뒤 도착한 옛 계정 불일치 응답은 새 회원을 종료시키지 않는다", async () => {
  let finish!: (value: Response) => void;
  const dispatchEvent = vi.fn();
  vi.stubGlobal("window", { dispatchEvent });
  const fetcher = vi
    .fn()
    .mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve;
        }),
    )
    .mockResolvedValueOnce(Response.json({ token: "new-security" }))
    .mockResolvedValueOnce(Response.json({ saved: true }));
  vi.stubGlobal("fetch", fetcher);
  const { api, commitExpectedMember, invalidateSessionRequests } =
    await import("./api");
  commitExpectedMember("member-a");
  const old = api("/trips");
  const refused = expect(old).rejects.toMatchObject({ status: 409 });
  invalidateSessionRequests();
  commitExpectedMember("member-b");
  finish(
    Response.json(
      { code: "ACCOUNT_CHANGED" },
      { status: 409, headers: { "X-Travel-Account-Changed": "true" } },
    ),
  );
  await refused;
  expect(dispatchEvent).not.toHaveBeenCalled();
  await expect(
    api("/auth/profile", { method: "PUT", body: { nickname: "b" } }),
  ).resolves.toEqual({ saved: true });
  expect(fetcher.mock.calls[2][1].headers["X-Travel-Member"]).toBe("member-b");
});
