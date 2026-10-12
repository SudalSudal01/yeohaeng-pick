import { useCallback, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  ArrowRight,
  Clock3,
  GitCompareArrows,
  Loader2,
  LockKeyhole,
} from "lucide-react";
import { api } from "../../shared/api";
import { useToast } from "../../shared/providers";
import { Modal, ErrorState, Loading } from "../../shared/ui";
import type { Trip, SelectedRoute, RouteTimingPlan } from "../../shared/types";
import { tripDayDate } from "./transportSchedule";

/**
 * 비교를 연 순간의 여행 버전과 조회 영수증을 유지합니다. 설정을 고치면 재비교 전에는 적용하지 못합니다.
 * 실제 저장 시 서버가 최신 예약·버전을 다시 검사하며 화면에서 계산한 시각을 전송하지 않습니다.
 */
export default function RouteTimingModal({
  trip,
  route,
  lodging,
  close,
  refresh,
  onAppliedDay,
}: {
  trip: Trip;
  route: SelectedRoute;
  lodging: boolean;
  close: () => void;
  refresh: () => Promise<void>;
  onAppliedDay: (day: number) => void;
}) {
  const [buffer, setBuffer] = useState("20");
  const fromItem = trip.items.find((i) => i.id === route.result.from.id);
  const toItem = trip.items.find((i) => i.id === route.result.to.id);
  const crossDay =
    !!fromItem && !!toItem && fromItem.dayIndex !== toItem.dayIndex;
  const initialDeparture = crossDay
    ? tripDayDate(trip.startDate, toItem!.dayIndex) + "T08:00"
    : "09:00";
  const [departure, setDeparture] = useState(initialDeparture);
  const [settings, setSettings] = useState({
    bufferMinutes: 20,
    departureTime: initialDeparture,
  });
  const [busy, setBusy] = useState(false);
  const toast = useToast();
  const [error, setError] = useState("");
  const onClose = useCallback(() => {
    if (!busy) close();
  }, [busy, close]);
  const body = {
    quoteToken: route.result.quoteToken,
    optionId: route.option.id,
    version: trip.version,
    ...settings,
  };
  const dirty =
    buffer.trim() === "" ||
    Number(buffer) !== settings.bufferMinutes ||
    departure !== settings.departureTime;
  const valid =
    buffer.trim() !== "" &&
    Number.isInteger(Number(buffer)) &&
    Number(buffer) >= 0 &&
    Number(buffer) <= 120 &&
    (!(lodging || crossDay) || !!departure);
  const base = `/trips/${trip.id}/transport/timing`;
  const query = useQuery({
    queryKey: [
      "route-timing",
      trip.id,
      trip.version,
      route.result.quoteToken,
      route.option.id,
      settings,
    ],
    queryFn: () =>
      api<RouteTimingPlan[]>(base + "/preview", { method: "POST", body }),
    retry: false,
    staleTime: Infinity,
  });
  async function apply(strategy: string) {
    setBusy(true);
    setError("");
    try {
      await api(base + "/apply", {
        method: "POST",
        body: { ...body, strategy },
      });
      await refresh();
      // 날짜 경계의 적용 후에는 실제로 바뀐 방문일을 보여 줍니다. 변경이 없어도 저장 구간의 도착일로 이동합니다.
      const applied = query.data?.find((plan) => plan.strategy === strategy);
      onAppliedDay(applied?.changes[0]?.dayIndex ?? toItem?.dayIndex ?? 0);
      toast("조회 경로 시간과 여유를 일정에 반영했어요.");
      close();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal
      title="경로 시간, 일정과 함께 비교해요"
      onClose={onClose}
      className="route-timing-modal"
    >
      <div className="route-timing-summary">
        <span className="eyebrow">YOUR ROUTE, YOUR PACE</span>
        <h3>
          {route.result.from.label} <ArrowRight size={16} />{" "}
          {route.result.to.label}
        </h3>
        <p>
          <strong>이동 {route.option.minutes}분</strong> ·{" "}
          {route.result.mode === "CAR"
            ? "자동차"
            : route.result.mode === "WALK"
              ? "도보"
              : "대중교통"}
        </p>
        <p className="small muted">
          {new Date(route.result.queriedAt).toLocaleString("ko-KR")} 조회 ·{" "}
          {route.result.source === "KAKAO_MOBILITY"
            ? "카카오모빌리티"
            : "카카오맵"}
        </p>
      </div>
      <form
        className="route-timing-settings"
        onSubmit={(e) => {
          e.preventDefault();
          if (valid)
            setSettings({
              bufferMinutes: Number(buffer),
              departureTime: departure,
            });
        }}
      >
        <label>
          추가 여유 시간 (분)
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
        {(lodging || crossDay) && (
          <label>
            {crossDay ? "이동 출발 일시" : "숙소 출발 시간"}
            <input
              type={crossDay ? "datetime-local" : "time"}
              min={
                crossDay
                  ? tripDayDate(trip.startDate, fromItem!.dayIndex) + "T00:00"
                  : undefined
              }
              max={
                crossDay
                  ? tripDayDate(trip.startDate, toItem!.dayIndex) + "T23:59"
                  : undefined
              }
              required
              value={departure}
              disabled={busy}
              onInput={(e) => setDeparture(e.currentTarget.value)}
              onChange={(e) => setDeparture(e.target.value)}
            />
          </label>
        )}
        <button
          className="button secondary"
          disabled={!valid || busy || query.isFetching}
        >
          <GitCompareArrows size={16} />
          시간 다시 비교
        </button>
        <p className="small muted">
          조회 시간에 포함된 도보·환승 외에 준비·휴식을 위해 더 둘 여유예요.
          0~120분으로 조절해요.
        </p>
      </form>
      {crossDay && (
        <p className="notice route-cross-day-note">
          {fromItem!.dayIndex + 1}일차 마지막 방문과 {toItem!.dayIndex + 1}일차
          첫 방문 사이의 직접 이동이에요. 실제로 이동할 출발 일시를 선택해
          주세요. 숙소·역 경유와 수면·휴식 시간은 따로 확인해야 해요.
        </p>
      )}
      {trip.routeTimings?.some(
        (t) => t.toItemId === route.result.to.id && t.overnight,
      ) && (
        <p className="notice route-cross-day-note">
          이 안을 적용하면 같은 첫 방문에 저장한 숙소 경유·최소 체류 조건이 새
          조회 경로로 대체돼요. 숙소 연결을 유지하려면 ‘숙소·휴식 비교’에서
          조절해 주세요.
        </p>
      )}
      {dirty && (
        <p className="notice" role="status">
          설정을 바꿨어요. ‘시간 다시 비교’를 눌러 새 결과를 확인해 주세요.
        </p>
      )}
      <p className="small muted route-timing-rule">
        <LockKeyhole size={14} />
        고정 방문·교통 예약을 지키며 방문은 각 날짜의 06:00~22:00 안에서
        비교해요. 장소·순서·체류 시간·활동비는 유지해요.
      </p>
      {query.isPending ? (
        <Loading text="두 가지 일정의 시간 비교 중" />
      ) : query.error ? (
        <ErrorState error={query.error} />
      ) : (
        <div className="schedule-plans">
          {query.data?.map((plan) => (
            <article
              key={plan.strategy}
              className={
                "schedule-plan " + (!plan.canApply ? "unavailable" : "")
              }
            >
              <div className="section-heading">
                <h3>{plan.title}</h3>
                <span className={"badge " + (plan.canApply ? "" : "subtle")}>
                  {plan.canApply ? "시간상 적용 가능" : "추가 확인 필요"}
                </span>
              </div>
              <p className="small muted">
                {plan.strategy === "DEPARTURE"
                  ? crossDay
                    ? "입력한 이동 출발 일시를 지키고 다음 날 늦어지는 방문을 조정해요."
                    : lodging
                      ? "입력한 숙소 출발 시각을 기준으로 이후 방문을 조정해요."
                      : "출발 장소에 머무는 시간을 지키고 이후 방문을 조정해요."
                  : crossDay
                    ? "다음 날 첫 방문을 지키고 필요한 이동 출발 일시를 계산해요. 전날 방문이 앞당겨질 수 있어요."
                    : lodging
                      ? "첫 방문 시간을 지키고 숙소 출발 시각을 계산해요."
                      : "도착 장소의 방문 시간을 지키고 앞 방문을 조정해요. 하루가 일찍 시작될 수 있어요."}
              </p>
              <div className="route-timing-journey">
                <span>
                  출발 <strong>{plan.departure}</strong>
                </span>
                <ArrowRight size={17} />
                <span>
                  여유 포함 도착 <strong>{plan.arrival}</strong>
                </span>
              </div>
              <p className="small">
                이동 {route.option.minutes}분 + 여유 {settings.bufferMinutes}분
                · {plan.changes.length}곳 시간 변경
              </p>
              {plan.changes.length ? (
                <ul className="schedule-shifts">
                  {plan.changes.map((c) => (
                    <li key={c.itemId}>
                      <span>
                        {crossDay && `${c.dayIndex + 1}일차 · `}
                        {c.name}
                      </span>
                      <strong>
                        <span>{c.before}</span>
                        <ArrowRight size={13} />
                        {c.after}
                      </strong>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="small muted">
                  방문 시간 변경 없이 이 구간의 조회 시간을 저장해요.
                </p>
              )}
              {!!plan.conflicts.length && (
                <div className="schedule-plan-conflicts">
                  <ul>
                    {plan.conflicts.map((c) => (
                      <li key={c}>{c}</li>
                    ))}
                  </ul>
                  <p>다른 안을 고르거나 방문 장소·교통편을 확인해 주세요.</p>
                </div>
              )}
              {/* 안내·변경 목록과 실행 영역을 분리해 내용 길이와 관계없이 충분한 간격을 확보합니다. */}
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
                  onClick={() => apply(plan.strategy)}
                >
                  {busy ? (
                    <Loader2 size={16} className="spin" />
                  ) : (
                    <Clock3 size={16} />
                  )}
                  이 안으로 일정에 반영
                </button>
              </div>
            </article>
          ))}
        </div>
      )}
      {error && (
        <div className="notice danger" role="alert">
          {error} 창을 닫고 최신 일정에서 경로를 다시 조회해 주세요.
        </div>
      )}
      <p className="small muted route-timing-footnote">
        이 구간에 적용한 조회 시간은 저장되고 이후 일정 비교에도 반영돼요.
        나머지 구간은 거리 기반 추정이에요. 여행 날짜의 운행·영업시간은 별도
        확인이 필요하며, 조회 요금은 활동비나 지출에 자동 합산되지 않아요.
      </p>
    </Modal>
  );
}
