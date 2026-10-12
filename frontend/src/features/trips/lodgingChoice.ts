import type { Place, Trip } from "../../shared/types";
import { lodgingBudget } from "./tripBudget";

/** 각 숙박일의 마지막 방문 지역만 제안합니다. 마지막 날만 방문하는 지역과 당일치기는 제외합니다. */
export function lodgingRegions(trip: Trip) {
  const nights = Math.round(
    (Date.parse(trip.endDate.slice(0, 10)) -
      Date.parse(trip.startDate.slice(0, 10))) /
      86400000,
  );
  const regions = new Set<string>();
  for (let day = 0; day < nights; day++) {
    const last = trip.items.filter((i) => i.dayIndex === day).at(-1);
    if (last && trip.regions.includes(last.region)) regions.add(last.region);
  }
  return [...regions];
}

/** 교체할 지역만 바꿔 참고 예산을 계산합니다. 이전 숙소의 직접 입력 조건·가격·예약 상태는 새 숙소로 넘기지 않습니다. */
export function lodgingChoiceBudget(trip: Trip, candidate: Place) {
  return lodgingBudget({
    ...trip,
    lodgings: [
      ...trip.lodgings.filter((h) => h.region !== candidate.region),
      {
        placeId: candidate.id,
        region: candidate.region,
        name: candidate.name,
        nightlyEstimate: candidate.cost,
        source: candidate.source,
        placeUrl: candidate.url,
        bookingStatus: "NONE",
      },
    ],
  });
}
