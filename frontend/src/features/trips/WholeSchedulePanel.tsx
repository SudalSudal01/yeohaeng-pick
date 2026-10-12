import { useCallback, useRef, useState, type FormEvent } from "react";
import {
  ArrowRight,
  CalendarClock,
  GitCompareArrows,
  Loader2,
  LockKeyhole,
  Moon,
  Sun,
} from "lucide-react";
import { api, ApiError } from "../../shared/api";
import { ErrorState, Modal } from "../../shared/ui";
import { useToast } from "../../shared/providers";
import type { Trip, WholeSchedulePlan } from "../../shared/types";

type Settings = { startTime: string; endTime: string; bufferMinutes: number };
const initialSettings: Settings = {
  startTime: "09:00",
  endTime: "21:00",
  bufferMinutes: 20,
};

/** 예약 충돌 유무와 관계없이 여행 전체를 비교합니다. 열린 순간의 여행을 보관해 공동 편집을 덮어쓰지 않습니다. */
export default function WholeSchedulePanel({
  trip,
  refresh,
}: {
  trip: Trip;
  refresh: () => Promise<void>;
}) {
  const [snapshot, setSnapshot] = useState<Trip | null>(null);
  const close = useCallback(() => setSnapshot(null), []);
  return (
    <>
      <section className="whole-schedule-entry" aria-label="전체 일정 조정">
        <div>
          <h2>
            <CalendarClock size={21} />
            전체 일정 조정
          </h2>
          <p>
            하루 시작·마감과 이동 여유를 정하고, 모든 날짜의 변경을 함께
            비교해요.
          </p>
        </div>
        <button className="button secondary" onClick={() => setSnapshot(trip)}>
          <GitCompareArrows size={17} />
          전체 일정 비교
        </button>
      </section>
      {snapshot && (
        <WholeScheduleModal trip={snapshot} close={close} refresh={refresh} />
      )}
    </>
  );
}

