import { describe, expect, it } from "vitest";
import type { Place } from "../../shared/types";
import { chooseLodging, removeLodging } from "./lodgingSelection";

/** 실제 사용 흐름의 지역별 선택/찜 표시 불변식을 검사합니다. 가격·예약 상태를 선택 함수에서 만들지 않습니다. */
const hotel = (id: string, region: string): Place => ({
  id,
  region,
  name: id,
  category: "숙소",
  latitude: 37,
  longitude: 127,
  duration: 0,
  cost: 0,
  indoor: true,
  source: "KAKAO",
  url: "https://place.map.kakao.com/1",
});
const seoul = hotel("seoul", "서울");
const otherSeoul = hotel("other-seoul", "서울");
const busan = hotel("busan", "부산");
const known = [seoul, otherSeoul, busan];

describe("숙소 선택", () => {
  it("같은 일반 숙소를 재조회한 찜 카드에서 적용하면 해제하지 않고 찜 선택으로 전환한다", () => {
    expect(
      chooseLodging(
        { lodgingIds: [seoul.id], favoriteLodgingIds: [] },
        seoul,
        known,
        true,
      ),
    ).toEqual({ lodgingIds: [seoul.id], favoriteLodgingIds: [seoul.id] });
  });
  it("같은 지역 숙소만 교체하고 다른 지역의 찜 선택은 유지한다", () => {
    const before = {
      lodgingIds: [seoul.id, busan.id],
      favoriteLodgingIds: [seoul.id, busan.id],
    };
    expect(chooseLodging(before, otherSeoul, known, true)).toEqual({
      lodgingIds: [busan.id, otherSeoul.id],
      favoriteLodgingIds: [busan.id, otherSeoul.id],
    });
    expect(before.lodgingIds).toEqual([seoul.id, busan.id]);
  });
  it("일반 후보로 교체하면 이전 숙소의 개인 찜 표시를 제거한다", () => {
    expect(
      chooseLodging(
        { lodgingIds: [seoul.id], favoriteLodgingIds: [seoul.id] },
        otherSeoul,
        known,
        false,
      ),
    ).toEqual({ lodgingIds: [otherSeoul.id], favoriteLodgingIds: [] });
  });
  it("현재 선택을 다시 누르면 숙소와 개인 찜 표시를 함께 해제한다", () => {
    expect(
      chooseLodging(
        { lodgingIds: [seoul.id, busan.id], favoriteLodgingIds: [seoul.id] },
        seoul,
        known,
        true,
      ),
    ).toEqual({ lodgingIds: [busan.id], favoriteLodgingIds: [] });
  });
  it("일반 검색에 없는 재조회 숙소도 같은 지역의 기존 선택을 교체한다", () => {
    expect(
      chooseLodging(
        { lodgingIds: [seoul.id], favoriteLodgingIds: [] },
        otherSeoul,
        [seoul],
        true,
      ),
    ).toEqual({
      lodgingIds: [otherSeoul.id],
      favoriteLodgingIds: [otherSeoul.id],
    });
  });
  it("사라진 후보의 지역을 추측하지 않고 명시적인 해제까지 보존한다", () => {
    const selection = chooseLodging(
      { lodgingIds: ["missing"], favoriteLodgingIds: ["missing"] },
      seoul,
      known,
      false,
    );
    expect(selection).toEqual({
      lodgingIds: ["missing", seoul.id],
      favoriteLodgingIds: ["missing"],
    });
    expect(removeLodging(selection, "missing")).toEqual({
      lodgingIds: [seoul.id],
      favoriteLodgingIds: [],
    });
    expect(selection.lodgingIds).toEqual(["missing", seoul.id]);
  });
});
