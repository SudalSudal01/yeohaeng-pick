import { ArrowRight, TrainFront } from "lucide-react";
import type { Trip } from "../../shared/types";
import {
  bookingConnections,
  type BookingConnection,
} from "./transportSchedule";
import { scheduleTime } from "./bookingScheduleTime";

/** 예약 장소명은 검색어로만 전달합니다. 좌표 선택·경로 조회는 사용자가 확인한 뒤 각각 실행합니다. */
export default function BookingRouteGuide({
  trip,
  busy,
  onPrepare,
}: {
  trip: Trip;
  busy: boolean;
  onPrepare: (connection: BookingConnection) => void;
}) {
  const connections = bookingConnections(trip);
  if (!connections.length) return null;
  return (
    <details className="booking-route-guide">
      <summary>
        <TrainFront size={17} /> 예약한 역·터미널 앞뒤 이동
      </summary>
      <p className="small muted">
        직접 예약 완료로 기록한 교통편이에요. 앞뒤 방문을 연결하고, 장소를
        검색해 실제 위치를 선택해 주세요.
      </p>
      {trip.externalBookings
        .filter((b) => connections.some((c) => c.bookingId === b.id))
        .map((booking) => (
          <article key={booking.id}>
            <h3>{booking.label}</h3>
            <p className="small muted">
              {scheduleTime(booking.departure)} 출발
              {booking.arrival && ` · ${scheduleTime(booking.arrival)} 도착`}
            </p>
            <div className="booking-connection-options">
              {connections
                .filter((c) => c.bookingId === booking.id)
                .map((connection) => {
                  const item = trip.items.find(
                    (i) => i.id === connection.itemId,
                  );
                  return (
                    <div key={connection.side}>
                      <strong>
                        {connection.side === "DEPARTURE"
                          ? "출발역까지"
                          : "도착역에서"}
                      </strong>
                      <p>
                        {connection.side === "DEPARTURE" ? (
                          <>
                            {item?.placeName ?? "출발 방문 직접 선택"}
                            <ArrowRight size={14} />
                            {connection.query}
                          </>
                        ) : (
                          <>
                            {connection.query}
                            <ArrowRight size={14} />
                            {item?.placeName ?? "도착 방문 직접 선택"}
                          </>
                        )}
                      </p>
                      <p className="small muted">
                        {item
                          ? `${item.dayIndex + 1}일차 ${item.startTime} 방문 기준`
                          : "예약 앞뒤로 연결할 방문이 없어요. 경로 검색에서 직접 선택해 주세요."}
                      </p>
                      <button
                        className="button secondary full"
                        disabled={busy}
                        aria-label={`${booking.label} ${connection.side === "DEPARTURE" ? "출발역까지" : "도착역에서"} 검색 준비`}
                        onClick={() => onPrepare(connection)}
                      >
                        이 구간 검색 준비
                      </button>
                    </div>
                  );
                })}
            </div>
          </article>
        ))}
    </details>
  );
}
