import { afterEach, expect, it, vi } from "vitest";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.resetModules();
});
it("로그아웃 뒤 다음 계정의 변경 요청에는 새 CSRF 토큰을 사용한다", async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(Response.json({ token: "first-token" }))
    .mockResolvedValueOnce(new Response(null, { status: 204 }))
    .mockResolvedValueOnce(Response.json({ token: "new-token" }))
    .mockResolvedValueOnce(Response.json({ id: "new-user" }));
  vi.stubGlobal("fetch", fetcher);
  const { api } = await import("./api");
  await api("/auth/logout", { method: "POST" });
  await api("/auth/login", {
    method: "POST",
    body: { email: "fixture@example.test", password: "test-only" },
  });
  expect(fetcher.mock.calls[3][1].headers["X-XSRF-TOKEN"]).toBe("new-token");
  expect(fetcher.mock.calls[3][1].credentials).toBe("same-origin");
});
it("보안 토큰 거절 후 다음 시도에서는 토큰을 재발급한다", async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(Response.json({ token: "expired" }))
    .mockResolvedValueOnce(
      Response.json({ message: "보안 토큰 만료" }, { status: 403 }),
    )
    .mockResolvedValueOnce(Response.json({ token: "fresh" }))
    .mockResolvedValueOnce(Response.json({ saved: true }));
  vi.stubGlobal("fetch", fetcher);
  const { api } = await import("./api");
  await expect(api("/trips", { method: "POST" })).rejects.toMatchObject({
    status: 403,
    message: "보안 토큰 만료",
  });
  await api("/trips", { method: "POST" });
  expect(fetcher.mock.calls[3][1].headers["X-XSRF-TOKEN"]).toBe("fresh");
});
