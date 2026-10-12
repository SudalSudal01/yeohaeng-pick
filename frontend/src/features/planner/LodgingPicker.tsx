import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Check, Hotel, ArrowUpRight, Heart } from "lucide-react";
import { api, money } from "../../shared/api";
import type { Place } from "../../shared/types";
import { Loading, ErrorState } from "../../shared/ui";
import { useFavorites } from "../favorites/useFavorites";
import FavoriteLodgingCard from "./FavoriteLodgingCard";
import {
  chooseLodging,
  removeLodging,
  type LodgingSelection,
} from "./lodgingSelection";

/** 찜 숙소의 명시적 재조회/선택과 일반 검색 후보를 연결합니다. 후보 목록 실패를 다른 종류의 실패로 숨기지 않습니다. */
export default function LodgingPicker({
  regions,
  selection,
  onChange,
  suggestedFavorite,
}: {
  regions: string[];
  selection: LodgingSelection;
  onChange: (value: LodgingSelection) => void;
  suggestedFavorite?: string | null;
}) {
  const favorites = useFavorites();
  const [showAll, setShowAll] = useState<string[]>([]);
  const [showFavorites, setShowFavorites] = useState(false);
  const query = useQuery({
    queryKey: ["lodging-candidates", regions],
    queryFn: async () =>
      (
        await Promise.all(
          regions.map((r) =>
            api<Place[]>(
              "/catalog/places?region=" +
                encodeURIComponent(r) +
                "&category=" +
                encodeURIComponent("숙소"),
            ),
          ),
        )
      )
        .flat()
        .filter((p) => p.category === "숙소"),
  });
  const saved =
    favorites.data?.filter(
      (f) => regions.includes(f.place.region) && f.place.category === "숙소",
    ) || [];
  // 링크의 숙소와 선택한 숙소는 접힌 목록에서도 보입니다. 사용자가 확인하기 전에는 자동 선택하지 않습니다.
  const orderedSaved = [...saved].sort(
    (a, b) =>
      Number(b.place.id === suggestedFavorite) -
      Number(a.place.id === suggestedFavorite),
  );
  const visibleSaved = showFavorites
    ? orderedSaved
    : orderedSaved.filter(
        (f, i) =>
          i < 4 ||
          selection.lodgingIds.includes(f.place.id) ||
          f.place.id === suggestedFavorite,
      );
  const known = [...(query.data || []), ...saved.map((f) => f.place)];
  const choose = (p: Place, favorite: boolean) =>
    onChange(chooseLodging(selection, p, known, favorite));
  const missing = favorites.data
    ? selection.favoriteLodgingIds.filter(
        (id) => !saved.some((f) => f.place.id === id),
      )
    : [];
  if (query.data && favorites.data)
    missing.push(
      ...selection.lodgingIds.filter((id) => !known.some((p) => p.id === id)),
    );
  const suggestionMissing =
    suggestedFavorite &&
    favorites.data &&
    !saved.some((f) => f.place.id === suggestedFavorite);
  return (
    <section className="lodging-picker" aria-labelledby="lodging-picker-title">
      <h3 id="lodging-picker-title">
        <Hotel size={19} />
        숙소 후보도 함께 골라요
      </h3>
      <p className="small muted">
        지역별 한 곳을 선택하면 숙소와 가까운 장소부터 여행해요. 미정이면
        선택하지 않아도 괜찮아요. 선택은 예약 완료를 뜻하지 않아요.
      </p>
      <div className="favorite-lodging-section">
        <h4>
          <Heart size={18} />
          찜한 숙소에서 고르기
        </h4>
        <p>
          보관한 숙소를 하나씩 다시 확인한 뒤 동선 기준으로 선택해 주세요. 장소
          정보는 최대 5분 전 조회 결과일 수 있어요. 객실 요금·재고는 예약처에서
          확인해 주세요.
        </p>
        {favorites.isPending ? (
          <Loading text="찜한 숙소를 불러와요" />
        ) : favorites.error ? (
          <ErrorState
            error={favorites.error}
            retry={() => favorites.refetch()}
          />
        ) : !saved.length ? (
          <p className="favorite-lodging-empty">
            이번 지역에 찜한 숙소가 없어요. 아래 일반 후보에서 선택할 수 있어요.
          </p>
        ) : (
          <div className="favorite-lodging-list">
            {visibleSaved.map((f) => (
              <FavoriteLodgingCard
                key={f.place.id}
                saved={f}
                suggested={f.place.id === suggestedFavorite}
                selected={
                  selection.lodgingIds.includes(f.place.id) &&
                  selection.favoriteLodgingIds.includes(f.place.id)
                }
                onChoose={(p) => choose(p, true)}
              />
            ))}
          </div>
        )}
        {saved.length > 4 && (
          <button
            className="text-button lodging-show-more"
            type="button"
            onClick={() => setShowFavorites((value) => !value)}
          >
            {showFavorites
              ? "찜한 숙소 목록 접기"
              : "찜한 숙소 " + saved.length + "곳 모두 보기"}
          </button>
        )}
        {suggestionMissing && (
          <p className="field-error">
            링크의 숙소를 내 찜에서 찾을 수 없어요. 해제됐거나 이번 여행 지역의
            숙소가 아닌지 확인해 주세요.
          </p>
        )}
      </div>
      {[...new Set(missing)].map((id) => (
        <div className="notice lodging-missing" key={id}>
          <p>
            사용할 수 없거나 해제된 숙소 선택이 남아 있어요. 일반 후보로 자동
            변경하지 않으니 이 선택을 해제한 뒤 다시 골라 주세요.
          </p>
          <button
            className="button secondary"
            type="button"
            onClick={() => onChange(removeLodging(selection, id))}
          >
            사용할 수 없는 숙소 선택 해제
          </button>
        </div>
      ))}
      <h4 className="general-lodgings-heading">일반 숙소 후보</h4>
      {query.isPending ? (
        <Loading text="일반 숙소 후보를 불러와요" />
      ) : query.error ? (
        <ErrorState error={query.error} retry={() => query.refetch()} />
      ) : (
        regions.map((region) => {
          const options = query.data?.filter((p) => p.region === region) || [];
          // 기본 목록은 네 곳과 현재 선택한 곳을 보여 줍니다. 선택한 숙소가 접힌 목록 속에 사라지지 않습니다.
          const visible = showAll.includes(region)
            ? options
            : options.filter(
                (p, i) => i < 4 || selection.lodgingIds.includes(p.id),
              );
          return (
            <div className="lodging-region" key={region}>
              <strong>{region}</strong>
              {visible.map((p) => {
                const active = selection.lodgingIds.includes(p.id);
                return (
                  <div
                    className={"lodging-option " + (active ? "selected" : "")}
                    key={p.id}
                  >
                    <button
                      type="button"
                      aria-pressed={active}
                      aria-label={
                        p.name +
                        (active ? " 일반 숙소 선택 해제" : " 일반 숙소 선택")
                      }
                      onClick={() => choose(p, false)}
                    >
                      <Hotel size={22} />
                      <span>
                        <strong>{p.name}</strong>
                        <small>
                          {p.source === "SAMPLE"
                            ? money(p.cost) + " / 1객실 1박 · 예시"
                            : "객실 요금 확인 필요"}
                          {selection.favoriteLodgingIds.includes(p.id) &&
                            " · 내 찜에서 선택됨"}
                        </small>
                      </span>
                      {active && <Check size={18} />}
                    </button>
                    <a
                      className="text-button"
                      href={p.url}
                      target="_blank"
                      rel="noreferrer"
                      aria-label={p.name + " 위치·예약 안내"}
                    >
                      위치·예약 안내
                      <ArrowUpRight size={16} />
                    </a>
                  </div>
                );
              })}
              {!options.length && (
                <p className="muted small">
                  현재 검색된 숙소가 없어요. 찜한 숙소를 확인하거나 숙소 없이
                  진행할 수 있어요.
                </p>
              )}
              {options.length > 4 && (
                <button
                  className="text-button lodging-show-more"
                  type="button"
                  onClick={() =>
                    setShowAll((list) =>
                      list.includes(region)
                        ? list.filter((r) => r !== region)
                        : [...list, region],
                    )
                  }
                >
                  {showAll.includes(region)
                    ? region + " 일반 후보 접기"
                    : region + " 일반 후보 " + options.length + "곳 모두 보기"}
                </button>
              )}
            </div>
          );
        })
      )}
    </section>
  );
}
