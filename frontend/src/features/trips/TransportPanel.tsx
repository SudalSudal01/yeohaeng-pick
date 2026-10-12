import { useCallback, useEffect, useId, useRef, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import {
  TrainFront,
  ArrowUpRight,
  Search,
  Route,
  Footprints,
  CarFront,
} from "lucide-react";
import { api, money } from "../../shared/api";
import type {
  Conditions,
  SelectedRoute,
  TransportPoint,
  TransportResult,
  Trip,
} from "../../shared/types";
import RouteTimingModal from "./RouteTimingModal";
import { routeTimingEligibility } from "./routeTimingEligibility";
import BookingRouteGuide from "./BookingRouteGuide";
import {
  bookingConnectionImpact,
  type BookingConnection,
} from "./transportSchedule";
import { scheduleTime } from "./bookingScheduleTime";

/** 조회 버튼을 눌렀을 때만 공급자를 호출합니다. 경로 선택은 지도 표시만 바꾸며 예약/일정은 수정하지 않습니다. */
export default function TransportPanel({
  trip,
  day,
  selected,
  onSelect,
  refresh,
  onDayChange,
}: {
  trip: Trip;
  day: number;
  selected: SelectedRoute | null;
  onSelect: (route: SelectedRoute | null) => void;
  refresh: () => Promise<void>;
  onDayChange: (day: number) => void;
}) {
  const dayItems = trip.items.filter((i) => i.dayIndex === day);
  const [from, setFrom] = useState(dayItems[0] ? "ITEM:" + dayItems[0].id : "");
  const [to, setTo] = useState(dayItems[1] ? "ITEM:" + dayItems[1].id : "");
  const [mode, setMode] = useState<Conditions["transport"]>(trip.transport);
  const [originQuery, setOriginQuery] = useState(trip.origin);
  const [origins, setOrigins] = useState<TransportPoint[]>([]);
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchSide, setSearchSide] = useState<"FROM" | "TO">("FROM");
  const [connection, setConnection] = useState<
    (BookingConnection & { originId?: string }) | null
  >(null);
  const originInput = useRef<HTMLInputElement>(null);
  const connectionId = connection?.bookingId;
  const connectionSide = connection?.side;
  // 길어진 예약 목록 아래에 열린 검색창을 놓치지 않도록 입력으로 이동합니다. 후보를 선택할 때는 포커스를 빼앗지 않습니다.
  useEffect(() => {
    if (searchOpen && connectionId) originInput.current?.focus();
  }, [searchOpen, connectionId, connectionSide]);
  const [comparing, setComparing] = useState<SelectedRoute | null>(null);
  const closeComparison = useCallback(() => setComparing(null), []);
  const timingReason = connection
    ? "예약 역·터미널 연결은 경로별로 시간상 여유를 보여 줘요. 방문 시간을 바꾸려면 위의 예약 시간 조정에서 비교해 주세요."
    : routeTimingEligibility(trip, from, to);
  const timingHintId = useId();
  const base = "/trips/" + trip.id + "/transport";
  const reference = (value: string) => {
    const [kind, id] = value.split(":");
    return { kind, id };
  };
  const route = useMutation({
    mutationFn: () =>
      api<TransportResult>(base + "/routes", {
        method: "POST",
        body: { from: reference(from), to: reference(to), mode },
      }),
    onSuccess: (result) =>
      onSelect(
        result.options[0] ? { result, option: result.options[0] } : null,
      ),
  });
  const originSearch = useMutation({
    mutationFn: () =>
      api<TransportPoint[]>(base + "/origins", {
        method: "POST",
        body: { query: originQuery.trim() },
      }),
    onSuccess: (points) => {
      setOrigins(points);
      // 새 검색으로 후보를 바꾸면 사라진 출발지 토큰이 선택된 상태로 남지 않도록 초기화합니다.
      if (from.startsWith("ORIGIN:")) setFrom("");
      if (to.startsWith("ORIGIN:")) setTo("");
      route.reset();
      onSelect(null);
    },
  });
  const reset = () => {
    route.reset();
    onSelect(null);
  };
  // 예약 앞뒤 방문이 자동으로 잡히지 않아도 직접 고른 방문으로 여유를 확인할 수 있습니다.
  // 검색 위치 쪽을 여행 방문으로 바꾸는 등 연결 형식 자체가 달라지면 예약 문맥을 해제합니다.
  const changeEndpoint = (side: "FROM" | "TO", value: string) => {
    if (side === "FROM") setFrom(value);
    else setTo(value);
    if (connection) {
      const originSide = connection.side === "DEPARTURE" ? "TO" : "FROM";
      const [kind, id] = value.split(":");
      if (kind === (side === originSide ? "ORIGIN" : "ITEM"))
        setConnection({
          ...connection,
          ...(side === originSide ? { originId: id } : { itemId: id }),
        });
      else setConnection(null);
    }
    reset();
  };
  const options = (
    <>
      <option value="">장소를 선택해 주세요</option>
      <optgroup label="여행 일정">
        {trip.items.map((i) => (
          <option key={i.id} value={"ITEM:" + i.id}>
            {i.dayIndex + 1}일차 · {i.placeName}
          </option>
        ))}
      </optgroup>
      {!!trip.lodgings.length && (
        <optgroup label="선택한 숙소">
          {trip.lodgings.map((p) => (
            <option key={p.placeId} value={"LODGING:" + p.placeId}>
              {p.region} · {p.name}
            </option>
          ))}
        </optgroup>
      )}
      {!!origins.length && (
        <optgroup label="찾은 장소">
          {origins.map((p) => (
            <option key={p.id} value={"ORIGIN:" + p.id}>
              {p.label}
            </option>
          ))}
        </optgroup>
      )}
    </>
  );
  const data = route.data;
  const first = dayItems[0];
  const previousLast = trip.items.filter((i) => i.dayIndex === day - 1).at(-1);
  const nextFirst = trip.items.find((i) => i.dayIndex === day + 1);
  const transitionFrom = previousLast ?? dayItems.at(-1);
  const transitionTo = previousLast ? first : nextFirst;
  return (
    <section
      className="transport-panel"
      id="transport-routes"
      aria-label="실제 이동 경로 비교"
    >
      <div className="transport-heading">
        <span className="eyebrow">MOVE WITH YOUR PICK</span>
        <h3>
          <Route size={19} /> 이동도 편안하게
        </h3>
        <p>
          일정 사이, 숙소에서, 출발지부터.
          <br />
          실제 경로를 조회하고 비교해요.
        </p>
      </div>
      <div className="transport-form">
        {transitionFrom &&
          transitionTo &&
          transitionFrom.dayIndex !== transitionTo.dayIndex && (
            <div className="transport-day-connection">
              <strong>날짜가 바뀌는 이동</strong>
              <p className="small muted">
                전날 마지막 방문과 다음 날 첫 방문 사이의 직접 경로를 비교해요.
              </p>
              <button
                className="button secondary"
                disabled={route.isPending || originSearch.isPending}
                onClick={() => {
                  setFrom("ITEM:" + transitionFrom.id);
                  setTo("ITEM:" + transitionTo.id);
                  setConnection(null);
                  reset();
                }}
              >
                {transitionFrom.dayIndex + 1}일차 마지막 →{" "}
                {transitionTo.dayIndex + 1}일차 첫 방문 선택
              </button>
            </div>
          )}
        <label>
          출발지
          <select
            value={from}
            disabled={route.isPending || originSearch.isPending}
            onChange={(e) => changeEndpoint("FROM", e.target.value)}
          >
            {options}
          </select>
        </label>
        <label>
          도착지
          <select
            value={to}
            disabled={route.isPending || originSearch.isPending}
            onChange={(e) => changeEndpoint("TO", e.target.value)}
          >
            {options}
          </select>
        </label>
        <div className="transport-modes" role="group" aria-label="이동 수단">
          {(
            [
              { value: "TRANSIT", label: "대중교통", Icon: TrainFront },
              { value: "WALK", label: "도보", Icon: Footprints },
              { value: "CAR", label: "자동차", Icon: CarFront },
            ] as const
          ).map(({ value, label, Icon }) => (
            <button
              key={value}
              className={mode === value ? "selected" : ""}
              aria-pressed={mode === value}
              disabled={route.isPending}
              onClick={() => {
                setMode(value);
                reset();
              }}
            >
              <Icon size={17} />
              {label}
            </button>
          ))}
        </div>
        <button
          className="button primary transport-query"
          disabled={
            !from ||
            !to ||
            from === to ||
            route.isPending ||
            originSearch.isPending
          }
          onClick={() => {
            onSelect(null);
            route.mutate();
          }}
        >
          <Search size={17} />
          {route.isPending ? "경로를 찾고 있어요…" : "실제 경로 조회하기"}
        </button>
        {/* 가능 조건은 조회 전부터 한 번만 안내하고, 비활성 버튼의 설명에도 연결합니다. */}
        <p id={timingHintId} className="transport-timing-hint small muted">
          <strong>일정 영향 비교 이용 안내</strong>
          {timingReason ||
            "연속 방문이나 전날 마지막→다음 날 첫 방문, 숙소→첫 방문의 경로를 조회한 뒤 사용할 수 있어요. 비교 후 원하는 안을 직접 적용해요."}
        </p>
        <BookingRouteGuide
          trip={trip}
          busy={route.isPending || originSearch.isPending}
          onPrepare={(candidate) => {
            const departure = candidate.side === "DEPARTURE";
            setConnection(candidate);
            setOriginQuery(candidate.query);
            setOrigins([]);
            setSearchSide(departure ? "TO" : "FROM");
            setSearchOpen(true);
            setFrom(
              departure && candidate.itemId ? "ITEM:" + candidate.itemId : "",
            );
            setTo(
              !departure && candidate.itemId ? "ITEM:" + candidate.itemId : "",
            );
            originSearch.reset();
            reset();
          }}
        />
        <details
          className="origin-search"
          open={searchOpen}
          onToggle={(e) => setSearchOpen(e.currentTarget.open)}
        >
          <summary>역·터미널 등 다른 장소 찾기</summary>
          <p className="small muted">
            검색 버튼을 누르면 입력한 장소명이 카카오에 전달돼요. 기기 위치는
            수집하지 않아요.
          </p>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              originSearch.mutate();
            }}
          >
            <label>
              장소 검색어
              <input
                ref={originInput}
                value={originQuery}
                maxLength={100}
                placeholder="예: 서울역"
                disabled={originSearch.isPending || route.isPending}
                onChange={(e) => {
                  setOriginQuery(e.target.value);
                  setConnection(null);
                }}
              />
            </label>
            <button
              className="button secondary"
              disabled={
                !originQuery.trim() || originSearch.isPending || route.isPending
              }
            >
              {originSearch.isPending ? "검색 중…" : "장소 검색"}
            </button>
          </form>
          {connection && (
            <p className="small muted" role="status">
              {connection.query} 검색을 준비했어요. 실제 예약 장소와 같은
              위치인지 확인해 선택한 뒤 경로를 조회해 주세요.
            </p>
          )}
          <label>
            찾은 장소를 사용할 위치
            <select
              value={searchSide}
              disabled={route.isPending || originSearch.isPending}
              onChange={(e) => {
                setSearchSide(e.target.value as "FROM" | "TO");
                setConnection(null);
              }}
            >
              <option value="FROM">출발지로 선택</option>
              <option value="TO">도착지로 선택</option>
            </select>
          </label>
          {originSearch.error && (
            <p role="alert" className="error-text">
              {originSearch.error.message}
            </p>
          )}
          {originSearch.isSuccess && !origins.length && (
            <p role="status" className="small muted">
              찾은 장소가 없어요. 역 이름이나 건물 이름으로 검색해 주세요.
            </p>
          )}
          <div className="origin-results">
            {origins.map((p) => (
              <button
                key={p.id}
                disabled={route.isPending}
                onClick={() => {
                  if (searchSide === "FROM") setFrom("ORIGIN:" + p.id);
                  else setTo("ORIGIN:" + p.id);
                  if (connection)
                    setConnection({ ...connection, originId: p.id });
                  reset();
                }}
                aria-pressed={
                  (searchSide === "FROM" ? from : to) === "ORIGIN:" + p.id
                }
              >
                {p.label}
              </button>
            ))}
          </div>
        </details>
      </div>
      {route.error && (
        <p role="alert" className="transport-feedback error-text">
          {route.error.message}
        </p>
      )}
      {data && (
        <div className="transport-results" aria-live="polite">
          <p className="transport-endpoints">
            {data.from.label}
            <span>→</span>
            {data.to.label}
          </p>
          {!data.options.length && (
            <p className="transport-feedback">
              조회 가능한 경로가 없어요. 장거리·섬 이동은 공식 예매처에서 확인해
              주세요.
            </p>
          )}
          {data.options.map((option, index) => {
            const active = selected?.option.id === option.id;
            // 다른 구간으로 바꾼 결과에 이전 예약의 여유 계산이 남지 않도록 양쪽 ID를 확인합니다.
            const connected =
              connection?.originId &&
              (connection.side === "DEPARTURE"
                ? data.to.id === connection.originId &&
                  data.from.id === connection.itemId
                : data.from.id === connection.originId &&
                  data.to.id === connection.itemId);
            const impact = connected
              ? bookingConnectionImpact(trip, connection!, option.minutes)
              : null;
            return (
              <article
                key={option.id}
                className={"transport-option " + (active ? "active" : "")}
              >
                <div className="transport-option-top">
                  <span className="badge subtle">
                    경로 {index + 1}
                    {index === 0 ? " · 조회 중 최단 시간" : ""}
                  </span>
                  <strong>{option.minutes}분</strong>
                </div>
                <p className="transport-metrics">
                  {(option.distanceMeters / 1000).toFixed(1)}km
                  {option.transfers !== null &&
                    " · 환승 " + option.transfers + "회"}
                  {option.walkingMinutes !== null &&
                    mode === "TRANSIT" &&
                    " · 도보 약 " + option.walkingMinutes + "분"}
                </p>
                {impact && (
                  <div
                    className={
                      "booking-route-impact " +
                      (impact.slack < 0 ? "tight" : "")
                    }
                  >
                    <strong>
                      {impact.slack < 0
                        ? `선택한 구간에서 ${Math.abs(impact.slack)}분 부족`
                        : `선택한 구간에서 ${impact.slack}분 여유`}
                    </strong>
                    <p>
                      {connection!.side === "DEPARTURE"
                        ? "역 도착 예상"
                        : "방문지 도착 예상"}{" "}
                      {scheduleTime(impact.earliest)}
                      <br />
                      도착 기준 {scheduleTime(impact.deadline)}
                    </p>
                    <p className="small muted">
                      선택한 방문과 예약 사이만 비교해요. 조회 이동 시간과
                      입력한{" "}
                      {connection!.side === "DEPARTURE" ? "탑승 전" : "도착 후"}{" "}
                      여유를 사용했어요. 실제 운행·휴식·위치 일치 여부는 직접
                      확인해 주세요. 일정은 바뀌지 않아요.
                    </p>
                  </div>
                )}
                <p className="transport-fare">
                  {mode === "TRANSIT"
                    ? option.fare === null
                      ? "요금 미제공"
                      : money(option.fare) + " · 1인 조회요금"
                    : mode === "CAR"
                      ? option.toll === null
                        ? "통행료 미제공"
                        : "통행료 " + money(option.toll)
                      : "도보 경로"}
                </p>
                {/* 버튼별 여백 대신 공통 격자로 폭·간격을 맞춰 모바일에서도 어긋나지 않게 합니다. */}
                <div className="transport-option-actions">
                  <button
                    className="button secondary"
                    aria-pressed={active}
                    onClick={() => {
                      onSelect({ result: data, option });
                      document
                        .getElementById("trip-route-map")
                        ?.scrollIntoView({
                          behavior: "smooth",
                          block: "start",
                        });
                    }}
                  >
                    {active ? "지도에 표시 중" : "이 경로 지도에서 보기"}
                  </button>
                  {!connection && (
                    <button
                      className="button primary"
                      disabled={!!timingReason}
                      aria-describedby={timingHintId}
                      onClick={() => setComparing({ result: data, option })}
                    >
                      일정 영향 비교
                    </button>
                  )}
                </div>
                {!!option.steps.length && (
                  <details className="route-steps">
                    <summary>이동 순서 {option.steps.length}단계</summary>
                    <ol>
                      {option.steps.map((step, n) => (
                        <li key={n}>
                          <span>
                            {step.type === "WALKING"
                              ? "도보"
                              : step.type === "BUS"
                                ? "버스"
                                : step.type === "SUBWAY"
                                  ? "지하철"
                                  : "이동"}{" "}
                            · {step.minutes}분
                          </span>
                          <p>
                            {step.guidance ||
                              step.vehicles.join(", ") ||
                              "세부 이동 안내 확인 필요"}
                          </p>
                        </li>
                      ))}
                    </ol>
                  </details>
                )}
              </article>
            );
          })}
          <p className="small muted">
            {new Date(data.queriedAt).toLocaleTimeString("ko-KR", {
              hour: "2-digit",
              minute: "2-digit",
            })}{" "}
            조회 ·{" "}
            {data.source === "KAKAO_MOBILITY" ? "카카오모빌리티" : "카카오맵"}
          </p>
          <a
            className="text-button"
            href={data.externalUrl}
            target="_blank"
            rel="noreferrer"
          >
            카카오맵 길찾기에서 확인
            <ArrowUpRight size={14} />
          </a>
        </div>
      )}
      <p className="transport-disclaimer small muted">
        조회 시점의 예상 소요 시간이에요. 여행 날짜의 운행·좌석을 확인한 결과는
        아니며 일정 시간은 자동 변경되지 않아요.{" "}
        {mode === "CAR"
          ? "통행료는 기본 승용차 1대 기준이며 유류비·주차비는 제외돼요."
          : mode === "TRANSIT"
            ? "인원별 할인·결제 방식에 따라 요금이 달라질 수 있어요."
            : "보행 환경과 출입 가능 여부는 현장에서 확인해 주세요."}
      </p>
      <details className="transport-booking">
        <summary>열차·버스 예매하러 가기</summary>
        <p className="small muted">
          날짜와 출발·도착역은 예매처에서 선택해 주세요. 좌석·결제 결과는 자동
          연동되지 않아요.
        </p>
        <div className="transport-links">
          {[
            { name: "코레일 · 열차", url: "https://www.korail.com/" },
            { name: "SRT · 열차", url: "https://etk.srail.kr/" },
            { name: "고속버스", url: "https://www.kobus.co.kr/" },
            { name: "시외버스", url: "https://txbus.t-money.co.kr/" },
          ].map((l) => (
            <a href={l.url} target="_blank" rel="noreferrer" key={l.name}>
              {l.name}
              <ArrowUpRight size={14} />
            </a>
          ))}
        </div>
        <p className="small muted">
          예약 성공·매진·취소 결과는 ‘예약’ 탭에 직접 기록해 주세요.
        </p>
      </details>
      {comparing && (
        <RouteTimingModal
          trip={trip}
          route={comparing}
          lodging={from.startsWith("LODGING:")}
          close={closeComparison}
          refresh={refresh}
          onAppliedDay={onDayChange}
        />
      )}
    </section>
  );
}