function WholeScheduleModal({
  trip,
  close,
  refresh,
}: {
  trip: Trip;
  close: () => void;
  refresh: () => Promise<void>;
}) {
  const toast = useToast();
  const [draft, setDraft] = useState<Settings>(initialSettings);
  const [compared, setCompared] = useState<Settings | null>(null);
  const [plans, setPlans] = useState<WholeSchedulePlan[]>([]);
  const [pending, setPending] = useState(false);
  const [applying, setApplying] = useState<string | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const busy = pending || !!applying;
  const busyRef = useRef(false);
  busyRef.current = busy;
  // dialog의 닫기 콜백은 동일한 참조를 유지합니다. 적용 도중 재개방되거나 중복 실행되지 않게 합니다.
  const guardedClose = useCallback(() => {
    if (!busyRef.current) close();
  }, [close]);
  const dirty =
    !compared ||
    draft.startTime !== compared.startTime ||
    draft.endTime !== compared.endTime ||
    draft.bufferMinutes !== compared.bufferMinutes;
  const stale = error instanceof ApiError && error.status === 409;

  async function compare(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    setPending(true);
    setError(null);
    setPlans([]);
    try {
      const result = await api<WholeSchedulePlan[]>(
        `/trips/${trip.id}/schedule/whole/preview`,
        {
          method: "POST",
          body: { ...draft, version: trip.version },
        },
      );
      setCompared({ ...draft });
      setPlans(result);
    } catch (e) {
      setError(e as Error);
    } finally {
      setPending(false);
    }
  }

  async function apply(plan: WholeSchedulePlan) {
    if (busy || dirty || !compared || !plan.canApply || error) return;
    setApplying(plan.strategy);
    try {
      // 서버는 전달받은 전략·조건으로 다시 계산합니다. 화면의 items/changes는 저장 요청에 넣지 않습니다.
      await api(`/trips/${trip.id}/schedule/whole/apply`, {
        method: "POST",
        body: { ...compared, version: trip.version, strategy: plan.strategy },
      });
      await refresh();
      toast(
        "전체 방문 시간을 변경했어요. 변경 이력에서 이전 시간을 확인할 수 있어요.",
      );
      close();
    } catch (e) {
      setError(e as Error);
      await refresh();
    } finally {
      setApplying(null);
    }
  }

  return (
    <Modal
      title="전체 일정 비교"
      onClose={guardedClose}
      className="whole-schedule-modal"
    >
      <p className="muted">
        방문 장소·날짜·순서·체류 시간·비용을 유지하고, 방문 시각만 비교해요.
      </p>
      <div className="whole-schedule-protection">
        <LockKeyhole size={17} />
        고정 방문 · 연결 교통 예약 · 반영한 이동 소요·숙소 출발 보호
      </div>
      <form onSubmit={compare} className="whole-schedule-form">
        <fieldset disabled={busy || stale}>
          <legend>모든 여행 날짜의 방문 시간 기준</legend>
          <div className="whole-schedule-controls">
            <label>
              방문 시작 가능 시각
              <input
                type="time"
                required
                min="06:00"
                max="21:00"
                value={draft.startTime}
                onChange={(e) =>
                  setDraft({ ...draft, startTime: e.target.value })
                }
              />
            </label>
            <label>
              방문 마감 시각
              <input
                type="time"
                required
                min="07:00"
                max="22:00"
                value={draft.endTime}
                onChange={(e) =>
                  setDraft({ ...draft, endTime: e.target.value })
                }
              />
            </label>
            <label>
              추정 구간의 이동 여유
              <select
                value={draft.bufferMinutes}
                onChange={(e) =>
                  setDraft({ ...draft, bufferMinutes: Number(e.target.value) })
                }
              >
                {[0, 10, 20, 30, 45, 60, 90, 120].map((n) => (
                  <option key={n} value={n}>
                    {n}분씩
                  </option>
                ))}
              </select>
            </label>
          </div>
          <p className="small muted">
            06:00~22:00 안에서 1시간 이상으로 정해 주세요. 반영한 경로의 여유
            시간은 기존 값을 사용해요. 이 기준은 이번 비교에만 사용해요.
          </p>
        </fieldset>
        <div className="whole-schedule-form-actions">
          <button
            type="submit"
            className="button primary"
            disabled={busy || stale}
          >
            {pending ? (
              <Loader2 size={17} className="spin" />
            ) : (
              <GitCompareArrows size={17} />
            )}
            {pending
              ? "전체 시간표 계산 중"
              : plans.length
                ? "기준으로 다시 비교"
                : "두 가지 시간표 비교"}
          </button>
        </div>
      </form>
      {error && <ErrorState error={error} />}
      {stale && (
        <div className="whole-schedule-form-actions">
          <button
            className="button secondary"
            disabled={busy}
            onClick={async () => {
              await refresh();
              close();
            }}
          >
            최신 여행 불러오기
          </button>
        </div>
      )}
      {plans.length > 0 && (
        <section aria-label="전체 일정 비교 결과" aria-live="polite">
          <div className="whole-schedule-results-heading">
            <h3>어떤 하루가 더 마음에 드나요?</h3>
            <p className="small muted">
              같은 날 연속 방문: 반영한 조회 {plans[0].queriedLegs}구간 · 거리
              추정 {plans[0].estimatedLegs}구간
            </p>
          </div>
          {dirty && (
            <p className="notice" role="status">
              기준을 변경했어요. 다시 비교한 뒤 적용해 주세요.
            </p>
          )}
          {plans[0].warnings.length > 0 && (
            <ul className="whole-schedule-warnings">
              {plans[0].warnings.map((w) => (
                <li key={w}>{w}</li>
              ))}
            </ul>
          )}
          <div className="schedule-plans">
            {plans.map((plan) => (
              <article
                className={
                  "schedule-plan " + (!plan.canApply ? "unavailable" : "")
                }
                key={plan.strategy}
                aria-label={plan.title}
              >
                <div className="section-heading">
                  <h3>
                    {plan.strategy === "EARLY" ? (
                      <Sun size={19} />
                    ) : (
                      <Moon size={19} />
                    )}
                    {plan.title}
                  </h3>
                  <span className={"badge " + (!plan.canApply ? "subtle" : "")}>
                    {plan.conflicts.length
                      ? "조건 확인 필요"
                      : plan.changes.length
                        ? "시간상 적용 가능"
                        : "변경 없음"}
                  </span>
                </div>
                <p>{plan.changes.length}곳 시간 변경 · 활동비 변화 0원</p>
                <p className="small muted">
                  {plan.strategy === "EARLY"
                    ? "저녁 자유 시간을 확보해요. 첫 방문이 이른 시간으로 바뀔 수 있어요."
                    : "아침을 여유롭게 시작해요. 마지막 방문이 마감에 가까워질 수 있어요."}
                </p>
                <div className="whole-schedule-days">
                  {plan.days.map((d) => (
                    <div className="whole-schedule-day" key={d.dayIndex}>
                      <strong>DAY {d.dayIndex + 1}</strong>
                      <span className="small muted">
                        현재 {d.beforeStart} ~ {d.beforeEnd}
                      </span>
                      <span className="whole-schedule-after">
                        <ArrowRight size={15} />
                        {d.afterStart} ~ {d.afterEnd}
                      </span>
                    </div>
                  ))}
                </div>
                {plan.changes.length > 0 && (
                  <details className="whole-schedule-changes">
                    <summary>
                      방문 시간 변경 {plan.changes.length}곳 보기
                    </summary>
                    <ul className="schedule-shifts">
                      {plan.changes.map((c) => (
                        <li key={c.itemId}>
                          <span>
                            DAY {c.dayIndex + 1} · {c.name}
                          </span>
                          <strong>
                            <span>{c.before}</span>
                            <ArrowRight size={14} />
                            {c.after}
                          </strong>
                        </li>
                      ))}
                    </ul>
                  </details>
                )}
                {plan.conflicts.length > 0 && (
                  <div className="schedule-plan-conflicts">
                    <strong>이 안에서 확인할 조건</strong>
                    <ul>
                      {plan.conflicts.map((c) => (
                        <li key={c}>{c}</li>
                      ))}
                    </ul>
                  </div>
                )}
                {!plan.changes.length && !plan.conflicts.length && (
                  <p className="small muted">
                    현재 방문 시각과 같아요. 적용할 변경이 없어요.
                  </p>
                )}
                <div className="schedule-plan-actions">
                  <button
                    className="button primary"
                    disabled={busy || dirty || !!error || !plan.canApply}
                    onClick={() => apply(plan)}
                  >
                    {applying === plan.strategy && (
                      <Loader2 size={16} className="spin" />
                    )}
                    {applying === plan.strategy
                      ? "방문 시간 저장 중"
                      : "이 시간표로 변경"}
                  </button>
                </div>
              </article>
            ))}
          </div>
        </section>
      )}
      <p className="small muted whole-schedule-limits">
        같은 날의 이동은 앞 방문을 마친 직후로 계산해요. 장소 영업·입장 가능
        시간과 여행 날짜의 운행은 별도로 확인해 주세요. 방문 순서를 유지해 두
        방향으로 시간을 조정해요.
      </p>
    </Modal>
  );
}
