import { describe, expect, it } from "vitest";
import { RequestAttempts } from "./requestAttempt";

function attempts() {
  let next = 0;
  return new RequestAttempts(() => `request-${++next}`);
}

describe("저장 응답 유실의 재시도 키", () => {
  it("같은 회원·대상·본문은 객체 키 순서가 달라도 같은 요청으로 재시도한다", () => {
    const pending = attempts();
    const first = pending.begin("member:trip:expense", {
      label: "점심",
      amount: 35000,
    });
    expect(
      pending.begin("member:trip:expense", { amount: 35000, label: "점심" }),
    ).toBe(first);
    expect(
      pending.matches("member:trip:expense", { label: "점심", amount: 35000 }),
    ).toBe(true);
  });
  it("회원·여행·작업·추천 코스가 다르면 본문이 같아도 서로 다른 요청이다", () => {
    const pending = attempts();
    const body = { content: "준비물" };
    const keys = [
      "a:trip-1:task",
      "b:trip-1:task",
      "a:trip-2:task",
      "a:trip-1:expense",
      "a:recommendation-1:course-0",
      "a:recommendation-1:course-1",
    ].map((scope) => pending.begin(scope, body));
    expect(new Set(keys).size).toBe(keys.length);
  });
  it("본문·선택 순서가 바뀌면 새 요청을 만들고 이전 응답은 새 키를 지우지 않는다", () => {
    const pending = attempts();
    const old = pending.begin("save", { items: ["a", "b"] });
    const next = pending.begin("save", { items: ["b", "a"] });
    expect(next).not.toBe(old);
    pending.acknowledge("save", old);
    expect(pending.begin("save", { items: ["b", "a"] })).toBe(next);
  });
  it("성공한 요청을 정리하면 같은 내용의 의도적인 다음 추가는 새 키를 사용한다", () => {
    const pending = attempts();
    const body = { label: "점심", amount: 35000 };
    const first = pending.begin("expense", body);
    pending.acknowledge("expense", first);
    expect(pending.begin("expense", body)).not.toBe(first);
  });
  it("회원·화면 수명이 끝나면 보관 중인 본문과 키를 모두 제거한다", () => {
    const pending = attempts();
    const first = pending.begin("task", { content: "여권" });
    pending.clear();
    expect(pending.matches("task", { content: "여권" })).toBe(false);
    expect(pending.begin("task", { content: "여권" })).not.toBe(first);
  });
});
