import type { Trip } from "../../shared/types";

/** 화면의 안내용 판정입니다. 실제 적용 시 서버에서도 연속 구간 조건을 검증합니다. */
export function routeTimingEligibility(trip: Trip, from: string, to: string) {
  const [fromKind, fromId] = from.split(":");
  const [toKind, toId] = to.split(":");
  const target = trip.items.find((i) => i.id === toId);
  if (toKind !== "ITEM" || !target)
    return "도착지를 여행의 방문 장소로 고르면 일정에 미치는 영향을 비교할 수 있어요.";
  const day = trip.items.filter((i) => i.dayIndex === target.dayIndex);
  const index = day.findIndex((i) => i.id === toId);
  if (fromKind === "ITEM" && index > 0 && day[index - 1].id === fromId)
    return "";
  if (
    fromKind === "LODGING" &&
    index === 0 &&
    trip.lodgings.some(
      (l) => l.placeId === fromId && l.region === target.region,
    )
  )
    return "";
  return "같은 날 연속 방문 사이 또는 같은 지역 숙소에서 첫 방문까지 선택하면 일정에 반영할 수 있어요.";
}
