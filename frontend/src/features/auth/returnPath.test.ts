import { describe, expect, it } from "vitest";
import { returnPath } from "./returnPath";

describe("로그인 후 내부 화면 이동", () => {
  const origin = "https://travel.example";
  it("대화방·초대처럼 정상적인 내부 주소의 검색 조건과 위치를 유지한다", () => {
    expect(returnPath("/messages?room=room-1#latest", origin)).toBe(
      "/messages?room=room-1#latest",
    );
    expect(returnPath("/explore?query=%ED%95%9C%EA%B0%95", origin)).toBe(
      "/explore?query=%ED%95%9C%EA%B0%95",
    );
    expect(returnPath("/", origin)).toBe("/");
  });
  it("외부·역슬래시·제어문자·잘못된 인코딩은 로그인 성공 후 기본 화면으로 이동한다", () => {
    for (const next of [
      null,
      "",
      "https://elsewhere.example",
      "//elsewhere.example",
      "/\\elsewhere.example",
      "/\n/elsewhere.example",
      "/%5celsewhere.example",
      "/%2felsewhere.example",
      "/%0aelsewhere.example",
      "/%zz",
    ]) {
      expect(returnPath(next, origin)).toBe("/trips");
    }
  });
});
