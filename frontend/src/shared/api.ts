/** 같은 출처 세션을 사용하며 변경 요청에는 서버가 발급한 CSRF 토큰을 추가합니다.
 * 비밀 키와 인증 토큰을 localStorage에 남기지 않습니다. 409는 화면의 비교 상태를 버리고 재조회해야 합니다. */
let csrf: string | undefined;
let csrfRequest: Promise<string> | undefined;
let sessionRevision = 0;
// undefined는 App이 아직 회원 문맥을 확정하지 않은 초기/저수준 호출입니다.
// null은 문맥을 종료한 상태이며 회원 ID는 App의 layout effect가 화면 커밋과 함께 확정합니다.
let expectedMember: string | null | undefined;
export function commitExpectedMember(member: string | null) {
  expectedMember = member;
}
export const SESSION_EXPIRED_EVENT = "yeohaeng:session-expired";
/** 쿠키가 바뀔 수 있는 인증 전환을 시작할 때도 옛 요청의 권한 문맥을 끝냅니다. */
export function invalidateSessionRequests() {
  sessionRevision++;
  csrf = undefined;
  expectedMember = null;
}
/** 로그인 시도나 익명 상태 확인의 401은 정상 흐름입니다. 보호 API의 인증 종료만 공통 경계에 알립니다. */
export function announceSessionExpired() {
  invalidateSessionRequests();
  if (typeof window !== "undefined")
    window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT));
}
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}
/** 비공개 자료가 더 이상 허용되지 않으면 캐시된 내용 대신 접근 종료 화면을 표시합니다. */
export const accessEnded = (error: unknown) =>
  error instanceof ApiError && (error.status === 403 || error.status === 404);

/** 장시간 응답이 없는 요청도 입력 화면을 영구 잠그지 않습니다. 변경 요청은 자동 재전송하지 않습니다. */
async function requestFetch(
  url: string,
  options: RequestInit = {},
  timeout = 30000,
  onHeaders?: (response: Response) => void,
) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeout);
  try {
    const response = await fetch(url, {
      ...options,
      signal: controller.signal,
    });
    onHeaders?.(response);
    // 헤더만 도착하고 본문이 멈추는 경우도 같은 제한 안에 둡니다. 소비가 끝난 본문은 한 번만 파싱합니다.
    const text = await response.text();
    return { response, text };
  } catch (error) {
    if (error instanceof ApiError) {
      controller.abort();
      throw error;
    }
    throw new ApiError(
      0,
      controller.signal.aborted
        ? "응답이 늦어지고 있어요. 처리 결과를 확인한 뒤 다시 시도해 주세요."
        : "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.",
    );
  } finally {
    clearTimeout(timer);
  }
}

/** 여러 변경 요청이 동시에 시작해도 CSRF 쿠키를 서로 다른 발급 응답으로 덮어쓰지 않게 한 번만 발급합니다. */
async function csrfToken() {
  if (csrf) return csrf;
  if (!csrfRequest) {
    const started = sessionRevision;
    csrfRequest = (async () => {
      const { response, text } = await requestFetch(
        "/api/auth/csrf",
        { credentials: "same-origin" },
        10000,
        (headers) => {
          if (headers.status !== 401) return;
          if (started !== sessionRevision)
            throw new ApiError(
              409,
              "로그인 상태가 바뀌었어요. 현재 화면을 다시 확인해 주세요.",
            );
          announceSessionExpired();
          throw new ApiError(401, "로그인 후 다시 시도해 주세요.");
        },
      );
      if (response.status === 401 && started === sessionRevision)
        announceSessionExpired();
      if (!response.ok)
        throw new ApiError(
          response.status,
          "보안 정보를 확인하지 못했어요. 새로고침 후 다시 시도해 주세요.",
        );
      let body: { token?: unknown } | null = null;
      try {
        body = JSON.parse(text);
      } catch {
        /* 아래에서 사용자용 형식 오류로 처리합니다. */
      }
      if (typeof body?.token !== "string" || !body.token)
        throw new ApiError(502, "서버의 보안 응답을 확인하지 못했어요.");
      if (started === sessionRevision) csrf = body.token;
      return body.token as string;
    })().finally(() => {
      csrfRequest = undefined;
    });
  }
  return csrfRequest;
}

