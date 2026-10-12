import type { Favorite, Item, Trip } from "../../shared/types";

/** 서버의 교체 보호 규칙과 맞춰 예약 완료 또는 고정 방문을 비교 진입점에서 제외합니다. */
export function isVisitChangeProtected(
  item: Pick<Item, "fixed" | "bookingStatus">,
) {
  return (
    item.fixed ||
    item.bookingStatus === "USER_CONFIRMED" ||
    item.bookingStatus === "PARTNER_CONFIRMED"
  );
}

/**
 * 개인 찜 중 현재 여행의 방문 장소를 바꿀 때 검토할 수 있는 후보만 남깁니다.
 * 이 단계는 보관 당시 스냅샷을 화면에 안전하게 추리는 역할만 하며, 실제 적용 전에는 서버가 장소 ID를 다시 조회합니다.
 */
export function favoriteVisitCandidates(
  favorites: readonly Favorite[],
  trip: Pick<Trip, "items">,
  target: Pick<Item, "region">,
  rain: boolean,
) {
  const usedPlaceIds = new Set(trip.items.map((item) => item.placeId));
  const included = new Set<string>();

  return favorites.filter(({ place }) => {
    const available =
      place.region === target.region &&
      place.category !== "숙소" &&
      !usedPlaceIds.has(place.id) &&
      (!rain || place.indoor) &&
      !included.has(place.id);

    if (available) included.add(place.id);
    return available;
  });
}

/** 접은 목록에서도 이미 선택한 다섯 번째 이후 후보를 숨기지 않아 사용자가 현재 선택을 잃지 않게 합니다. */
export function visibleFavoriteVisitCandidates(
  candidates: readonly Favorite[],
  expanded: boolean,
  selectedPlaceId: string,
) {
  if (expanded) return [...candidates];

  const visible = candidates.slice(0, 4);
  const selected = candidates.find(({ place }) => place.id === selectedPlaceId);
  if (selected && !visible.some(({ place }) => place.id === selected.place.id))
    visible.push(selected);
  return visible;
}
