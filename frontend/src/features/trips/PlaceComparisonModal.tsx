import { useCallback, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Heart, Loader2, LockKeyhole, MapPin } from "lucide-react";
import { ApiError, api, activityPrice, money } from "../../shared/api";
import type { Comparison, Item, Place, Trip } from "../../shared/types";
import { useToast } from "../../shared/providers";
import { ErrorState, Loading, Modal } from "../../shared/ui";
import { useFavorites } from "../favorites/useFavorites";
import { lodgingBudget } from "./tripBudget";
import {
  favoriteVisitCandidates,
  visibleFavoriteVisitCandidates,
} from "./visitComparisonCandidates";

type CandidateSource = "GENERAL" | "FAVORITES";

/**
 * 저장된 일정의 한 방문을 바꾸기 전에 전체 시간·활동비 영향을 비교합니다.
 * 개인 찜은 보관 스냅샷을 후보 탐색에만 사용하고, 미리보기와 적용 요청마다 서버가 현재 장소 정보를 다시 검증합니다.
 */
export default function PlaceComparisonModal({
  trip,
  item,
  rain,
  close,
  applied,
}: {
  trip: Trip;
  item: Item;
  rain: boolean;
  close: () => void;
  applied: () => void;
}) {
  const [source, setSource] = useState<CandidateSource>("GENERAL");
  const [chosen, setChosen] = useState("");
  const [fromFavorite, setFromFavorite] = useState(false);
  const [showAllFavorites, setShowAllFavorites] = useState(false);
  const [busy, setBusy] = useState(false);
  const [applyError, setApplyError] = useState<unknown>(null);
  const [applyInvalidated, setApplyInvalidated] = useState(false);
  // 모달을 연 순간의 버전을 고정해 동행이 나중에 바꾼 일정을 오래된 비교 결과로 덮지 않습니다.
  const [version] = useState(trip.version);
  const toast = useToast();
  const client = useQueryClient();
  const favorites = useFavorites();
  const busyRef = useRef(false);
  const onClose = useCallback(() => {
    // 요청 중 닫기 동작만 막되 콜백은 고정합니다. busy 변화마다 네이티브 dialog가 닫혔다 다시 열리는 현상을 피합니다.
    if (!busyRef.current) close();
  }, [close]);

  const savedCandidates = favoriteVisitCandidates(
    favorites.data ?? [],
    trip,
    item,
    rain,
  );
  const visibleSavedCandidates = visibleFavoriteVisitCandidates(
    savedCandidates,
    showAllFavorites,
    fromFavorite ? chosen : "",
  );
  const alternatives = useQuery({
    queryKey: ["alternatives", trip.id, item.id, rain, version],
    queryFn: () =>
      api<Place[]>(`/trips/${trip.id}/alternatives/${item.id}?rain=${rain}`),
    enabled: source === "GENERAL",
    retry: false,
  });
  const body = {
    itemId: item.id,
    placeId: chosen,
    version,
    favorite: fromFavorite,
    rain,
  };
  const comparison = useQuery({
    queryKey: [
      "comparison",
      trip.id,
      item.id,
      chosen,
      version,
      fromFavorite,
      rain,
    ],
    enabled: !!chosen,
    queryFn: () =>
      api<Comparison>(`/trips/${trip.id}/preview`, {
        method: "POST",
        body,
      }),
    retry: false,
  });
  // 새 미리보기가 실패하면 React Query에 남은 직전 성공 데이터를 적용 가능한 결과처럼 보여 주지 않습니다.
  const result =
    comparison.error || applyInvalidated ? undefined : comparison.data;
  const refreshedItem = result?.items.find(
    (candidate) => candidate.id === item.id,
  );

  function choose(placeId: string, favorite: boolean) {
    setChosen(placeId);
    setFromFavorite(favorite);
    setApplyError(null);
    setApplyInvalidated(false);
  }

  function changeSource(next: CandidateSource) {
    setSource(next);
    setChosen("");
    setFromFavorite(false);
    setApplyError(null);
    setApplyInvalidated(false);
  }

  async function apply() {
    if (!result?.canApply) return;
    busyRef.current = true;
    setBusy(true);
    setApplyError(null);
    try {
      await api(`/trips/${trip.id}/apply`, { method: "POST", body });
      toast("변경된 동선과 비용을 일정에 반영했어요.");
      applied();
    } catch (error) {
      // 적용 오류를 모달 안에 남겨 사용자가 맥락과 해결 방법을 함께 읽을 수 있게 합니다.
      setApplyError(error);
      // 적용 요청이 실패하면 공급자 상태나 공동 일정이 비교 시점과 달라졌을 수 있으므로 성공했던 결과도 다시 쓰지 않습니다.
      setApplyInvalidated(true);
      void client.invalidateQueries({
        queryKey: [
          "comparison",
          trip.id,
          item.id,
          chosen,
          version,
          fromFavorite,
          rain,
        ],
        exact: true,
      });
      void client.invalidateQueries({ queryKey: ["trip", trip.id] });
      void client.invalidateQueries({ queryKey: ["trips"] });
      void favorites.refresh();
      if (error instanceof ApiError && error.status === 409) {
        // 409는 동행 수정이나 찜 해제처럼 사용자가 최신 목록에서 다시 시작해야 하는 상태입니다.
        toast("최신 여행과 찜 목록을 다시 불러왔어요.", true);
      }
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }

  return (
    <Modal
      title={rain ? "비 오는 날, 실내 후보로 바꿔요" : "선택하기 전에 비교해요"}
      onClose={onClose}
      className="place-comparison-modal"
    >
      <div className="comparison-current">
        <span>지금 선택한 장소</span>
        <strong>{item.placeName}</strong>
        <p>
          {activityPrice(item)} · {item.duration}분
        </p>
      </div>
      <p className="muted place-comparison-intro">
        일반 후보 또는 내 찜에서 한 곳을 고르면 전체 일정의 시간과 활동비 변화를
        먼저 계산해요.
      </p>
      <div
        className="place-comparison-sources"
        role="group"
        aria-label="방문 장소 후보 찾는 방법"
      >
        <button
          type="button"
          className={`button ${source === "GENERAL" ? "primary" : "secondary"}`}
          aria-pressed={source === "GENERAL"}
          disabled={busy}
          onClick={() => changeSource("GENERAL")}
        >
          일반 후보
        </button>
        <button
          type="button"
          className={`button ${source === "FAVORITES" ? "primary" : "secondary"}`}
          aria-pressed={source === "FAVORITES"}
          disabled={busy}
          onClick={() => changeSource("FAVORITES")}
        >
          내 찜 장소
        </button>
      </div>

      {source === "GENERAL" ? (
        <section
          className="place-comparison-candidates"
          aria-labelledby="general-candidate-title"
        >
          <h3 id="general-candidate-title">일반 장소 후보</h3>
          <p className="small muted">
            현재 일정과 같은 지역에서 아직 쓰지 않은 후보예요. 실제 운영·비용
            정보는 장소 연결 화면에서 다시 확인해 주세요.
          </p>
          {alternatives.isFetching ? (
            <Loading text="일반 후보를 불러오고 있어요" />
          ) : alternatives.error ? (
            <ErrorState
              error={alternatives.error}
              retry={() => void alternatives.refetch()}
            />
          ) : !alternatives.data?.length ? (
            <p className="notice" role="status">
              현재 조건에 맞는 다른 후보가 없어요.
            </p>
          ) : (
            <div
              className="alternative-list"
              role="group"
              aria-label="일반 장소 후보"
            >
              {alternatives.data.map((place) => (
                <button
                  type="button"
                  className={`alternative ${!fromFavorite && chosen === place.id ? "selected" : ""}`}
                  key={place.id}
                  aria-pressed={!fromFavorite && chosen === place.id}
                  disabled={busy}
                  onClick={() => choose(place.id, false)}
                >
                  <span className="green-icon" aria-hidden="true">
                    <MapPin size={21} />
                  </span>
                  <span className="alternative-copy">
                    <strong>{place.name}</strong>
                    <span>
                      {place.category} · {place.indoor ? "실내" : "야외"} ·{" "}
                      {activityPrice(place)}
                    </span>
                  </span>
                  {!fromFavorite && chosen === place.id && (
                    <Check size={19} aria-hidden="true" />
                  )}
                </button>
              ))}
            </div>
          )}
        </section>
      ) : (
        <section
          className="place-comparison-candidates"
          aria-labelledby="favorite-candidate-title"
        >
          <h3 id="favorite-candidate-title">내 찜에서 비교하기</h3>
          <p className="favorite-visit-guide">
            카드는 찜한 때의 이름·분류·실내 여부를 보여 줘요. 후보를 선택하면
            서버가 현재 장소 정보를 다시 조회한 뒤 일정 영향을 계산해요. 내 찜
            목록과 보관 시각은 동행에게 공개하지 않아요.
          </p>
          {rain && (
            <p className="notice" role="status">
              비 오는 날 비교이므로 보관 당시 실내였던 찜만 보여 줘요. 현재
              정보가 야외로 바뀌었다면 서버가 적용을 막아요.
            </p>
          )}
          {favorites.isPending ? (
            <Loading text="내 찜 장소를 불러오고 있어요" />
          ) : favorites.error ? (
            <ErrorState
              error={favorites.error}
              retry={() => void favorites.refetch()}
            />
          ) : !savedCandidates.length ? (
            <p className="notice" role="status">
              {item.region}에서 비교할 수 있는 다른 찜 장소가 없어요. ‘일반
              후보’에서 찾아보세요.
            </p>
          ) : (
            <>
              <div
                className="favorite-visit-list"
                role="group"
                aria-label="내 찜 장소 후보"
              >
                {visibleSavedCandidates.map(({ place, savedAt }) => {
                  const selected = fromFavorite && chosen === place.id;
                  return (
                    <button
                      type="button"
                      className={`favorite-visit-candidate ${selected ? "selected" : ""}`}
                      key={`${place.id}:${savedAt}`}
                      aria-pressed={selected}
                      disabled={busy}
                      onClick={() => choose(place.id, true)}
                    >
                      <span className="favorite-visit-label">
                        <Heart size={15} aria-hidden="true" />내 찜 · 보관 당시
                      </span>
                      <strong>{place.name}</strong>
                      <span>
                        {place.category} · {place.indoor ? "실내" : "야외"} ·{" "}
                        {activityPrice(place)}
                      </span>
                      <small>{savedAt.slice(0, 10)} 보관</small>
                      <span className="favorite-visit-select">
                        {selected ? (
                          <Check size={16} aria-hidden="true" />
                        ) : null}
                        {selected
                          ? "비교 후보로 선택됨 · 아직 적용 전"
                          : "이 장소 영향 비교하기"}
                      </span>
                    </button>
                  );
                })}
              </div>
              {savedCandidates.length > 4 && (
                <button
                  type="button"
                  className="text-button favorite-visit-more"
                  disabled={busy}
                  onClick={() => setShowAllFavorites((value) => !value)}
                >
                  {showAllFavorites
                    ? "찜 장소 접기"
                    : `찜 장소 ${savedCandidates.length}곳 모두 보기`}
                </button>
              )}
            </>
          )}
        </section>
      )}

      {comparison.isFetching ? (
        <Loading text="전체 일정에 미치는 영향을 계산해요" />
      ) : comparison.error ? (
        <ErrorState
          error={comparison.error}
          retry={() => void comparison.refetch()}
        />
      ) : (
        result && (
          <section
            className="comparison-result"
            aria-label="장소 변경 영향 비교"
          >
            <span className="eyebrow">WHAT CHANGES</span>
            <h3>{result.summary}</h3>
            {fromFavorite && refreshedItem && (
              <div className="favorite-visit-refreshed" role="status">
                <strong>서버에서 다시 확인한 후보</strong>
                <p>
                  {refreshedItem.placeName} · {refreshedItem.category} ·{" "}
                  {refreshedItem.indoor ? "실내" : "야외"} ·{" "}
                  {activityPrice(refreshedItem)}
                </p>
                <span>
                  위 현재 정보로 계산했어요. 보관 당시 카드와 다르면 이 내용을
                  기준으로 판단해 주세요.
                </span>
              </div>
            )}
            <div className="comparison-stats">
              <div>
                <span>전체 인원 활동비 변화</span>
                <strong>
                  {result.costDelta > 0 ? "+" : ""}
                  {money(result.costDelta)}
                </strong>
              </div>
              <div>
                <span>일정 내 이동 변화 · 추정</span>
                <strong>
                  {result.travelDelta > 0 ? "+" : ""}
                  {result.travelDelta}분
                </strong>
              </div>
            </div>
            <p>
              {result.costDelta > 0
                ? "추가 비용이 생겨요. 장소의 실내 여부·체류 시간과 함께 비교해 주세요."
                : "활동비를 유지하거나 줄일 수 있어요."}
            </p>
            <p>
              변경 후 활동비 + 숙박비 참고 합계{" "}
              {money(result.estimatedCost + lodgingBudget(trip).total)}
              {result.estimatedCost + lodgingBudget(trip).total > trip.budget
                ? " · 설정한 예산을 초과해요."
                : " · 현재 합산 금액은 예산 이내예요."}{" "}
              숙박비는 저장한 총 숙박비와 예시 금액을 사용해요. 미확인
              숙박비·장소 비용, 교통비와 미선택 숙소 비용은 포함하지 않았어요.
            </p>
            <p className="small muted">
              바꾸는 장소의 방문 조건·직접 입력 비용은 초기화해요. 유지하는
              장소의 확인 조건은 그대로 지켜요.
            </p>
            {result.conflicts.map((conflict) => (
              <p className="field-error" key={conflict} role="alert">
                {conflict}
              </p>
            ))}
            <div className="changed-times">
              {result.items
                .filter((candidate) => candidate.dayIndex === item.dayIndex)
                .map((candidate) => (
                  <p key={candidate.id}>
                    <span>{candidate.startTime}</span>
                    {candidate.placeName}
                    {candidate.fixed && (
                      <LockKeyhole size={12} aria-label="고정된 방문" />
                    )}
                  </p>
                ))}
            </div>
            <div className="place-comparison-actions">
              <button
                type="button"
                className="button primary full"
                disabled={busy || !result.canApply}
                onClick={() => void apply()}
              >
                {busy ? (
                  <Loader2 className="spin" size={17} aria-hidden="true" />
                ) : (
                  <Check size={17} aria-hidden="true" />
                )}
                {busy ? "일정에 반영하고 있어요" : "변경 내용 적용하기"}
              </button>
            </div>
          </section>
        )
      )}
      {applyError != null && (
        <div className="place-comparison-apply-error">
          <ErrorState error={applyError} />
          <p className="small muted">
            적용 전 상태가 달라졌을 수 있어 이전 비교 결과를 폐기했어요. 안내를
            확인한 뒤 후보를 다시 선택해 주세요.
          </p>
        </div>
      )}
    </Modal>
  );
}
