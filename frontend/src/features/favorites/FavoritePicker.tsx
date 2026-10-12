import { Link } from "react-router-dom";
import { Heart, Check } from "lucide-react";
import { Loading, ErrorState } from "../../shared/ui";
import { useFavorites } from "./useFavorites";

/** 선호도 선택은 개인 찜을 자동으로 전부 넣지 않습니다. 사용자가 이번 여행에 고른 ID만 서버에 보냅니다. */
export default function FavoritePicker({
  regions,
  selected,
  onChange,
}: {
  regions: string[];
  selected: string[];
  onChange: (ids: string[]) => void;
}) {
  const favorites = useFavorites();
  const available =
    favorites.data?.filter(
      (f) => regions.includes(f.place.region) && f.place.category !== "숙소",
    ) || [];
  const missing = favorites.data
    ? selected.filter((id) => !available.some((f) => f.place.id === id))
    : [];
  return (
    <section
      className="favorite-picker"
      aria-labelledby="favorite-picker-title"
    >
      <h3 id="favorite-picker-title">
        <Heart size={19} />
        찜한 방문 장소 <span>{selected.length} / 5</span>
      </h3>
      <p className="muted">
        이번 지역에서 먼저 고려할 곳을 골라 주세요. 분류 균형·이동·시간에 따라
        일부는 빠질 수 있고, 결과에서 반영 여부를 확인할 수 있어요.
      </p>
      {favorites.isPending ? (
        <Loading />
      ) : favorites.error ? (
        <ErrorState error={favorites.error} retry={() => favorites.refetch()} />
      ) : (
        <>
          {missing.map((id) => (
            <div className="notice favorite-missing" key={id}>
              <p>
                해제됐거나 이번 지역에서 사용할 수 없는 찜이 선택되어 있어요.
              </p>
              <button
                className="button secondary"
                onClick={() => onChange(selected.filter((x) => x !== id))}
              >
                이 찜 선택 해제
              </button>
            </div>
          ))}
          {available.length ? (
            <div className="favorite-picker-list">
              {available.map(({ place: p }) => {
                const active = selected.includes(p.id);
                return (
                  <button
                    type="button"
                    key={p.id}
                    className={"favorite-pick " + (active ? "selected" : "")}
                    aria-pressed={active}
                    disabled={!active && selected.length >= 5}
                    onClick={() =>
                      onChange(
                        active
                          ? selected.filter((id) => id !== p.id)
                          : [...selected, p.id],
                      )
                    }
                  >
                    <span>
                      <strong>{p.name}</strong>
                      <span>
                        {p.region} · {p.category}
                      </span>
                    </span>
                    {active ? <Check size={20} /> : <Heart size={18} />}
                  </button>
                );
              })}
            </div>
          ) : (
            <p className="favorite-empty">
              이번 지역에 찜한 방문 장소가 없어요. 찜 없이도 추천받을 수 있어요.
            </p>
          )}
        </>
      )}
      <Link
        className="text-button favorite-picker-link"
        to="/favorites"
        target="_blank"
        rel="noreferrer"
      >
        내 찜 관리하기 · 새 탭
      </Link>
    </section>
  );
}
