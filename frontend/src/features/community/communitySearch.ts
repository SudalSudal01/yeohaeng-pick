/** 검색 조건은 URL에 남깁니다. 공유·새로고침·상세에서 돌아오기에도 같은 목록을 다시 조회합니다. */
export const communityCategories = [
  "전체",
  "후기",
  "추천",
  "코스",
  "동행",
  "의견",
] as const;
export type CommunityCategory = (typeof communityCategories)[number];
export type RecruitmentStatus = "" | "OPEN" | "CLOSED" | "CANCELLED";
export type CommunityFilters = {
  q: string;
  region: string;
  category: CommunityCategory;
  status: RecruitmentStatus;
  page: number;
};

/** 직접 수정한 URL도 UI가 처리 가능한 조건으로 정리합니다. 서버는 같은 범위를 별도로 검사합니다. */
export function readCommunityFilters(
  params: URLSearchParams,
): CommunityFilters {
  const category =
    communityCategories.find((value) => value === params.get("category")) ??
    "전체";
  const rawStatus = params.get("status") ?? "";
  const status =
    category === "동행" && ["OPEN", "CLOSED", "CANCELLED"].includes(rawStatus)
      ? (rawStatus as RecruitmentStatus)
      : "";
  const rawPage = params.get("page") ?? "1";
  return {
    q: (params.get("q") ?? "").trim().slice(0, 100),
    region: (params.get("region") ?? "").trim().slice(0, 50),
    category,
    status,
    page: /^[1-9]\d*$/.test(rawPage) ? Math.min(Number(rawPage), 1000000) : 1,
  };
}

/** 기본값은 생략해 읽기 쉬운 주소를 만듭니다. 문자열을 직접 이어 붙이지 않아 특수문자도 보존됩니다. */
export function communitySearchParams(
  filters: CommunityFilters,
): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.q.trim()) params.set("q", filters.q.trim());
  if (filters.region.trim()) params.set("region", filters.region.trim());
  if (filters.category !== "전체") params.set("category", filters.category);
  if (filters.category === "동행" && filters.status)
    params.set("status", filters.status);
  if (filters.page > 1) params.set("page", String(filters.page));
  return params;
}

/** 상세 URL의 returnTo는 내부 커뮤니티 목록 주소만 허용합니다. 외부 주소나 다른 화면으로 이동시키지 않습니다. */
export function communityReturnTo(params: URLSearchParams): string {
  const value = params.get("returnTo") ?? "";
  if (value === "/community") return value;
  if (!value.startsWith("/community?")) return "/community";
  const query = communitySearchParams(
    readCommunityFilters(
      new URLSearchParams(value.slice("/community?".length)),
    ),
  ).toString();
  return "/community" + (query ? "?" + query : "");
}
