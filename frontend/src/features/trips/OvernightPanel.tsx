import { useCallback, useState } from "react";
import { Link } from "react-router-dom";
import { BedDouble, ArrowUpRight, GitCompareArrows } from "lucide-react";
import { api } from "../../shared/api";
import { ErrorState } from "../../shared/ui";
import type { Trip, TransportResult } from "../../shared/types";
import { overnightConnection } from "./transportSchedule";
import OvernightModal from "./OvernightModal";

/** 두 구간을 명시적으로 조회한 뒤에만 비교 창을 엽니다. 숙소 선택·시간 입력만으로 외부 API를 호출하지 않습니다. */
export default function OvernightPanel({
  trip,
  day,
  refresh,
  onDayChange,
}: {
  trip: Trip;
  day: number;
  refresh: () => Promise<void>;
  onDayChange: (day: number) => void;
}) {
  const connection = overnightConnection(trip, day);
  const [lodging, setLodging] = useState(
    trip.lodgings.find((l) => l.region === connection?.from.region)?.placeId ??
      trip.lodgings[0]?.placeId ??
      "",
  );
  const [mode, setMode] = useState(trip.transport);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [results, setResults] = useState<
    [TransportResult, TransportResult] | null
  >(null);
  const close = useCallback(() => setResults(null), []);
  if (!connection) return null;
  async function query() {
    if (!connection) return;
    setBusy(true);
    setError(null);
    const base = `/trips/${trip.id}/transport/routes`;
    try {
      // 서로 독립적인 경로를 요청합니다. 어느 한쪽이라도 실패/경로 없음이면 불완전한 일정은 적용하지 않습니다.
      const pair = await Promise.all([
        api<TransportResult>(base, {
          method: "POST",
          body: {
            from: { kind: "ITEM", id: connection.from.id },
            to: { kind: "LODGING", id: lodging },
            mode,
          },
        }),
        api<TransportResult>(base, {
          method: "POST",
          body: {
            from: { kind: "LODGING", id: lodging },
            to: { kind: "ITEM", id: connection.to.id },
            mode,
          },
        }),
      ]);
      if (pair.some((r) => !r.options.length))
        throw new Error(
          "숙소 앞뒤 중 조회 가능한 경로가 없는 구간이 있어요. 이동 수단을 바꾸거나 공식 지도에서 확인해 주세요.",
        );
      setResults(pair);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  return (
    <section
      id="overnight-routes"
      className="overnight-panel"
      aria-label="숙소 경유와 휴식 연결"
    >
      <span className="eyebrow">A NIGHT FOR YOUR PACE</span>
      <h3>
        <BedDouble size={20} /> 하룻밤도 동선에 맞게
      </h3>
      <p>
        {connection.from.dayIndex + 1}일차 마지막 방문 → 숙소 →{" "}
        {connection.to.dayIndex + 1}일차 첫 방문을 연결해요.
      </p>
      {!trip.lodgings.length ? (
        <div className="overnight-empty">
          <p className="small muted">
            먼저 여행을 만들 때 숙소 후보를 선택해 주세요. 현재 여행에 저장한
            숙소가 있어야 앞뒤 경로를 비교할 수 있어요.
          </p>
          <Link
            className="button secondary"
            to={`/explore?region=${encodeURIComponent(connection.from.region)}&category=숙소`}
          >
            숙소 후보 살펴보기 <ArrowUpRight size={16} />
          </Link>
        </div>
      ) : (
        <>
          <div className="overnight-controls">
            <label>
              경유할 숙소
              <select
                value={lodging}
                disabled={busy}
                onChange={(e) => setLodging(e.target.value)}
              >
                {trip.lodgings.map((l) => (
                  <option key={l.placeId} value={l.placeId}>
                    {l.region} · {l.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              숙소 앞뒤 이동 수단
              <select
                value={mode}
                disabled={busy}
                onChange={(e) => setMode(e.target.value as Trip["transport"])}
              >
                <option value="TRANSIT">대중교통</option>
                <option value="CAR">자동차</option>
                <option value="WALK">도보</option>
              </select>
            </label>
          </div>
          <p className="small muted overnight-action-note">
            선택한 두 구간의 장소 좌표로 실제 경로를 조회해요. 숙소 체류와 다음
            날 출발 조건은 비교 창에서 조절해요. 객실·체크인·체크아웃 조건은
            예약처에서 확인해 주세요.
          </p>
          <button
            className="button secondary"
            disabled={busy || !lodging}
            onClick={query}
          >
            {busy ? "숙소 앞뒤 경로를 찾고 있어요…" : "숙소 앞뒤 경로 조회"}
            <GitCompareArrows size={17} />
          </button>
        </>
      )}
      {!!error && <ErrorState error={error} />}
      {results && (
        <OvernightModal
          trip={trip}
          pair={results}
          close={close}
          refresh={refresh}
          onDayChange={onDayChange}
        />
      )}
    </section>
  );
}
