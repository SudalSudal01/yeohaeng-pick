import { describe, expect, it } from "vitest";
import type { Trip } from "../../shared/types";
import {
  bookingConnections,
  bookingConnectionImpact,
  tripDayDate,
  overnightConnection,
} from "./transportSchedule";

/** 한국 현지 날짜 경계와 예약의 탑승/하차 여유를 실제 이동 시간과 중복 없이 비교하는지 검증합니다. */
const trip = {
  startDate: "2026-11-01",
  items: [
    {
      id: "before",
      dayIndex: 0,
      startTime: "20:00",
      duration: 60,
      placeName: "전날 방문",
    },
    {
      id: "after",
      dayIndex: 1,
      startTime: "09:00",
      duration: 60,
      placeName: "다음 날 방문",
    },
  ],
  externalBookings: [
    {
      id: "train",
      label: "야간 열차",
      status: "USER_CONFIRMED",
      departure: "2026-11-01T22:00",
      arrival: "2026-11-02T07:00",
      departurePlace: "서울역",
      arrivalPlace: "부산역",
      beforeMinutes: 30,
      afterMinutes: 20,
    },
  ],
} as Trip;
describe("역·터미널 앞뒤 연결", () => {
  it("첫날과 다음 날 모두 같은 숙소 연결 경계를 고른다", () => {
    expect(overnightConnection(trip, 0)?.from.id).toBe("before");
    expect(overnightConnection(trip, 1)?.to.id).toBe("after");
    expect(
      overnightConnection({ ...trip, items: [trip.items[0]] }, 0),
    ).toBeNull();
    expect(
      overnightConnection(
        { ...trip, items: [trip.items[0], { ...trip.items[1], dayIndex: 2 }] },
        0,
      ),
    ).toBeNull();
  });
  it("자정을 넘는 예약의 앞뒤 방문을 서로 다른 날짜에서 찾는다", () => {
    expect(bookingConnections(trip)).toEqual([
      {
        bookingId: "train",
        side: "DEPARTURE",
        query: "서울역",
        itemId: "before",
      },
      { bookingId: "train", side: "ARRIVAL", query: "부산역", itemId: "after" },
    ]);
  });
  it("탑승 전 여유를 확보한 정확한 경계는 0분, 긴 경로는 부족으로 표시한다", () => {
    const connection = bookingConnections(trip)[0];
    expect(bookingConnectionImpact(trip, connection, 30)).toMatchObject({
      earliest: "2026-11-01T21:30",
      deadline: "2026-11-01T21:30",
      slack: 0,
    });
    expect(bookingConnectionImpact(trip, connection, 45)?.slack).toBe(-15);
  });
  it("도착 후 여유와 조회 시간을 각각 한 번만 더한다", () => {
    expect(
      bookingConnectionImpact(trip, bookingConnections(trip)[1], 40),
    ).toMatchObject({
      earliest: "2026-11-02T08:00",
      deadline: "2026-11-02T09:00",
      slack: 60,
    });
  });
  it("미확정·취소·실패 예약은 연결 대상으로 안내하지 않는다", () => {
    for (const status of ["NONE", "NEEDS_CONFIRMATION", "FAILED", "CANCELLED"])
      expect(
        bookingConnections({
          ...trip,
          externalBookings: trip.externalBookings.map((b) => ({
            ...b,
            status,
          })),
        }),
      ).toEqual([]);
  });
  it("방문이 없거나 조회 시간이 잘못되면 여유를 만들어내지 않는다", () => {
    const empty = { ...trip, items: [] };
    expect(bookingConnections(empty)[0].itemId).toBeNull();
    expect(
      bookingConnectionImpact(empty, bookingConnections(empty)[0], 20),
    ).toBeNull();
    expect(
      bookingConnectionImpact(trip, bookingConnections(trip)[0], -1),
    ).toBeNull();
  });
  it("월말·연말의 날짜를 시간대에 영향 없이 넘긴다", () => {
    expect(tripDayDate("2026-12-31", 1)).toBe("2027-01-01");
    expect(tripDayDate("2028-02-28", 1)).toBe("2028-02-29");
  });
});
