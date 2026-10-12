import type { ExternalBooking, Item, Trip } from "../../shared/types";

/** 한국 현지 달력 날짜를 UTC 달력 연산으로 더합니다. 브라우저 시간대·서머타임에 따라 날짜가 바뀌지 않습니다. */
export function tripDayDate(start: string, day: number) {
  const date = new Date(start.slice(0, 10) + "T00:00:00Z");
  date.setUTCDate(date.getUTCDate() + day);
  return date.toISOString().slice(0, 10);
}

/** 오프셋 없는 한국 현지 일시를 분 단위로 비교하기 위한 값입니다. 실제 UTC 시각으로 표시하지 않습니다. */
function localMinute(value: string) {
  return Date.parse(value.slice(0, 16) + "Z") / 60_000;
}
function itemMinute(trip: Trip, item: Item) {
  return localMinute(
    tripDayDate(trip.startDate, item.dayIndex) + "T" + item.startTime,
  );
}
export type BookingConnection = {
  bookingId: string;
  side: "DEPARTURE" | "ARRIVAL";
  query: string;
  itemId: string | null;
};

/** 예약 완료로 직접 기록한 교통편의 앞뒤 방문을 찾습니다. 장소명만으로 역 좌표를 확정하지 않습니다. */
export function bookingConnections(trip: Trip): BookingConnection[] {
  const visits = [...trip.items].sort(
    (a, b) => itemMinute(trip, a) - itemMinute(trip, b),
  );
  return trip.externalBookings
    .filter((b) => b.status === "USER_CONFIRMED")
    .flatMap((b) => {
      const connections: BookingConnection[] = [];
      if (b.departurePlace.trim()) {
        const leaveBy = localMinute(b.departure) - b.beforeMinutes;
        const previous = visits
          .filter((i) => itemMinute(trip, i) + i.duration <= leaveBy)
          .at(-1);
        connections.push({
          bookingId: b.id,
          side: "DEPARTURE",
          query: b.departurePlace,
          itemId: previous?.id ?? null,
        });
      }
      if (b.arrival && b.arrivalPlace.trim()) {
        const readyAt = localMinute(b.arrival) + b.afterMinutes;
        const next = visits.find((i) => itemMinute(trip, i) >= readyAt);
        connections.push({
          bookingId: b.id,
          side: "ARRIVAL",
          query: b.arrivalPlace,
          itemId: next?.id ?? null,
        });
      }
      return connections;
    });
}

/** 경로 조회 분과 기록한 준비/하차 여유를 분리해 비교합니다. 이 결과로 예약·일정은 수정하지 않습니다. */
export function bookingConnectionImpact(
  trip: Trip,
  connection: BookingConnection,
  minutes: number,
) {
  const booking: ExternalBooking | undefined = trip.externalBookings.find(
    (b) => b.id === connection.bookingId && b.status === "USER_CONFIRMED",
  );
  const item = trip.items.find((i) => i.id === connection.itemId);
  if (!booking || !item || !Number.isFinite(minutes) || minutes < 0)
    return null;
  let earliest: number;
  let deadline: number;
  if (connection.side === "DEPARTURE") {
    earliest = itemMinute(trip, item) + item.duration + minutes;
    deadline = localMinute(booking.departure) - booking.beforeMinutes;
  } else {
    if (!booking.arrival) return null;
    earliest = localMinute(booking.arrival) + booking.afterMinutes + minutes;
    deadline = itemMinute(trip, item);
  }
  const format = (value: number) =>
    new Date(value * 60_000).toISOString().slice(0, 16);
  return {
    earliest: format(earliest),
    deadline: format(deadline),
    slack: deadline - earliest,
    itemName: item.placeName,
    bookingLabel: booking.label,
  };
}
