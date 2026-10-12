import type { Place } from "../../shared/types";

export type LodgingSelection = {
  lodgingIds: string[];
  favoriteLodgingIds: string[];
};

/** 지역별 한 숙소를 유지하고, 일반 후보로 바꾸거나 해제할 때 이전 찜 선택 표시도 함께 정리합니다. */
export function chooseLodging(
  current: LodgingSelection,
  place: Place,
  known: Place[],
  favorite: boolean,
): LodgingSelection {
  const regions = new Map(known.map((p) => [p.id, p.region]));
  regions.set(place.id, place.region);
  const remaining = current.lodgingIds.filter(
    (id) => regions.get(id) !== place.region,
  );
  // 같은 숙소를 일반 후보로 골랐다가 재조회한 찜 카드에서 적용하면, 해제 대신 찜 선택으로 전환합니다.
  // 이미 찜 카드에서 선택한 숙소를 다시 누르거나 일반 후보의 선택 해제를 누르면 두 표시를 함께 지웁니다.
  const adding =
    !current.lodgingIds.includes(place.id) ||
    (favorite && !current.favoriteLodgingIds.includes(place.id));
  const lodgingIds = adding ? [...remaining, place.id] : remaining;
  const favoriteLodgingIds = current.favoriteLodgingIds.filter((id) =>
    remaining.includes(id),
  );
  if (adding && favorite) favoriteLodgingIds.push(place.id);
  return { lodgingIds, favoriteLodgingIds };
}

/** 사용할 수 없는 선택도 이름/좌표를 추측하지 않고 ID만 양쪽 목록에서 해제합니다. */
export function removeLodging(
  current: LodgingSelection,
  id: string,
): LodgingSelection {
  return {
    lodgingIds: current.lodgingIds.filter((x) => x !== id),
    favoriteLodgingIds: current.favoriteLodgingIds.filter((x) => x !== id),
  };
}
