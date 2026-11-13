import { afterEach, describe, expect, it, vi } from "vitest";
import { api, SESSION_EXPIRED_EVENT } from "./api";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
describe("인증 종료 공통 신호", () => {
  it("보호 API가 401이면 오류와 함께 인증 종료를 한 번 알린다", async () => {
    const dispatchEvent = vi.fn();
    vi.stubGlobal("window", { dispatchEvent });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ message: "로그인해 주세요" }), {
          status: 401,
        }),
      ),
    );
    await expect(api("/messages")).rejects.toMatchObject({ status: 401 });
    expect(dispatchEvent).toHaveBeenCalledTimes(1);
    expect(dispatchEvent.mock.calls[0][0].type).toBe(SESSION_EXPIRED_EVENT);
  });
  it("익명 상태 확인과 로그인 실패는 전역 세션 종료로 취급하지 않는다", async () => {
    const dispatchEvent = vi.fn();
    vi.stubGlobal("window", { dispatchEvent });
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockImplementation(() =>
          Promise.resolve(
            new Response(JSON.stringify({ message: "확인" }), { status: 401 }),
          ),
        ),
    );
    for (const path of ["/auth/me", "/auth/login", "/auth/register"]) {
      await expect(api(path)).rejects.toMatchObject({ status: 401 });
    }
    expect(dispatchEvent).not.toHaveBeenCalled();
  });
  it("수신 설정·접근 권한의 403은 인증 세션 전체를 종료하지 않는다", async () => {
    const dispatchEvent = vi.fn();
    vi.stubGlobal("window", { dispatchEvent });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ message: "현재 요청할 수 없어요" }), {
          status: 403,
        }),
      ),
    );
    await expect(api("/messages/recipients/a")).rejects.toMatchObject({
      status: 403,
    });
    expect(dispatchEvent).not.toHaveBeenCalled();
  });
  it("비밀번호 재설정 성공도 서버 세션 종료에 맞춰 비공개 캐시 정리를 알린다", async () => {
    const dispatchEvent = vi.fn();
    vi.stubGlobal("window", { dispatchEvent });
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(Response.json({ token: "reset-csrf" }))
        .mockResolvedValueOnce(
          Response.json({ message: "비밀번호를 변경했어요." }),
        ),
    );
    await api("/auth/reset", {
      method: "POST",
      body: { token: "a".repeat(64), password: "New-password12!" },
    });
    expect(dispatchEvent).toHaveBeenCalledTimes(1);
    expect(dispatchEvent.mock.calls[0][0].type).toBe(SESSION_EXPIRED_EVENT);
  });
});
