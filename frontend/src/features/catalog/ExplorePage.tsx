import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useSearchParams, Link } from "react-router-dom";
import {
  Search,
  MapPin,
  ArrowUpRight,
  ArrowRight,
  SlidersHorizontal,
} from "lucide-react";
import { api, money } from "../../shared/api";
import type { Place } from "../../shared/types";
import { useConfig } from "../../shared/providers";
import { Loading, ErrorState, Empty } from "../../shared/ui";

/** URL에 검색 조건을 보존해 새로고침·뒤로 가기·공유 시 사용자가 보던 검색을 유지합니다. */
export default function ExplorePage() {
  const [params, setParams] = useSearchParams();
  const region = params.get("region") || "서울";
  const category = params.get("category") || "전체";
  const query = params.get("query") || "";
  const [text, setText] = useState(query);
  useEffect(() => setText(query), [query]);
  const config = useConfig();
  const places = useQuery({
    queryKey: ["places", region, query, category],
    queryFn: () =>
      api<Place[]>(
        "/catalog/places?region=" +
          encodeURIComponent(region) +
          "&query=" +
          encodeURIComponent(query) +
          "&category=" +
          encodeURIComponent(category),
      ),
  });
  const update = (key: string, value: string) =>
    setParams((p) => {
      p.set(key, value);
      return p;
    });
  const filtered = places.data?.filter(
    (p) => category === "전체" || p.category === category,
  );
  return (
    <div className="page">
      <div className="page-heading">
        <span className="eyebrow">DISCOVER YOUR PICK</span>
        <h1>마음에 드는 곳을 발견해요</h1>
        <p>가고 싶은 곳을 찾았다면, 그곳에서 여행을 시작해 보세요.</p>
      </div>
      <form
        className="search-bar"
        onSubmit={(e) => {
          e.preventDefault();
          update("query", text);
        }}
      >
        <Search size={21} />
        <input
          aria-label="장소 검색"
          placeholder="어떤 장소를 찾고 있나요?"
          value={text}
          onChange={(e) => setText(e.target.value)}
        />
        <button className="button primary">검색</button>
      </form>
      <div className="filter-row">
        <span className="filter-label">
          <MapPin size={17} />
          지역
        </span>
        {["서울", "부산", "제주", "강릉"].map((c) => (
          <button
            key={c}
            className={"chip " + (region === c ? "selected" : "")}
            onClick={() => update("region", c)}
          >
            {c}
          </button>
        ))}
      </div>
      <div className="filter-row">
        <span className="filter-label">
          <SlidersHorizontal size={17} />
          취향
        </span>
        {["전체", "관광", "자연", "맛집", "카페", "전시", "체험", "숙소"].map(
          (c) => (
            <button
              key={c}
              className={"chip " + (category === c ? "selected" : "")}
              onClick={() => update("category", c)}
            >
              {c}
            </button>
          ),
        )}
      </div>
      {config.data?.sampleData && (
        <div className="notice">
          로컬 예시 장소예요. 비용은 예산을 비교하기 위한 추정치이며 현재
          요금·운영 여부와 다를 수 있어요.
        </div>
      )}
      {places.isPending ? (
        <Loading />
      ) : places.error ? (
        <ErrorState error={places.error} retry={() => places.refetch()} />
      ) : !filtered?.length ? (
        <Empty
          title="아직 발견한 장소가 없어요"
          text="검색어나 카테고리를 바꿔서 다시 찾아보세요."
        />
      ) : (
        <div className="place-grid">
          {filtered.map((p, i) => (
            <article className="place-card" key={p.id}>
              <div className={"place-art art-" + (i % 5)}>
                <span className="place-art-category">{p.category}</span>
                <MapPin size={42} strokeWidth={1.3} />
                <span className="place-art-region">{p.region}</span>
              </div>
              <div className="place-card-body">
                <span className="badge subtle">
                  {p.category} ·{" "}
                  {p.source === "KAKAO"
                    ? "실내·야외 확인 필요"
                    : p.indoor
                      ? "실내"
                      : "야외"}
                </span>
                <h3>{p.name}</h3>
                <p>
                  <MapPin size={14} />
                  {p.region} ·{" "}
                  {p.duration ? p.duration + "분 머무르기" : "숙박 후보"}
                </p>
                <div className="place-bottom">
                  <span>
                    {p.source === "KAKAO" && p.cost === 0
                      ? "요금 확인 필요"
                      : money(p.cost)}{" "}
                    <small>
                      {p.source === "KAKAO" && p.cost === 0 ? "" : "추정"}
                    </small>
                  </span>
                  <a
                    href={p.url}
                    target="_blank"
                    rel="noreferrer"
                    aria-label={p.name + " 지도에서 상세·리뷰 보기"}
                  >
                    <ArrowUpRight size={20} />
                  </a>
                </div>
                <Link
                  className="text-button"
                  to={"/plan?region=" + encodeURIComponent(p.region)}
                >
                  이 지역 여행 만들기
                  <ArrowRight size={15} />
                </Link>
              </div>
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
