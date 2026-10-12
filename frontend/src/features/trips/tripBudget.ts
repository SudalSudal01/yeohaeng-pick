import type { Trip } from "../../shared/types";

/** 직접 입력한 0원도 유효합니다. 미입력일 때만 추천 참고값을 쓰고 실제 미확인 비용을 따로 셉니다. */
export function activityBudget(trip: Trip) {
  let total = 0,
    unpriced = 0,
    entered = 0;
  for (const i of trip.items) {
    if (i.visitCost != null) {
      total += i.visitCost * trip.partySize;
      entered += i.visitCost * trip.partySize;
    } else {
      total += i.cost * trip.partySize;
      if (i.source === "KAKAO" && i.cost === 0) unpriced++;
    }
  }
  return { total, entered, unpriced };
}

/** 직접 입력 합계는 객실 수·숙박 수를 다시 곱하지 않습니다. 비어 있는 실제 가격은 0원으로 확정하지 않습니다.
 * 예시 숙소만 기간·객실 수로 계산하고, 조건 미정의 이전 여행은 일별 지역의 1객실 예시를 유지합니다. */
export function lodgingBudget(trip: Trip) {
  const nights = Math.max(
    0,
    Math.round(
      (Date.parse(String(trip.endDate).slice(0, 10)) -
        Date.parse(String(trip.startDate).slice(0, 10))) /
        86400000,
    ),
  );
  let entered = 0,
    example = 0,
    unpriced = 0;
  for (const stay of trip.lodgings) {
    if (stay.totalAmount !== null && stay.totalAmount !== undefined) {
      entered += stay.totalAmount;
      continue;
    }
    if (stay.source !== "SAMPLE") {
      unpriced++;
      continue;
    }
    if (stay.checkInDate && stay.checkOutDate) {
      example +=
        stay.nightlyEstimate *
        Math.max(
          0,
          Math.round(
            (Date.parse(stay.checkOutDate) - Date.parse(stay.checkInDate)) /
              86400000,
          ),
        ) *
        (stay.roomCount ?? 1);
    } else {
      for (let day = 0; day < nights; day++) {
        if (trip.items.find((i) => i.dayIndex === day)?.region === stay.region)
          example += stay.nightlyEstimate;
      }
    }
  }
  return { total: entered + example, entered, example, unpriced };
}
