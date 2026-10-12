import { useQuery } from "@tanstack/react-query";
import { Check, RefreshCw, Heart, ArrowUpRight } from "lucide-react";
import { api, money } from "../../shared/api";
import { useAuth } from "../../shared/providers";
import type { CheckedLodging, Favorite, Place } from "../../shared/types";
import { ErrorState } from "../../shared/ui";

/** 과거 스냅샷에서 바로 선택하지 않습니다. 필요한 숙소 한 곳을 확인한 뒤 사용자가 별도로 적용합니다. */
export default function FavoriteLodgingCard({
  saved,
  selected,
  suggested,
  onChoose,
}: {
  saved: Favorite;
  selected: boolean;
  suggested: boolean;
  onChoose: (place: Place) => void;
}) {
  const { user } = useAuth();
  const query = useQuery({
    queryKey: [
      "checked-favorite-lodging",
      user?.id,
      saved.place.id,
      saved.savedAt,
    ],
    queryFn: () =>
      api<CheckedLodging>("/favorites/lodgings/resolve", {
        method: "POST",
        body: { placeId: saved.place.id, region: saved.place.region },
      }),
    enabled: false,
    retry: false,
  });
  // 재조회에 실패했을 때 직전 성공 데이터를 최신 결과처럼 표시하거나 선택에 사용하지 않습니다.
  const checked = query.error ? undefined : query.data;
  return (
    <article
      className={"favorite-lodging-card " + (selected ? "selected" : "")}
    >
      <span className="badge subtle">
        <Heart size={14} />내 찜 · 보관 당시
      </span>
      {suggested && (
        <p className="favorite-lodging-suggestion">
          내 찜에서 가져온 숙소 · 정보를 확인하고 선택해 주세요
        </p>
      )}
      <h4>{saved.place.name}</h4>
      <p className="favorite-lodging-saved">
        {saved.savedAt.slice(0, 10)} 보관 · {saved.place.region}
      </p>
      {selected && (
        <p className="favorite-lodging-selected">
          <Check size={16} />이 여행의 동선 기준으로 선택됨
        </p>
      )}
      {checked && (
        <div className="favorite-lodging-current">
          <strong>다시 확인한 장소</strong>
          <p>{checked.place.name}</p>
          <p>
            {checked.place.region} ·{" "}
            {checked.place.source === "SAMPLE" ? "로컬 예시" : "Kakao 장소"}
          </p>
          <p>
            {checked.place.source === "SAMPLE"
              ? money(checked.place.cost) + " / 1객실 1박 · 예시"
              : "객실 요금·재고 확인 필요"}
          </p>
          <span>
            {new Date(checked.checkedAt).toLocaleString("ko-KR")} 확인
          </span>
          <a
            className="text-button"
            href={checked.place.url}
            target="_blank"
            rel="noreferrer"
            aria-label={checked.place.name + " 현재 위치·예약 안내"}
          >
            현재 위치·예약 안내
            <ArrowUpRight size={16} />
          </a>
        </div>
      )}
      {query.error && <ErrorState error={query.error} />}
      <div className="favorite-lodging-actions">
        <button
          type="button"
          className="button secondary"
          disabled={query.isFetching}
          onClick={() => query.refetch()}
          aria-label={saved.place.name + " 현재 정보 확인"}
        >
          <RefreshCw size={16} className={query.isFetching ? "spin" : ""} />
          {query.isFetching
            ? "확인하고 있어요"
            : checked
              ? "정보 다시 확인"
              : "현재 정보 확인"}
        </button>
        {(checked || selected) && (
          <button
            type="button"
            className={"button " + (selected ? "secondary" : "primary")}
            disabled={query.isFetching || (!checked && !selected)}
            onClick={() => onChoose(checked?.place || saved.place)}
            aria-label={
              saved.place.name +
              (selected ? " 숙소 선택 해제" : " 동선 기준으로 선택")
            }
          >
            <Check size={16} />
            {selected ? "숙소 선택 해제" : "동선 기준으로 선택"}
          </button>
        )}
      </div>
    </article>
  );
}