/** 공개 읽기와 인증 변경을 제외한 응답은 시작한 로그인 상태에서만 소비합니다. */
function privateRequest(path: string, method: string) {
  const route = path.split("?")[0];
  if (
    [
      "/auth/login",
      "/auth/logout",
      "/auth/register",
      "/auth/reset",
      "/auth/reset/send",
      "/auth/verify",
      "/auth/csrf",
      "/auth/account",
    ].includes(route)
  )
    return false;
  if (
    method === "GET" &&
    (route === "/config" ||
      route.startsWith("/catalog/") ||
      route.startsWith("/share/") ||
      (route.startsWith("/community/") &&
        !route.startsWith("/community/trash")))
  )
    return false;
  return true;
}
export async function api<T>(
  path: string,
  options: { method?: string; body?: unknown; expectedMemberId?: string } = {},
): Promise<T> {
  const method = options.method ?? "GET";
  const started = sessionRevision;
  const privateCall = privateRequest(path, method);
  const route = path.split("?")[0];
  // me는 쿠키의 실제 계정을 다시 확인하는 조회입니다. 다른 비공개 요청은 화면에 커밋된 계정을 캡처합니다.
  // 탈퇴처럼 문맥을 먼저 종료해야 하는 인증 변경만 호출자가 미리 캡처한 주체를 명시할 수 있습니다.
  const memberContext =
    (privateCall && route !== "/auth/me") ||
    options.expectedMemberId !== undefined ||
    route === "/auth/account";
  const member = options.expectedMemberId ?? expectedMember;
  if (memberContext && method !== "GET" && member === null)
    throw new ApiError(401, "로그인 상태를 확인한 뒤 다시 시도해 주세요.");
  const token = method !== "GET" ? await csrfToken() : undefined;
  const checkSession = () => {
    if (privateCall && started !== sessionRevision)
      throw new ApiError(
        409,
        "로그인 상태가 바뀌었어요. 현재 화면을 다시 확인해 주세요.",
      );
  };
  // CSRF를 기다리는 사이 계정이 바뀌면 이전 계정의 입력을 새 세션으로 전송하지 않습니다.
  checkSession();
  let response: Response;
  let text: string;
  try {
    const received = await requestFetch(
      "/api" + path,
      {
        method,
        credentials: "same-origin",
        headers: {
          "Content-Type": "application/json",
          ...(token ? { "X-XSRF-TOKEN": token } : {}),
          ...(memberContext && member ? { "X-Travel-Member": member } : {}),
        },
        ...(options.body !== undefined
          ? { body: JSON.stringify(options.body) }
          : {}),
      },
      path.startsWith("/recommendations") || path.includes("/transport")
        ? 90000
        : 30000,
      (headers) => {
        checkSession();
        if (
          headers.status === 409 &&
          headers.headers.get("X-Travel-Account-Changed") === "true"
        ) {
          // 이전 회원의 DOM에서 새 쿠키로 전송된 요청을 서버가 거절했습니다. 다른 계정의 정보를 오류에 싣지 않습니다.
          // 새 로그인 뒤에 늦게 도착한 거절은 현재 회원을 종료시키지 않습니다.
          if (started !== sessionRevision)
            throw new ApiError(
              409,
              "로그인 상태가 바뀌었어요. 현재 화면을 다시 확인해 주세요.",
            );
          announceSessionExpired();
          throw new ApiError(
            401,
            "다른 창에서 로그인 상태가 바뀌었어요. 현재 계정을 다시 확인해 주세요.",
          );
        }
        if (
          headers.status !== 401 ||
          ["/auth/login", "/auth/register"].includes(path.split("?")[0])
        )
          return;
        // 401은 오류 본문이 멈춰도 헤더에서 즉시 처리합니다. me의 익명 확인은 useAuth가 현재 계정 유무로 구분합니다.
        if (path.split("?")[0] !== "/auth/me" && started === sessionRevision)
          announceSessionExpired();
        throw new ApiError(401, "로그인 후 이용해 주세요.");
      },
    );
    response = received.response;
    text = received.text;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError(
      0,
      "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.",
    );
  }
  // 옛 GET/변경 응답의 401도 새 로그인 상태를 종료시키지 않도록 상태 처리보다 먼저 검사합니다.
  checkSession();
  if (!response.ok) {
    if (
      response.status === 401 &&
      started === sessionRevision &&
      !["/auth/login", "/auth/register", "/auth/me"].includes(
        path.split("?")[0],
      )
    ) {
      csrf = undefined;
      announceSessionExpired();
    }
    let error: { message?: unknown } | null = null;
    try {
      error = JSON.parse(text);
    } catch {
      /* 서버/프록시의 HTML 오류도 같은 안내로 처리합니다. */
    }
    if (response.status === 403) csrf = undefined;
    throw new ApiError(
      response.status,
      typeof error?.message === "string" && error.message.trim()
        ? error.message
        : "요청을 처리하지 못했어요.",
    );
  }
  if (
    path === "/auth/logout" ||
    path === "/auth/login" ||
    path === "/auth/account" ||
    path === "/auth/reset"
  ) {
    csrf = undefined;
    if (path !== "/auth/reset") sessionRevision++;
  }
  // 재설정은 현재 세션도 서버에서 종료합니다. 공개 계정 화면에 남아 있어도 옛 비공개 캐시는 즉시 정리합니다.
  if (path === "/auth/reset" && method === "POST") announceSessionExpired();
  if (response.status === 204 || response.headers.get("content-length") === "0")
    return undefined as T;
  checkSession();
  if (!text) return undefined as T;
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new ApiError(
      502,
      "서버 응답을 읽지 못했어요. 잠시 후 다시 확인해 주세요.",
    );
  }
}
export const money = (amount: number) =>
  new Intl.NumberFormat("ko-KR").format(amount) + "원";
