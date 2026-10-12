import { useState, type FormEvent } from "react";
import { Search, RotateCcw } from "lucide-react";
import type { CommunityFilters, RecruitmentStatus } from "./communitySearch";

/** 입력 도중에는 조회하지 않습니다. 검색 버튼/Enter로 조건을 확정해 불필요한 요청과 목록 흔들림을 줄입니다. */
export default function CommunitySearchForm({
  filters,
  apply,
  reset,
}: {
  filters: CommunityFilters;
  apply: (filters: CommunityFilters) => void;
  reset: () => void;
}) {
  const [q, setQuery] = useState(filters.q);
  const [region, setRegion] = useState(filters.region);
  const [status, setStatus] = useState(filters.status);
  function submit(event: FormEvent) {
    event.preventDefault();
    apply({ ...filters, q: q.trim(), region: region.trim(), status, page: 1 });
  }
  return (
    <form
      className="community-search panel"
      aria-label="여행 이야기 검색"
      onSubmit={submit}
    >
      <div className="community-search-fields">
        <label>
          검색어
          <input
            type="search"
            value={q}
            onChange={(e) => setQuery(e.target.value)}
            maxLength={100}
            placeholder="예: 비 오는 날 데이트"
          />
        </label>
        <label>
          여행 지역
          <input
            type="search"
            value={region}
            onChange={(e) => setRegion(e.target.value)}
            maxLength={50}
            placeholder="예: 부산, 성수동"
          />
        </label>
        {filters.category === "동행" && (
          <label>
            모집 상태
            <select
              value={status}
              onChange={(e) => setStatus(e.target.value as RecruitmentStatus)}
            >
              <option value="">모든 모집 상태</option>
              <option value="OPEN">모집 중</option>
              <option value="CLOSED">모집 마감</option>
              <option value="CANCELLED">모집 취소</option>
            </select>
          </label>
        )}
      </div>
      <p className="field-help">
        검색어는 제목과 본문에서, 지역은 글에 적힌 지역명에서 찾아요. 원하는
        조건만 입력해도 돼요.
      </p>
      <div className="community-search-actions">
        <button type="submit" className="button primary">
          <Search size={18} />
          이야기 찾기
        </button>
        <button
          type="button"
          className="button secondary"
          onClick={() => {
            setQuery("");
            setRegion("");
            setStatus("");
            reset();
          }}
        >
          <RotateCcw size={17} />
          조건 초기화
        </button>
      </div>
    </form>
  );
}
