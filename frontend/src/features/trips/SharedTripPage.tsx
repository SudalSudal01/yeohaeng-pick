import { useCallback, useEffect, useRef, useState } from "react";
import { useParams, Link, useLocation } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { LockKeyhole, ArrowRight, Copy } from "lucide-react";
import { api, activityPrice, calendarDateLabel } from "../../shared/api";
import type { SharedTrip } from "../../shared/types";
import { Loading, ErrorState } from "../../shared/ui";
import { useAuth } from "../../shared/providers";
import SharedTripImport, { type ImportAttempt } from "./SharedTripImport";
import "./sharedTrip.css";

/** 공개 응답은 서버에서 허용 필드만 골라 보냅니다. 브라우저에서 민감한 필드를 숨기는 방식에 의존하지 않습니다. */
export default function SharedTripPage() {
  const { token } = useParams();
  const location = useLocation();
  const { user, loading } = useAuth();
  const [importing, setImporting] = useState(false);
  // 응답 유실 후 창을 닫았다 다시 열어도 같은 요청을 재시도합니다. 계정/공유 링크가 바뀌면 개인 입력은 제거합니다.
  const attempt = useRef<ImportAttempt | null>(null);
  useEffect(() => {
    attempt.current = null;
    setImporting(false);
  }, [user?.id, token]);
  const closeImport = useCallback(() => setImporting(false), []);
  const query = useQuery({
    queryKey: ["share", token],
    queryFn: () => api<SharedTrip>("/share/" + token),
    retry: false,
  });
  if (query.isPending) return <Loading />;
  if (query.error || !query.data) return <ErrorState error={query.error} />;
  const t = query.data;
  return (
    <div className="page article-page">
      <div className="notice">
        <LockKeyhole size={17} />
        공개된 일정이에요. 내 여행으로 가져오면 원본과 별도로 수정할 수 있어요.
        함께 편집하려면 여행 초대를 받아 주세요.
      </div>
      <div className="page-heading">
        <span className="eyebrow">A JOURNEY TO SHARE</span>
        <h1>{t.title}</h1>
        <p>{t.regions.join(" → ")}</p>
        <p>
          {calendarDateLabel(t.startDate)} ~ {calendarDateLabel(t.endDate)} ·
          원본 날짜
        </p>
      </div>
      <section
        className="panel shared-import-callout"
        aria-label="내 여행으로 일정 가져오기"
      >
        <h2>마음에 드는 코스, 내 여행으로</h2>
        <p className="muted">
          새 날짜와 내 조건으로 저장하고 자유롭게 바꿔 보세요. 예약·숙소·개인
          기록은 복사되지 않아요.
        </p>
        <div className="shared-import-action">
          {loading ? (
            <Loading text="로그인 상태 확인 중" />
          ) : user ? (
            <button
              className="button primary"
              onClick={() => setImporting(true)}
            >
              <Copy size={18} /> 내 여행으로 가져오기
            </button>
          ) : (
            <Link
              className="button primary"
              to={"/login?next=" + encodeURIComponent(location.pathname)}
            >
              <Copy size={18} /> 로그인하고 가져오기
            </Link>
          )}
        </div>
      </section>
      {[...new Set(t.items.map((i) => i.dayIndex))].map((d) => (
        <section className="panel shared-day" key={d}>
          <h2>DAY {d + 1}</h2>
          {t.items
            .filter((i) => i.dayIndex === d)
            .map((i, n) => (
              <div className="shared-place" key={n}>
                <span className="badge subtle">{i.startTime}</span>
                <div>
                  <strong>{i.placeName}</strong>
                  <p>
                    {i.category} · {i.duration}분
                  </p>
                  <p className="muted">{activityPrice(i)}</p>
                </div>
                {i.placeUrl && (
                  <a
                    className="text-button"
                    href={i.placeUrl}
                    target="_blank"
                    rel="noreferrer"
                  >
                    장소 보기
                    <ArrowRight size={15} />
                  </a>
                )}
              </div>
            ))}
        </section>
      ))}
      <Link className="button primary" to="/plan">
        나만의 여행 만들기
        <ArrowRight size={17} />
      </Link>
      {importing && token && (
        <SharedTripImport
          trip={t}
          token={token}
          onClose={closeImport}
          attempt={attempt}
        />
      )}
    </div>
  );
}
