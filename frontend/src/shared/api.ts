/** 같은 출처 세션을 사용하며 변경 요청에는 서버가 발급한 CSRF 토큰을 추가합니다.
 * 비밀 키와 인증 토큰을 localStorage에 남기지 않습니다. 409는 화면의 비교 상태를 버리고 재조회해야 합니다. */
let csrf: string | undefined;
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
export async function api<T>(
  path: string,
  options: { method?: string; body?: unknown } = {},
): Promise<T> {
  const method = options.method ?? "GET";
  if (method !== "GET" && !csrf) {
    const response = await fetch("/api/auth/csrf", {
      credentials: "same-origin",
    });
    if (!response.ok)
      throw new ApiError(response.status, "서버 연결을 확인해 주세요.");
    csrf = (await response.json()).token;
  }
  let response: Response;
  try {
    response = await fetch("/api" + path, {
      method,
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json",
        ...(method !== "GET" && csrf ? { "X-XSRF-TOKEN": csrf } : {}),
      },
      ...(options.body !== undefined
        ? { body: JSON.stringify(options.body) }
        : {}),
    });
  } catch {
    throw new ApiError(
      0,
      "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.",
    );
  }
  if (!response.ok) {
    const error = await response
      .json()
      .catch(() => ({ message: "요청을 처리하지 못했어요." }));
    if (response.status === 403) csrf = undefined;
    throw new ApiError(response.status, error.message);
  }
  if (path === "/auth/logout" || path === "/auth/login") csrf = undefined;
  if (response.status === 204 || response.headers.get("content-length") === "0")
    return undefined as T;
  const text = await response.text();
  return text ? (JSON.parse(text) as T) : (undefined as T);
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
