import { Link, useSearchParams } from "react-router-dom";
import { Heart, MapPin, ArrowRight, ArrowUpRight } from "lucide-react";
import { activityPrice } from "../../shared/api";
import { Loading, ErrorState, Empty } from "../../shared/ui";
import { useFavorites } from "./useFavorites";
import FavoriteButton from "./FavoriteButton";

/** 최대 200곳의 개인 찜을 지역/분류로 좁히고 12곳씩 봅니다. 필터와 페이지는 주소에 남겨 복귀를 돕습니다. */
export default function FavoritesPage() {
  const favorites = useFavorites();
  const [params, setParams] = useSearchParams();
  const region = params.get("region") || "전체";
  const category = params.get("category") || "전체";
  const filtered =
    favorites.data?.filter(
      (f) =>
        (region === "전체" || f.place.region === region) &&
        (category === "전체" || f.place.category === category),
    ) || [];
  const pages = Math.max(1, Math.ceil(filtered.length / 12));
  const requested = Number(params.get("page"));
  const page = Math.min(
    pages,
    Math.max(1, Number.isFinite(requested) ? Math.floor(requested) : 1),
  );
  const update = (key: string, value: string) =>
    setParams((p) => {
      p.set(key, value);
      if (key !== "page") p.delete("page");
      return p;
    });
  return (
    <div className="page favorites-page">
      <div className="page-heading">
        <span className="eyebrow">SAVED FOR YOUR NEXT TRIP</span>
        <h1>가고 싶은 곳, 내 찜</h1>
        <p>
          혼자 모아 둔 장소를 이번 여행의 후보로 골라 보세요. 찜은 나에게만
          보여요.
        </p>
      </div>
      <div className="favorite-intro panel">
        <div>
          <Heart size={23} />
          <strong>
            {favorites.isPending
              ? "내 찜을 불러오고 있어요"
              : favorites.error
                ? "찜 목록 확인 필요"
                : `${favorites.data?.length || 0} / 200곳 보관`}
          </strong>
        </div>
        <p>
          찜한 방문 장소를 최대 5곳 골라 추천에 우선 반영해 보세요. 코스마다
          반영 결과를 확인할 수 있어요.
        </p>
        <div className="favorite-intro-actions">
          <Link className="button primary" to="/plan">
            찜 보며 여행 만들기
            <ArrowRight size={17} />
          </Link>
          <Link className="button secondary" to="/explore">
            장소 더 찾아보기
          </Link>
        </div>
      </div>
      <details className="favorite-help">
        <summary>찜과 장소 정보 안내</summary>
        <p>
          목록은 보관 당시 정보이며 추천할 때 다시 조회해요. 요금·영업·예약
          여부는 방문 전에 확인해 주세요. 숙소 찜은 다시 찾아보는 용도예요.
          여행의 숙소는 숙소 선택 단계에서 별도로 정해 주세요.
        </p>
      </details>
      {favorites.isPending ? (
        <Loading />
      ) : favorites.error ? (
        <ErrorState error={favorites.error} retry={() => favorites.refetch()} />
      ) : !favorites.data?.length ? (
        <Empty
          title="다음 여행의 첫 찜을 골라 보세요"
          text="여행지 탐색에서 마음에 드는 장소의 찜하기를 눌러 주세요."
          action="여행지 탐색"
          to="/explore"
        />
      ) : (
        <>
          <div className="favorite-filters">
            <label>
              지역
              <select
                value={region}
                onChange={(e) => update("region", e.target.value)}
              >
                {["전체", "서울", "부산", "제주", "강릉"].map((x) => (
                  <option key={x}>{x}</option>
                ))}
              </select>
            </label>
            <label>
              분류
              <select
                value={category}
                onChange={(e) => update("category", e.target.value)}
              >
                {[
                  "전체",
                  "관광",
                  "자연",
                  "맛집",
                  "카페",
                  "전시",
                  "체험",
                  "숙소",
                ].map((x) => (
                  <option key={x}>{x}</option>
                ))}
              </select>
            </label>
          </div>
          <p className="favorite-count" role="status">
            {filtered.length}곳 · {page} / {pages}페이지
          </p>
          {!filtered.length ? (
            <Empty
              title="이 조건의 찜은 아직 없어요"
              text="지역이나 분류를 바꾸면 다른 찜을 볼 수 있어요."
            />
          ) : (
            <div className="favorite-grid">
              {filtered
                .slice((page - 1) * 12, page * 12)
                .map(({ place: p, savedAt }) => (
                  <article className="panel favorite-card" key={p.id}>
                    <span className="badge subtle">
                      {p.category} ·{" "}
                      {p.source === "SAMPLE" ? "로컬 예시" : "Kakao 장소"}
                    </span>
                    <h2>{p.name}</h2>
                    <p>
                      <MapPin size={16} />
                      {p.region}
                      {p.category !== "숙소" &&
                        " · " + p.duration + "분 머무름 참고"}
                    </p>
                    <p className="favorite-price">
                      {p.category === "숙소"
                        ? "객실 요금·재고는 예약처 확인"
                        : activityPrice(p)}
                    </p>
                    <span className="favorite-date">
                      {savedAt.slice(0, 10)} 보관 · 당시 정보
                    </span>
                    <div className="favorite-card-actions">
                      <FavoriteButton place={p} />
                      <a
                        className="button secondary"
                        href={p.url}
                        target="_blank"
                        rel="noreferrer"
                        aria-label={p.name + " 지도·리뷰 보기"}
                      >
                        지도·리뷰
                        <ArrowUpRight size={17} />
                      </a>
                      <Link
                        className="text-button"
                        to={
                          "/plan?region=" +
                          encodeURIComponent(p.region) +
                          (p.category !== "숙소"
                            ? "&favorite=" + encodeURIComponent(p.id)
                            : "")
                        }
                      >
                        {p.category === "숙소"
                          ? "이 지역 여행 만들기"
                          : "이 장소 우선 고려하기"}
                        <ArrowRight size={16} />
                      </Link>
                    </div>
                  </article>
                ))}
            </div>
          )}
          {pages > 1 && (
            <nav className="favorite-pagination" aria-label="찜 목록 페이지">
              <button
                className="button secondary"
                disabled={page === 1}
                onClick={() => update("page", String(page - 1))}
              >
                이전
              </button>
              <span>
                {page} / {pages}
              </span>
              <button
                className="button secondary"
                disabled={page === pages}
                onClick={() => update("page", String(page + 1))}
              >
                다음
              </button>
            </nav>
          )}
        </>
      )}
    </div>
  );
}
