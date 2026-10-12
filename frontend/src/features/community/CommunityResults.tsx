import { Link } from "react-router-dom";
import {
  ArrowLeft,
  ArrowRight,
  MapPin,
  Users,
  CalendarDays,
} from "lucide-react";
import { calendarDateLabel } from "../../shared/api";
import type { CommunityPostPage } from "../../shared/types";

/** 서버가 확정한 건수·현재 페이지를 사용합니다. 카드는 공개 미리보기만 받고 상세에서 본문을 조회합니다. */
export default function CommunityResults({
  data,
  returnTo,
  changePage,
}: {
  data: CommunityPostPage;
  returnTo: string;
  changePage: (page: number) => void;
}) {
  const first = (data.page - 1) * data.pageSize + 1;
  return (
    <>
      <div
        className="community-result-summary"
        role="status"
        aria-live="polite"
      >
        <strong>
          여행 이야기 <span>{data.total.toLocaleString()}개</span>
        </strong>
        <span>
          최신순 · {first}–{first + data.items.length - 1}번째 이야기
        </span>
      </div>
      <div className="post-grid">
        {data.items.map((p) => (
          <Link
            className="post-card"
            to={"/community/" + p.id + "?" + new URLSearchParams({ returnTo })}
            key={p.id}
          >
            <div className="post-card-meta">
              <span
                className={
                  "badge " + (p.category === "동행" ? "orange-badge" : "subtle")
                }
              >
                {p.category === "동행"
                  ? p.status === "OPEN"
                    ? "동행 모집 중"
                    : p.status === "CANCELLED"
                      ? "모집 취소"
                      : "모집 마감"
                  : p.category}
              </span>
              <span>
                <MapPin size={14} />
                {p.region}
              </span>
            </div>
            <h2>{p.title}</h2>
            <p className="post-excerpt">{p.excerpt}</p>
            {p.category === "동행" && (
              <div className="recruit-meta">
                <span>
                  <CalendarDays size={15} />
                  {calendarDateLabel(p.recruitmentDate)}
                </span>
                <span>
                  <Users size={15} />
                  {p.capacity}명
                </span>
              </div>
            )}
            <div className="post-author">
              <span className="avatar small-avatar" aria-hidden="true">
                {p.nickname.slice(0, 1)}
              </span>
              <span className="post-nickname">{p.nickname}</span>
              <span className="post-date">
                {new Date(p.createdAt).toLocaleDateString("ko-KR")}
              </span>
              <ArrowRight size={17} aria-hidden="true" />
            </div>
          </Link>
        ))}
      </div>
      {data.totalPages > 1 && (
        <nav
          className="community-pagination"
          aria-label="여행 이야기 페이지 이동"
        >
          <button
            className="button secondary"
            disabled={data.page <= 1}
            onClick={() => changePage(data.page - 1)}
          >
            <ArrowLeft size={17} />
            이전
          </button>
          <span aria-current="page">
            {data.page.toLocaleString()} / {data.totalPages.toLocaleString()}{" "}
            페이지
          </span>
          <button
            className="button secondary"
            disabled={data.page >= data.totalPages}
            onClick={() => changePage(data.page + 1)}
          >
            다음
            <ArrowRight size={17} />
          </button>
        </nav>
      )}
    </>
  );
}