// 외부 검색의 미제공 요금을 무료로 표시하지 않습니다. 식비·카페 비용도 실제 결제 금액이 아닌 추정입니다.
export const activityPrice = (place: {
  source: string;
  cost: number;
  visitCost?: number | null;
}) =>
  place.visitCost != null
    ? money(place.visitCost) + " / 인 · 직접 입력"
    : place.source === "KAKAO" && place.cost === 0
      ? "요금 확인 필요"
      : money(place.cost) + " / 인 추정";
export const dateLabel = (value: string) =>
  new Date(value + "T00:00:00").toLocaleDateString("ko-KR", {
    month: "long",
    day: "numeric",
  });
/** 모집 날짜는 달력 날짜입니다. 서버가 시각을 붙여 보내도 시간대 변환 없이 날짜만 읽기 좋게 표시합니다. */
export function calendarDateLabel(value?: string) {
  const date = value?.match(/^(\d{4})-(\d{2})-(\d{2})/);
  return date
    ? `${date[1]}년 ${Number(date[2])}월 ${Number(date[3])}일`
    : "날짜 확인 필요";
}
export const bookingLabels: Record<string, string> = {
  NONE: "예약 전",
  NEEDS_CONFIRMATION: "결과 확인 필요",
  USER_CONFIRMED: "예약 완료 · 직접 입력",
  PARTNER_CONFIRMED: "예약 완료 · 제휴 확인",
  FAILED: "예약 실패",
  CANCELLED: "취소됨",
};
