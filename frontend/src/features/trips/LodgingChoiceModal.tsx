import { useCallback, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ArrowUpRight, Check, Hotel, Search } from "lucide-react";
import { api, money } from "../../shared/api";
import type { LodgingChoicePreview, Place, Trip } from "../../shared/types";
import { ErrorState, Loading, Modal } from "../../shared/ui";
import { useToast } from "../../shared/providers";
import { lodgingChoiceBudget, lodgingRegions } from "./lodgingChoice";
import { lodgingBudget } from "./tripBudget";
import { useFavorites } from "../favorites/useFavorites";
import FavoriteLodgingCard from "../planner/FavoriteLodgingCard";

/** 열린 여행의 version을 고정합니다. 동행이 편집하면 오래된 비교를 저장하지 않고 창을 다시 열도록 안내합니다. */
export default function LodgingChoiceModal({
  trip,
  initialRegion,
  refresh,
  close,
}: {
  trip: Trip;
  initialRegion: string;
  refresh: () => Promise<void>;
  close: () => void;
}) {
  const regions = lodgingRegions(trip);
  const [region, setRegion] = useState(initialRegion || regions[0] || "");
  const [query, setQuery] = useState("");
  const [search, setSearch] = useState("");
  const [chosen, setChosen] = useState("");
  const [fromFavorite, setFromFavorite] = useState(false);
  const [source, setSource] = useState<"SEARCH" | "FAVORITES">("SEARCH");
  const [showSaved, setShowSaved] = useState(false);
  const favorites = useFavorites();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const toast = useToast();
  const onClose = useCallback(() => {
    if (!busy) close();
  }, [busy, close]);
  const current = trip.lodgings.find((h) => h.region === region);
  const savedHotels =
    favorites.data?.filter(
      (f) =>
        f.place.region === region &&
        f.place.category === "숙소" &&
        f.place.id !== current?.placeId,
    ) || [];
  const base = `/trips/${trip.id}/lodgings`;
  const body = {
    region,
    placeId: chosen,
    query: search,
    version: trip.version,
    favorite: fromFavorite,
  };
  const candidates = useQuery({
    queryKey: ["lodging-candidates", trip.id, trip.version, region, search],
    queryFn: () =>
      api<Place[]>(
        `${base}/candidates?region=${encodeURIComponent(region)}&query=${encodeURIComponent(search)}`,
      ),
    enabled: !!region && source === "SEARCH",
    retry: false,
  });
  const comparison = useQuery({
    queryKey: [
      "lodging-choice",
      trip.id,
      trip.version,
      region,
      search,
      chosen,
      fromFavorite,
    ],
    queryFn: () =>
      api<LodgingChoicePreview>(base + "/preview", { method: "POST", body }),
    enabled: !!chosen,
    retry: false,
  });
  const result = comparison.error ? undefined : comparison.data;
  const beforeBudget = lodgingBudget(trip);
  const afterBudget = result
    ? lodgingChoiceBudget(trip, result.candidate)
    : null;
  const activities = trip.items.reduce(
    (sum, i) => sum + (i.visitCost ?? i.cost) * trip.partySize,
    0,
  );
  const dirty = query.trim() !== search;
  async function apply() {
    setBusy(true);
    setError(null);
    try {
      await api(base + "/apply", { method: "POST", body });
      await refresh();
      toast("숙소를 반영했어요. 숙박 조건과 실제 이동 경로를 확인해 주세요.");
      close();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal
      title="숙소 후보 비교하기"
      onClose={onClose}
      className="lodging-choice-modal"
    >
      <p className="muted">
        숙박할 지역의 후보를 고르고 일정 앞뒤 이동을 비교해요. 객실
        재고·가격·예약 가능 여부는 예약처에서 확인해 주세요.
      </p>
      <div
        className="lodging-choice-sources"
        role="group"
        aria-label="숙소 후보 찾는 방법"
      >
        {(["SEARCH", "FAVORITES"] as const).map((value) => (
          <button
            type="button"
            className={"button " + (source === value ? "primary" : "secondary")}
            key={value}
            aria-pressed={source === value}
            disabled={busy}
            onClick={() => {
              setSource(value);
              setChosen("");
              setFromFavorite(false);
              setError(null);
              setQuery("");
              setSearch("");
            }}
          >
            {value === "SEARCH" ? "일반 후보 검색" : "내 찜 숙소"}
          </button>
        ))}
      </div>
      {source === "FAVORITES" && (
        <label className="lodging-choice-favorite-region">
          숙박할 지역
          <select
            value={region}
            disabled={busy}
            onChange={(e) => {
              setRegion(e.target.value);
              setChosen("");
              setFromFavorite(false);
              setError(null);
            }}
          >
            {regions.map((r) => (
              <option key={r}>{r}</option>
            ))}
          </select>
        </label>
      )}
      {source === "SEARCH" && (
        <form
          className="lodging-choice-search"
          onSubmit={(e) => {
            e.preventDefault();
            setChosen("");
            setFromFavorite(false);
            setError(null);
            setSearch(query.trim());
            if (query.trim() === search) void candidates.refetch();
          }}
        >
          <label>
            숙박할 지역
            <select
              value={region}
              disabled={busy}
              onChange={(e) => {
                setRegion(e.target.value);
                setQuery("");
                setSearch("");
                setChosen("");
                setFromFavorite(false);
                setError(null);
              }}
            >
              {regions.map((r) => (
                <option key={r}>{r}</option>
              ))}
            </select>
          </label>
          <label>
            숙소 이름·지역 내 검색어
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              disabled={busy}
              maxLength={100}
              placeholder="예: 광화문 호텔"
            />
          </label>
          <button
            className="button secondary"
            disabled={busy || candidates.isFetching}
          >
            <Search size={17} /> 후보 검색
          </button>
        </form>
      )}
      <div className="lodging-choice-current">
        <span className="small muted">현재 {region} 숙소</span>
        <strong>{current?.name ?? "아직 정하지 않았어요"}</strong>
      </div>
      {source === "FAVORITES" && (
        <section
          className="favorite-lodging-section"
          aria-label="내 찜 숙소 비교"
        >
          <h3>내 찜에서 숙소 비교하기</h3>
          <p>
            찜한 숙소의 현재 정보를 확인하고 선택하면 앞뒤 이동과 참고 예산을
            비교해요. 내 찜 목록은 동행에게 공개하지 않으며, 적용한 숙소는 공동
            일정에 보여요.
          </p>
          {favorites.isPending ? (
            <Loading text="내 찜 숙소를 불러와요" />
          ) : favorites.error ? (
            <ErrorState
              error={favorites.error}
              retry={() => favorites.refetch()}
            />
          ) : (
            <div className="favorite-lodging-list">
              {savedHotels
                .filter((f, i) => showSaved || i < 4 || chosen === f.place.id)
                .map((f) => (
                  <FavoriteLodgingCard
                    key={f.place.id}
                    saved={f}
                    selected={fromFavorite && chosen === f.place.id}
                    suggested={false}
                    purpose="COMPARE"
                    disabled={busy}
                    onChoose={(p) => {
                      // 조회한 개인 숙소를 비교 대상으로만 지정합니다. 아래 영향 비교에서 별도로 적용해야 여행이 바뀝니다.
                      setChosen(fromFavorite && chosen === p.id ? "" : p.id);
                      setFromFavorite(true);
                      setSearch("");
                      setQuery("");
                      setError(null);
                    }}
                  />
                ))}
            </div>
          )}
          {savedHotels.length > 4 && (
            <button
              type="button"
              className="text-button lodging-show-more"
              disabled={busy}
              onClick={() => setShowSaved((v) => !v)}
            >
              {showSaved
                ? "찜 숙소 접기"
                : `찜 숙소 ${savedHotels.length}곳 모두 보기`}
            </button>
          )}
          {favorites.data && !savedHotels.length && (
            <p>
              이번 지역에 비교할 다른 찜 숙소가 없어요. ‘일반 후보 검색’으로
              찾아보세요.
            </p>
          )}
        </section>
      )}
      {source === "SEARCH" && (
        <>
          <h3>일반 숙소 후보</h3>
          <p className="small muted">
            일정상 숙박일의 마지막 방문과 다음 날 첫 방문까지의 추정 이동이 짧은
            순서예요. 현재 숙소는 후보에서 제외해요.
          </p>
          {dirty && (
            <p className="notice">
              검색어가 바뀌었어요. ‘후보 검색’을 눌러 새 결과를 확인해 주세요.
            </p>
          )}
          {candidates.isFetching ? (
            <Loading text="숙소 후보를 검색하고 있어요" />
          ) : candidates.error ? (
            <ErrorState
              error={candidates.error}
              retry={() => void candidates.refetch()}
            />
          ) : !candidates.data?.length ? (
            <p className="notice">
              다른 후보가 없어요. 숙소 이름이나 지역 내 검색어를 바꿔 다시
              검색해 주세요.
            </p>
          ) : (
            <div
              className="lodging-choice-candidates"
              role="group"
              aria-label="비교할 숙소 후보"
            >
              {candidates.data.map((p) => (
                <button
                  type="button"
                  className={
                    "lodging-choice-candidate " +
                    (chosen === p.id ? "selected" : "")
                  }
                  key={p.id}
                  aria-pressed={chosen === p.id}
                  disabled={busy || dirty}
                  onClick={() => {
                    setChosen(p.id);
                    setFromFavorite(false);
                    setError(null);
                  }}
                >
                  <Hotel size={19} />
                  <span>
                    <strong>{p.name}</strong>
                    <span>
                      {p.source === "SAMPLE"
                        ? `${money(p.cost)} / 1객실 1박 · 예시`
                        : "객실 가격·재고 확인 필요"}
                    </span>
                  </span>
                  {chosen === p.id && <Check size={19} />}
                </button>
              ))}
            </div>
          )}
        </>
      )}
      {comparison.isFetching ? (
        <Loading text="숙소 변경의 영향을 비교하고 있어요" />
      ) : comparison.error ? (
        <ErrorState error={comparison.error} />
      ) : (
        result &&
        afterBudget && (
          <section
            className="lodging-choice-result"
            aria-label="숙소 변경 영향"
          >
            <h3>{current ? "이 숙소로 바꾸면" : "이 숙소를 추가하면"}</h3>
            <p className="lodging-choice-selection">
              <span>현재: {result.currentName ?? "숙소 미정"}</span>
              <strong>선택: {result.candidate.name}</strong>
            </p>
            <div className="lodging-choice-stats">
              <div>
                <span>숙소 앞뒤 이동 합계 · 추정</span>
                <strong>
                  {result.beforeMinutes == null
                    ? "숙소 미정"
                    : `${result.beforeMinutes}분`}{" "}
                  → {result.afterMinutes}분
                </strong>
                <p>
                  {result.minutesDelta == null
                    ? "새로 선택할 숙소 기준의 이동 부담이에요."
                    : result.minutesDelta === 0
                      ? "추정 이동 합계가 같아요."
                      : `추정 이동이 ${Math.abs(result.minutesDelta)}분 ${result.minutesDelta < 0 ? "줄어요" : "늘어요"}.`}
                </p>
              </div>
              <div>
                <span>활동비 + 숙박비 참고 합계</span>
                <strong>
                  {money(activities + afterBudget.total)}
                  {afterBudget.unpriced > 0 && " + 미확인 비용"}
                </strong>
                <p>
                  현재 참고 합계는 {money(activities + beforeBudget.total)}
                  입니다.
                  {beforeBudget.unpriced > 0 &&
                    ` 현재도 가격 미확인 숙소 ${beforeBudget.unpriced}곳이 제외돼 있어요.`}{" "}
                  변경 후 가격 미확인 숙소 {afterBudget.unpriced}곳은
                  제외했어요. 교통비·미확인 장소 비용도 제외예요.
                  {activities + afterBudget.total > trip.budget
                    ? " 현재 합산 금액은 예산을 초과해요."
                    : ""}
                </p>
              </div>
            </div>
            <p className="small muted">
              두 숙소를 같은 좌표 기반 방식으로 비교해요. 실제 조회 시간·교통편
              운행·체크인/아웃 조건을 반영한 결과가 아니에요. 숙박일은 해당
              날짜의 마지막 방문 지역으로 가정해요.
            </p>
            <ul className="lodging-choice-legs">
              {result.legs.map((leg) => (
                <li key={`${leg.dayIndex}:${leg.direction}`}>
                  <span>
                    DAY {leg.dayIndex + 1} ·{" "}
                    {leg.direction === "INBOUND"
                      ? `${leg.placeName} → 숙소`
                      : `숙소 → ${leg.placeName}`}
                  </span>
                  <strong>
                    {leg.beforeMinutes == null
                      ? "미정"
                      : `${leg.beforeMinutes}분`}{" "}
                    → {leg.afterMinutes}분
                  </strong>
                </li>
              ))}
            </ul>
            <div className="notice">
              <div>
                <strong>적용 전에 확인해 주세요</strong>
                <p>
                  방문 시간·고정 방문·교통 예약은 유지돼요. 새 숙소는 ‘예약
                  전’으로 저장돼요.
                </p>
                {current && (
                  <p>
                    이전 숙소의 숙박 날짜·인원·객실·시각·총액은 새 숙소로 옮기지
                    않아요.
                    {result.resetsStay
                      ? " 입력한 숙박 조건을 다시 확인해 주세요."
                      : ""}
                  </p>
                )}
                <p>
                  기존 숙소 관련 반영 경로 {result.clearedRouteCount}개를
                  해제해요. 방문 시간은 유지되므로 새 숙소의 실제 경로를 다시
                  비교해 주세요.
                </p>
                <p>
                  가격 미확인을 저렴해진 것으로 판단하지 마세요. 외부 예약
                  변경·취소는 예약처에서 별도로 진행해야 해요.
                </p>
              </div>
            </div>
            <a
              className="text-button"
              href={result.candidate.url}
              target="_blank"
              rel="noreferrer"
            >
              후보 위치·리뷰·예약 안내
              <ArrowUpRight size={16} />
            </a>
            {result.conflicts.map((c) => (
              <p className="field-error" key={c} role="alert">
                {c}
              </p>
            ))}
            {error != null && <ErrorState error={error} />}
            <div className="lodging-choice-actions">
              <button
                className="button primary full"
                disabled={
                  busy || dirty || comparison.isFetching || !result.canApply
                }
                onClick={() => void apply()}
              >
                <Check size={17} />
                {busy
                  ? "숙소를 저장하고 있어요"
                  : current
                    ? "이 숙소로 변경하기"
                    : "이 숙소를 일정에 추가하기"}
              </button>
            </div>
          </section>
        )
      )}
    </Modal>
  );
}
