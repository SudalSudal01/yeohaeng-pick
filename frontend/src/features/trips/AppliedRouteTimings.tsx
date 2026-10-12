import { useState } from "react";
import { Clock3 } from "lucide-react";
import { api } from "../../shared/api";
import { ErrorState } from "../../shared/ui";
import type { Trip } from "../../shared/types";
import { scheduleTime } from "./bookingScheduleTime";

/** 저장한 구간의 출처·시각을 다시 볼 수 있고, 더 이상 쓸 수 없는 조회값은 명시적으로 해제합니다. */
export default function AppliedRouteTimings({
  trip,
  day,
  refresh,
}: {
  trip: Trip;
  day: number;
  refresh: () => Promise<void>;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const timings = trip.routeTimings?.filter((t) => t.dayIndex === day) ?? [];
  if (!timings.length) return null;
  async function remove(itemId: string) {
    setBusy(true);
    setError(null);
    try {
      await api(`/trips/${trip.id}/transport/timing/remove`, {
        method: "POST",
        body: { itemId, version: trip.version },
      });
      await refresh();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="route-timing-saved" aria-label="반영한 이동 시간">
      <h3>
        <Clock3 size={18} />
        반영한 이동 시간
      </h3>
      <p className="small muted">
        조회 시점의 예상 시간이에요. 해제해도 현재 방문 시각은 유지돼요.
      </p>
      {timings.map((t) => (
        <article key={t.toItemId}>
          <strong>
            {t.fromKind === "LODGING"
              ? trip.lodgings.find((l) => l.placeId === t.fromId)?.name
              : trip.items.find((i) => i.id === t.fromId)?.placeName}{" "}
            → {t.overnight && `${t.overnight.lodgingName} → `}
            {trip.items.find((i) => i.id === t.toItemId)?.placeName}
          </strong>
          <p>
            {t.minutes}분 + {t.overnight ? "구간마다 " : ""}여유{" "}
            {t.bufferMinutes}분 ·{" "}
            {t.mode === "CAR"
              ? "자동차"
              : t.mode === "WALK"
                ? "도보"
                : "대중교통"}
            {t.originDeparture &&
              (t.fromKind === "LODGING"
                ? ` · 숙소 ${t.originDeparture} 출발`
                : ` · ${scheduleTime(t.originDeparture)} 이동 출발`)}
          </p>
          {t.overnight && (
            <div className="saved-overnight">
              <p>
                숙소 도착 {scheduleTime(t.overnight.arrival)}
                <br />
                숙소 출발 {scheduleTime(t.overnight.departure)}
              </p>
              <p className="small">
                최소 체류 {t.overnight.minimumStayMinutes / 60}시간 · 다음 날{" "}
                {t.overnight.notBefore} 이후 출발
              </p>
              {[t.overnight.inbound, t.overnight.outbound].map((leg, n) => (
                <p className="small muted" key={n}>
                  {n === 0 ? "숙소까지" : "첫 방문까지"} {leg.minutes}분 ·{" "}
                  {new Date(leg.queriedAt).toLocaleString("ko-KR")} ·{" "}
                  {leg.source === "KAKAO_MOBILITY"
                    ? "카카오모빌리티"
                    : "카카오맵"}
                </p>
              ))}
            </div>
          )}
          {!t.overnight && (
            <p className="small muted">
              {new Date(t.queriedAt).toLocaleString("ko-KR")} ·{" "}
              {t.source === "KAKAO_MOBILITY" ? "카카오모빌리티" : "카카오맵"}
            </p>
          )}
          <button
            className="text-button"
            disabled={busy}
            onClick={() => remove(t.toItemId)}
          >
            {t.overnight ? "숙소 연결 해제" : "시간 반영 해제"}
          </button>
        </article>
      ))}
      {!!error && <ErrorState error={error} />}
    </section>
  );
}
