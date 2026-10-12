import { describe, expect, it } from "vitest";
import {
  communityReturnTo,
  communitySearchParams,
  readCommunityFilters,
} from "./communitySearch";

describe("커뮤니티 검색 주소", () => {
  it("한글·특수문자·모집 상태·페이지가 공유 주소에서 그대로 복원된다", () => {
    const filters = {
      q: "카페 50%_할인! & 산책🚶",
      region: "부산 해운대",
      category: "동행" as const,
      status: "OPEN" as const,
      page: 9,
    };
    expect(readCommunityFilters(communitySearchParams(filters))).toEqual(
      filters,
    );
    const list = "/community?" + communitySearchParams(filters);
    expect(communityReturnTo(new URLSearchParams({ returnTo: list }))).toBe(
      list,
    );
  });
  it("기본 목록은 짧은 주소로 유지하고 일반 글에는 모집 상태를 남기지 않는다", () => {
    expect(
      communitySearchParams(
        readCommunityFilters(new URLSearchParams()),
      ).toString(),
    ).toBe("");
    expect(
      readCommunityFilters(
        new URLSearchParams("category=후기&status=OPEN&page=-1"),
      ),
    ).toEqual({ q: "", region: "", category: "후기", status: "", page: 1 });
  });
  it("잘못된 URL의 과도한 조건·페이지를 제한하고 존재하지 않는 종류는 전체로 돌린다", () => {
    const filters = readCommunityFilters(
      new URLSearchParams({
        q: "가".repeat(101),
        region: "나".repeat(51),
        category: "다른게시판",
        page: "99999999999",
      }),
    );
    expect(filters.q).toHaveLength(100);
    expect(filters.region).toHaveLength(50);
    expect(filters.category).toBe("전체");
    expect(filters.page).toBe(1000000);
    for (const page of ["0", "1.5", "NaN", "1e2", "-2"])
      expect(readCommunityFilters(new URLSearchParams({ page })).page).toBe(1);
  });
  it("상세의 돌아가기 주소는 커뮤니티 목록으로만 제한한다", () => {
    for (const returnTo of [
      "https://example.test",
      "//example.test",
      "/admin",
      "/community/other",
      "/communityish?x=1",
    ])
      expect(communityReturnTo(new URLSearchParams({ returnTo }))).toBe(
        "/community",
      );
    expect(
      communityReturnTo(
        new URLSearchParams({ returnTo: "/community?page=2&unknown=secret" }),
      ),
    ).toBe("/community?page=2");
  });
});
