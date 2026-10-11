import type { Trip } from "../../shared/types";

/** 숙소는 지역별 한 곳·객실 한 개를 가정합니다. 마지막 여행일에는 숙박을 더하지 않습니다.
 * 저장된 일별 방문 지역을 사용하므로 여러 지역의 숙박비를 모든 날짜에 중복 곱하지 않습니다.
 * 날짜별 객실 재고나 인원별 객실 수를 조회한 금액이 아니라는 설명을 화면에 함께 제공합니다. */
export function lodgingEstimate(trip: Trip): number {
  const nights = Math.max(
    0,
    Math.round(
      (Date.parse(String(trip.endDate).slice(0, 10)) -
        Date.parse(String(trip.startDate).slice(0, 10))) /
        86400000,
    ),
  );
  let total = 0;
  for (let day = 0; day < nights; day++) {
    const region = trip.items.find((item) => item.dayIndex === day)?.region;
    total +=
      trip.lodgings.find((stay) => stay.region === region)?.nightlyEstimate ??
      0;
  }
  return total;
}
