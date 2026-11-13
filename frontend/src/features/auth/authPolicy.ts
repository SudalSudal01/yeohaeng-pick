/** BCrypt가 처리하는 UTF-8 72바이트 경계를 서버와 같은 기준으로 안내합니다. 최종 검증은 서버가 수행합니다. */
export function passwordError(value: string) {
  if (value.length < 10) return "비밀번호는 10자 이상으로 입력해 주세요.";
  if (new TextEncoder().encode(value).length > 72)
    return "비밀번호는 UTF-8 기준 72바이트 이하로 입력해 주세요.";
  return "";
}

/** 인증/재설정은 이미 발급한 링크를 소비하므로 SMTP의 현재 발송 가능 여부와 별개입니다. */
export const needsMailConnection = (mode: "forgot" | "verify" | "reset") =>
  mode === "forgot";
export const validAccountToken = (token: string | null) =>
  Boolean(token && /^[0-9a-f]{64}$/.test(token));
