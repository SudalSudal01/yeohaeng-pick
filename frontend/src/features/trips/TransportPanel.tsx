import { useCallback, useState } from "react";
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

/** 조회 버튼을 눌렀을 때만 공급자를 호출합니다. 경로 선택은 지도 표시만 바꾸며 예약/일정은 수정하지 않습니다. */
export default function TransportPanel({
  trip,
  day,
  selected,
  onSelect,
  refresh,
}: {
  trip: Trip;
  day: number;
  selected: SelectedRoute | null;
  onSelect: (route: SelectedRoute | null) => void;
  refresh: () => Promise<void>;
}) {
  const dayItems = trip.items.filter((i) => i.dayIndex === day);
  const [from, setFrom] = useState(dayItems[0] ? "ITEM:" + dayItems[0].id : "");
  const [to, setTo] = useState(dayItems[1] ? "ITEM:" + dayItems[1].id : "");
  const [mode, setMode] = useState<Conditions["transport"]>(trip.transport);
  const [originQuery, setOriginQuery] = useState(trip.origin);
  const [origins, setOrigins] = useState<TransportPoint[]>([]);
  const [comparing, setComparing] = useState<SelectedRoute | null>(null);
  const closeComparison = useCallback(() => setComparing(null), []);
  const timingReason = routeTimingEligibility(trip, from, to);
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
        <optgroup label="찾은 출발지">
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
        <label>
          출발지
          <select
            value={from}
            disabled={route.isPending || originSearch.isPending}
            onChange={(e) => {
              setFrom(e.target.value);
              reset();
            }}
          >
            {options}
          </select>
        </label>
        <label>
          도착지
          <select
            value={to}
            disabled={route.isPending || originSearch.isPending}
            onChange={(e) => {
              setTo(e.target.value);
              reset();
            }}
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
        <details className="origin-search">
          <summary>역·터미널 등 다른 출발지 찾기</summary>
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
              출발지 검색어
              <input
                value={originQuery}
                maxLength={100}
                placeholder="예: 서울역"
                disabled={originSearch.isPending || route.isPending}
                onChange={(e) => setOriginQuery(e.target.value)}
              />
            </label>
            <button
              className="button secondary"
              disabled={
                !originQuery.trim() || originSearch.isPending || route.isPending
              }
            >
              {originSearch.isPending ? "검색 중…" : "출발지 검색"}
            </button>
          </form>
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
                  setFrom("ORIGIN:" + p.id);
                  reset();
                }}
                aria-pressed={from === "ORIGIN:" + p.id}
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
                <button
                  className="button secondary"
                  aria-pressed={active}
                  onClick={() => {
                    onSelect({ result: data, option });
                    document
                      .getElementById("trip-route-map")
                      ?.scrollIntoView({ behavior: "smooth", block: "start" });
                  }}
                >
                  {active ? "지도에 표시 중" : "이 경로 지도에서 보기"}
                </button>
                <button
                  className="button primary"
                  disabled={!!timingReason}
                  onClick={() => setComparing({ result: data, option })}
                >
                  일정 영향 비교
                </button>
                {timingReason && <p className="small muted">{timingReason}</p>}
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
        />
      )}
    </section>
  );
}
