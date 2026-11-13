type Attempt = { requestId: string; fingerprint: string };

/** 전송 본문의 객체 키 순서는 의미가 없고 배열 순서는 의미가 있습니다.
 * 이 문자열은 현재 화면의 메모리에서만 비교하며 브라우저 저장소·로그로 내보내지 않습니다. */
function fingerprint(body: unknown) {
  return JSON.stringify(body, (_key, value) =>
    value && typeof value === "object" && !Array.isArray(value)
      ? Object.fromEntries(
          Object.keys(value)
            .sort()
            .map((key) => [key, value[key]]),
        )
      : value,
  );
}

/** 서버에는 저장됐지만 응답만 유실된 쓰기를 같은 UUID로 재시도합니다.
 * scope는 회원·대상·작업을 구분합니다. 성공 후 같은 내용을 새로 추가하는 것은 별도 요청입니다. */
export class RequestAttempts {
  private readonly pending = new Map<string, Attempt>();
  constructor(
    private readonly createId: () => string = () => crypto.randomUUID(),
  ) {}

  begin(scope: string, body: unknown) {
    const value = fingerprint(body);
    const previous = this.pending.get(scope);
    if (previous?.fingerprint === value) return previous.requestId;
    const requestId = this.createId();
    this.pending.set(scope, { requestId, fingerprint: value });
    return requestId;
  }

  matches(scope: string, body: unknown) {
    return this.pending.get(scope)?.fingerprint === fingerprint(body);
  }

  acknowledge(scope: string, requestId: string) {
    // 앞선 응답이 늦게 도착해도 같은 scope에서 새로 시작한 요청의 키를 지우지 않습니다.
    if (this.pending.get(scope)?.requestId === requestId)
      this.pending.delete(scope);
  }

  clear() {
    this.pending.clear();
  }
}
