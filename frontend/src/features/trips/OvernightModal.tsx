import { useCallback, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Clock3,
  ArrowRight,
  ArrowUpRight,
  GitCompareArrows,
} from "lucide-react";
import { api, money } from "../../shared/api";
import { useToast } from "../../shared/providers";
import { ErrorState, Loading, Modal } from "../../shared/ui";
import type {
  Trip,
  TransportResult,
  RouteTimingPlan,
} from "../../shared/types";
import { scheduleTime } from "./bookingScheduleTime";
/** 구간마다 경로를 고르고, 입력 조건이 바뀌면 재비교하기 전까지 저장을 막습니다. 서버가 영수증·버전을 재검사합니다. */
export default function OvernightModal({
  trip,
  pair,
  close,
  refresh,
  onDayChange,
}: {
  trip: Trip;
  pair: [TransportResult, TransportResult];
  close: () => void;
  refresh: () => Promise<void>;
  onDayChange: (day: number) => void;
}) {
  const [inbound, setInbound] = useState(pair[0].options[0].id);
  const [outbound, setOutbound] = useState(pair[1].options[0].id);
  const [buffer, setBuffer] = useState("20");
  const [hours, setHours] = useState("8");
  const [notBefore, setNotBefore] = useState("08:00");
  const current = {
    inboundOption: inbound,
    outboundOption: outbound,
    bufferMinutes: Number(buffer),
    minimumStayMinutes: Math.round(Number(hours) * 60),
    notBefore,
  };
  const [settings, setSettings] = useState(current);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const toast = useToast();
  const onClose = useCallback(() => {
    if (!busy) close();
  }, [busy, close]);
  const dirty =
    JSON.stringify(current) !== JSON.stringify(settings) ||
    buffer.trim() === "" ||
    hours.trim() === "";
  const valid =
    buffer.trim() !== "" &&
    Number.isInteger(Number(buffer)) &&
    Number(buffer) >= 0 &&
    Number(buffer) <= 120 &&
    hours.trim() !== "" &&
    Number(hours) >= 1 &&
    Number(hours) <= 16 &&
    (Number(hours) * 2) % 1 === 0 &&
    /^(0[6-9]|1\d|2[01]):[0-5]\d$/.test(notBefore);
  const body = {
    inboundToken: pair[0].quoteToken,
    outboundToken: pair[1].quoteToken,
    version: trip.version,
    ...settings,
  };
  const base = `/trips/${trip.id}/transport/overnight`;
  const query = useQuery({
    queryKey: [
      "overnight",
      trip.id,
      trip.version,
      pair[0].quoteToken,
      pair[1].quoteToken,
      settings,
    ],
    queryFn: () =>
      api<RouteTimingPlan[]>(base + "/preview", { method: "POST", body }),
    retry: false,
    staleTime: Infinity,
  });
  async function apply(plan: RouteTimingPlan) {
    setBusy(true);
    setError(null);
    try {
      await api(base + "/apply", {
        method: "POST",
        body: { ...body, strategy: plan.strategy },
      });
      await refresh();
      onDayChange(plan.changes[0]?.dayIndex ?? plan.timing.dayIndex);
      toast("숙소 경유와 체류 시간을 일정에 반영했어요.");
      close();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal
      title="숙소에서 쉬고, 다음 날도 편안하게"
      onClose={onClose}
      className="route-timing-modal overnight-modal"
    >
      <p className="muted">
        전날 마지막 방문과 다음 날 첫 방문을 연결해요. 비교만으로 일정이나 숙소
        예약은 바뀌지 않아요.
      </p>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (valid) setSettings(current);
        }}
      >
        <div className="overnight-route-choices">
          {pair.map((result, n) => (
            <RouteChoice
              key={n}
              result={result}
              label={n === 0 ? "숙소까지 이동" : "숙소에서 첫 방문까지"}
              value={n === 0 ? inbound : outbound}
              setValue={n === 0 ? setInbound : setOutbound}
              busy={busy}
            />
          ))}
        </div>
        <div className="overnight-settings">
          <label>
            구간별 추가 여유 (분)
            <input
              type="number"
              min="0"
              max="120"
              step="1"
              required
              value={buffer}
              disabled={busy}
              onChange={(e) => setBuffer(e.target.value)}
            />
          </label>
          <label>
            최소 숙소 체류 (시간)
            <input
              type="number"
              min="1"
              max="16"
              step="0.5"
              required
              value={hours}
              disabled={busy}
              onChange={(e) => setHours(e.target.value)}
            />
          </label>
          <label>
            다음 날 숙소 출발 가능 시각
            <input
              type="time"
              min="06:00"
              max="21:59"
              required
              value={notBefore}
              disabled={busy}
              onInput={(e) => setNotBefore(e.currentTarget.value)}
              onChange={(e) => setNotBefore(e.target.value)}
            />
          </label>
        </div>
        <p className="small muted overnight-action-note">
          체류는 체크인·준비를 포함해 숙소에 머물 최소 시간으로, 실제 수면
          시간은 아니에요. 두 이동 구간에는 추가 여유가 각각 한 번씩 더해져요.
          실제 여행일 운행과 객실 이용 조건은 별도로 확인해 주세요.
        </p>
        <button
          className="button secondary"
          disabled={!valid || busy || query.isFetching}
        >
          <GitCompareArrows size={17} />
          숙소 일정 다시 비교
        </button>
      </form>
      {dirty && (
        <p role="status" className="notice overnight-comparison-note">
          조건을 바꿨어요. 다시 비교한 뒤 원하는 안을 적용해 주세요.
        </p>
      )}
      <p className="small muted overnight-comparison-note">
        고정 방문·교통 예약과 방문일 06:00~22:00을 지켜요. 같은 첫 방문에 반영한
        기존 숙소 출발·날짜 간 이동은 이 연결로 대체돼요.
        장소·순서·체류·활동비는 유지해요.
      </p>
      {query.isPending ? (
        <Loading text="숙소 연결 두 안을 비교 중" />
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
                <span className={"badge " + (!plan.canApply ? "subtle" : "")}>
                  {plan.canApply ? "시간상 적용 가능" : "추가 확인 필요"}
                </span>
              </div>
              <p className="small muted">
                {plan.strategy === "DEPARTURE"
                  ? "전날 방문을 지키고 숙소 체류 후 늦어지는 다음 날 방문을 조정해요."
                  : "다음 날 첫 방문을 지키고 숙소 체류를 위해 필요하면 전날 방문을 앞당겨요."}
              </p>
              <div className="overnight-stay-times">
                <strong>{plan.timing.overnight!.lodgingName}</strong>
                <p>
                  숙소 도착{" "}
                  <b>{scheduleTime(plan.timing.overnight!.arrival)}</b>
                </p>
                <p>
                  숙소 출발{" "}
                  <b>{scheduleTime(plan.timing.overnight!.departure)}</b>
                </p>
                <p className="small">
                  최소 체류 {settings.minimumStayMinutes / 60}시간 · 구간별 여유{" "}
                  {settings.bufferMinutes}분
                </p>
              </div>
              <p className="small">
                숙소로 이동 출발 {plan.departure}
                <br />
                다음 날 방문지 도착 {plan.arrival}
              </p>
              {plan.changes.length ? (
                <ul className="schedule-shifts">
                  {plan.changes.map((c) => (
                    <li key={c.itemId}>
                      <span>
                        {c.dayIndex + 1}일차 · {c.name}
                      </span>
                      <strong>
                        <span>{c.before}</span>
                        <ArrowRight size={14} />
                        {c.after}
                      </strong>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="small muted">
                  방문 시각은 유지하고 숙소 연결 조건을 저장해요.
                </p>
              )}
              {!!plan.conflicts.length && (
                <div className="schedule-plan-conflicts">
                  <ul>
                    {plan.conflicts.map((c) => (
                      <li key={c}>{c}</li>
                    ))}
                  </ul>
                </div>
              )}
              <div className="schedule-plan-actions">
                <button
                  className="button primary full"
                  disabled={
                    !plan.canApply ||
                    busy ||
                    dirty ||
                    !!error ||
                    query.isFetching
                  }
                  onClick={() => apply(plan)}
                >
                  <Clock3 size={17} />
                  {busy ? "반영 중…" : "이 안으로 숙소 연결 반영"}
                </button>
              </div>
            </article>
          ))}
        </div>
      )}
      {!!error && <ErrorState error={error} />}
    </Modal>
  );
}

