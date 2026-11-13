import { expect, it } from "vitest";
import {
  needsMailConnection,
  passwordError,
  validAccountToken,
} from "./authPolicy";

it("ASCII·한글·이모지 비밀번호의 실제 UTF-8 길이를 검사한다", () => {
  expect(passwordError("short")).not.toBe("");
  for (const valid of [
    "a".repeat(10),
    "a".repeat(72),
    "가".repeat(24),
    "🧭".repeat(18),
  ])
    expect(passwordError(valid)).toBe("");
  for (const tooLong of ["a".repeat(73), "가".repeat(25), "🧭".repeat(19)])
    expect(passwordError(tooLong)).toContain("72바이트");
});
it("발송 중단 중에도 이미 받은 올바른 인증/재설정 링크는 사용할 수 있다", () => {
  expect(needsMailConnection("forgot")).toBe(true);
  expect(needsMailConnection("verify")).toBe(false);
  expect(needsMailConnection("reset")).toBe(false);
  expect(validAccountToken("a".repeat(64))).toBe(true);
  for (const invalid of [
    null,
    "",
    "a".repeat(63),
    "A".repeat(64),
    "z".repeat(64),
  ])
    expect(validAccountToken(invalid)).toBe(false);
});
