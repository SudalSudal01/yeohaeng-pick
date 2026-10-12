import { describe, expect, it } from "vitest";
import type { Favorite, Item, Place, Trip } from "../../shared/types";
import {
  favoriteVisitCandidates,
  isVisitChangeProtected,
  visibleFavoriteVisitCandidates,
} from "./visitComparisonCandidates";

function favorite(
  id: string,
  region = "서울",
  category = "관광",
  indoor = true,
): Favorite {
  return {
    savedAt: "2026-10-01T12:00:00Z",
    place: { id, region, category, indoor, name: id } as Place,
  };
}

describe("방문 장소 비교용 찜 후보", () => {
  it("고정 여부가 누락된 오래된 데이터라도 예약 완료 방문은 교체 진입을 막는다", () => {
    expect(
      isVisitChangeProtected({ fixed: false, bookingStatus: "USER_CONFIRMED" }),
    ).toBe(true);
    expect(
      isVisitChangeProtected({
        fixed: false,
        bookingStatus: "PARTNER_CONFIRMED",
      }),
    ).toBe(true);
    expect(isVisitChangeProtected({ fixed: true, bookingStatus: "NONE" })).toBe(
      true,
    );
    expect(
      isVisitChangeProtected({
        fixed: false,
        bookingStatus: "NEEDS_CONFIRMATION",
      }),
    ).toBe(false);
  });

  it("같은 지역의 비숙소·여행 전체 미사용 장소만 원래 순서로 남긴다", () => {
    const favorites = [
      favorite("available"),
      favorite("used-current"),
      favorite("used-other"),
      favorite("lodging", "서울", "숙소"),
      favorite("other-region", "부산"),
      favorite("available"),
    ];
    const before = favorites.map(({ place }) => place.id);
    const trip = {
      items: [{ placeId: "used-current" }, { placeId: "used-other" }],
    } as Trip;

    expect(
      favoriteVisitCandidates(
        favorites,
        trip,
        { region: "서울" } as Item,
        false,
      ).map(({ place }) => place.id),
    ).toEqual(["available"]);
    expect(favorites.map(({ place }) => place.id)).toEqual(before);
  });

  it("비 오는 날에는 보관 당시 실내였던 찜만 보여 준다", () => {
    const favorites = [
      favorite("indoor", "서울", "전시", true),
      favorite("outdoor", "서울", "자연", false),
    ];
    const trip = { items: [] } as unknown as Trip;

    expect(
      favoriteVisitCandidates(
        favorites,
        trip,
        { region: "서울" } as Item,
        true,
      ).map(({ place }) => place.id),
    ).toEqual(["indoor"]);
    expect(
      favoriteVisitCandidates(
        favorites,
        trip,
        { region: "서울" } as Item,
        false,
      ).map(({ place }) => place.id),
    ).toEqual(["indoor", "outdoor"]);
  });

  it("접어도 다섯 번째 이후에 선택한 후보는 첫 네 곳과 함께 유지한다", () => {
    const candidates = ["one", "two", "three", "four", "five"].map((id) =>
      favorite(id),
    );

    expect(
      visibleFavoriteVisitCandidates(candidates, false, "five").map(
        ({ place }) => place.id,
      ),
    ).toEqual(["one", "two", "three", "four", "five"]);
    expect(
      visibleFavoriteVisitCandidates(candidates, true, "").map(
        ({ place }) => place.id,
      ),
    ).toEqual(["one", "two", "three", "four", "five"]);
  });
});