/** 각 구간의 조회 시각·요금·공식 길찾기를 따로 보여 줍니다. 합산 요금은 지출이나 예약 확정으로 저장하지 않습니다. */
function RouteChoice({
  result,
  label,
  value,
  setValue,
  busy,
}: {
  result: TransportResult;
  label: string;
  value: string;
  setValue: (value: string) => void;
  busy: boolean;
}) {
  const option = result.options.find((o) => o.id === value)!;
  return (
    <section className="overnight-route-choice">
      <label>
        {label}
        <select
          value={value}
          disabled={busy}
          onChange={(e) => setValue(e.target.value)}
        >
          {result.options.map((o, n) => (
            <option key={o.id} value={o.id}>
              경로 {n + 1} · {o.minutes}분
              {o.transfers !== null ? ` · 환승 ${o.transfers}회` : ""}
            </option>
          ))}
        </select>
      </label>
      <p>
        {result.from.label} → {result.to.label}
      </p>
      <p className="small">
        {(option.distanceMeters / 1000).toFixed(1)}km ·{" "}
        {result.mode === "CAR"
          ? option.toll === null
            ? "통행료 미제공"
            : "통행료 " + money(option.toll)
          : result.mode === "WALK"
            ? "도보"
            : option.fare === null
              ? "요금 미제공"
              : money(option.fare) + " · 1인 조회요금"}
      </p>
      <p className="small muted">
        {new Date(result.queriedAt).toLocaleString("ko-KR")} ·{" "}
        {result.source === "KAKAO_MOBILITY" ? "카카오모빌리티" : "카카오맵"}
      </p>
      <a
        className="text-button"
        href={result.externalUrl}
        target="_blank"
        rel="noreferrer"
      >
        공식 길찾기에서 확인 <ArrowUpRight size={15} />
      </a>
    </section>
  );
}
