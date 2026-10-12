import { useCallback, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  ArrowRight,
  Clock3,
  GitCompareArrows,
  Loader2,
  LockKeyhole,
  TrainFront,
  TriangleAlert,
} from "lucide-react";
import { api } from "../../shared/api";
import { Modal, ErrorState, Loading } from "../../shared/ui";
import type { Trip, SchedulePlan } from "../../shared/types";
import { scheduleTime } from "./bookingScheduleTime";

/** 예약을 기록한 직후 일정 화면에서도 충돌과 다음 행동을 찾을 수 있도록 안내합니다. */
export default function BookingSchedulePanel({
  trip,
  refresh,
  onBookings,
}: {
  trip: Trip;
  refresh: () => Promise<void>;
  onBookings: () => void;
}) {
  const [comparing, setComparing] = useState<Trip | null>(null);
  const close = useCallback(() => setComparing(null), []);
  const report = trip.schedule;
  if (!report || (!report.windows.length && !report.unlinkedCount)) return null;
  return (
    <section className="schedule-panel" aria-label="교통 예약과 일정 확인">
      <div className="schedule-heading">
        <div className="schedule-heading-label">
          <span className="schedule-icon">
            <TrainFront size={21} />
          </span>
          <div>
            <h2>예약 시간도 여행의 일부니까</h2>
            <p>
              {report.issues.length
                ? `시간 확인이 필요한 항목 ${report.issues.length}건`
                : report.windows.length
                  ? `연결한 교통편 ${report.windows.length}건 · 입력한 시간 기준 겹침 없음`
                  : "도착·여유 시간을 확인해 예약을 일정에 연결해 주세요"}
            </p>
          </div>
        </div>
        {report.issues.length > 0 && (
          <button
            className="button primary small-button"
            onClick={() => setComparing(trip)}
          >
            <GitCompareArrows size={16} />
            일정 조정안 비교
          </button>
        )}
      </div>
      <div className="schedule-windows">
        {report.windows.map((w) => (
          <article className="schedule-window" key={w.bookingId}>
            <div className="row">
              <LockKeyhole size={14} />
              <strong>{w.label}</strong>
              <span className="badge subtle">직접 확인한 예약</span>
            </div>
            {(w.departurePlace || w.arrivalPlace) && (
              <p>
                {w.departurePlace || "출발지 미입력"} <ArrowRight size={13} />{" "}
                {w.arrivalPlace || "도착지 미입력"}
              </p>
            )}
            <p className="schedule-reserved-time">
              {scheduleTime(w.departure)} <ArrowRight size={14} />{" "}
              {scheduleTime(w.arrival)}
            </p>
            <p className="schedule-window-range">
              <Clock3 size={14} />
              {scheduleTime(w.leaveBy)} ~ {scheduleTime(w.resumeAt)} 일정 비워
              두기
            </p>
          </article>
        ))}
      </div>
      {report.issues.length > 0 && (
        <div className="schedule-issues" role="status">
          <strong>
            <TriangleAlert size={16} />
            방문 시간이나 교통편을 확인해 주세요
          </strong>
          <ul>
            {report.issues.slice(0, 3).map((i, n) => (
              <li key={n}>{i.message}</li>
            ))}
          </ul>
          {report.issues.length > 3 && (
            <details>
              <summary>나머지 {report.issues.length - 3}건 보기</summary>
              <ul>
                {report.issues.slice(3).map((i, n) => (
                  <li key={n}>{i.message}</li>
                ))}
              </ul>
            </details>
          )}
        </div>
      )}
      <p className="small muted schedule-explanation">
        출발 전 이동·탑승 여유와 도착 후 이동 여유를 포함해요. 역·터미널까지의
        실제 경로와 운행 변경은 별도로 확인해 주세요.
      </p>
      <button className="text-button" onClick={onBookings}>
        {report.unlinkedCount
          ? `일정에 연결하지 않은 예약 ${report.unlinkedCount}건 확인`
          : "교통편 시간·여유 시간 수정"}
        <ArrowRight size={14} />
      </button>
      {comparing && (
        <ScheduleComparison trip={comparing} close={close} refresh={refresh} />
      )}
    </section>
  );
}

/** 비교를 연 순간의 version을 유지합니다. 동행 변경 후 적용은 서버가 409로 막고 다시 비교하도록 안내합니다. */
function ScheduleComparison({
  trip,
  close,
  refresh,
}: {
  trip: Trip;
  close: () => void;
  refresh: () => Promise<void>;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const query = useQuery({
    queryKey: ["schedule-preview", trip.id, trip.version],
    queryFn: () =>
      api<SchedulePlan[]>(`/trips/${trip.id}/schedule/preview`, {
        method: "POST",
        body: { version: trip.version },
      }),
    retry: false,
    staleTime: Infinity,
  });
  async function apply(strategy: string) {
    setBusy(true);
    setError("");
    try {
      await api(`/trips/${trip.id}/schedule/apply`, {
        method: "POST",
        body: { version: trip.version, strategy },
      });
      await refresh();
      close();
    } catch (e) {
      setError((e as Error).message);
      await refresh();
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal title="교통 예약에 맞춰 일정 조정하기" onClose={close}>
      <p className="muted">
        방문 장소·순서·머무는 시간·활동비는 유지해요. 확정된 교통편과 고정 방문
        시간도 바꾸지 않아요.
      </p>
      <p className="small muted">
        같은 날 06:00~22:00 안에서 비교해요. 적용한 구간은 조회 시간, 나머지
        구간은 거리 기반 추정을 사용하며 예약 앞뒤 여유는 직접 입력한 값이에요.
      </p>
      <p className="small muted">
        변경된 방문 시간의 영업·입장 가능 여부는 장소 상세에서 확인해 주세요.
      </p>
      {query.isPending ? (
        <Loading text="고정 시간을 지키는 조정안 계산 중" />
      ) : query.error ? (
        <ErrorState error={query.error} />
      ) : (
        <div className="schedule-plans">
          {query.data?.map((plan) => (
            <article
              className={
                "schedule-plan " + (!plan.canApply ? "unavailable" : "")
              }
              key={plan.strategy}
            >
              <div className="section-heading">
                <h3>{plan.title}</h3>
                <span className={"badge " + (plan.canApply ? "" : "subtle")}>
                  {plan.canApply ? "시간상 적용 가능" : "추가 확인 필요"}
                </span>
              </div>
              <p className="small">
                {plan.changes.length}곳 시간 변경 · 활동비 변화 0원
              </p>
              <p className="small muted">
                {plan.strategy === "EARLIER"
                  ? "교통편 출발 전에 방문할 시간을 확보해요. 하루를 일찍 시작할 수 있어요."
                  : "교통편 도착 이후로 방문을 옮겨요. 하루 일정이 늦게 끝날 수 있어요."}
              </p>
              {plan.changes.length > 0 && (
                <ul className="schedule-shifts">
                  {plan.changes.map((c) => (
                    <li key={c.itemId}>
                      <span>
                        DAY {c.dayIndex + 1} · {c.name}
                      </span>
                      <strong>
                        <span>{c.before}</span>
                        <ArrowRight size={13} />
                        {c.after}
                      </strong>
                    </li>
                  ))}
                </ul>
              )}
              {plan.conflicts.length > 0 && (
                <div className="schedule-plan-conflicts">
                  <p>이 안으로 바로 적용하기 어려워요.</p>
                  <ul>
                    {plan.conflicts.map((c) => (
                      <li key={c}>{c}</li>
                    ))}
                  </ul>
                  <p>
                    방문 장소 대안이나 교통편 시간·여유 시간을 다시 확인해
                    주세요.
                  </p>
                </div>
              )}
              {/* 경로 비교와 같은 실행 영역을 사용해 하단 설명과 버튼 사이 여백을 일관되게 유지합니다. */}
              <div className="schedule-plan-actions">
                <button
                  className="button primary full"
                  disabled={!plan.canApply || busy || !!error}
                  onClick={() => apply(plan.strategy)}
                >
                  {busy ? (
                    <Loader2 size={16} className="spin" />
                  ) : (
                    <Clock3 size={16} />
                  )}
                  이 조정안 적용하기
                </button>
              </div>
            </article>
          ))}
        </div>
      )}
      {error && (
        <div role="alert" className="notice danger">
          {error} 창을 닫고 최신 일정에서 다시 비교해 주세요.
        </div>
      )}
      <p className="small muted">
        두 안으로 해결되지 않으면 방문일·장소 또는 교통편을 직접 변경해야 해요.
        예약 기록은 변경 이력 복원으로 되돌아가지 않아요.
      </p>
    </Modal>
  );
}
