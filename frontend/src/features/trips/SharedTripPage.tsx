import { useParams, Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { LockKeyhole, ArrowRight } from "lucide-react";
import { api } from "../../shared/api";
import type { Item } from "../../shared/types";
import { Loading, ErrorState } from "../../shared/ui";

/** 공개 응답은 서버에서 허용 필드만 골라 보냅니다. 브라우저에서 민감한 필드를 숨기는 방식에 의존하지 않습니다. */
export default function SharedTripPage() {
  const { token } = useParams();
  const query = useQuery({
    queryKey: ["share", token],
    queryFn: () =>
      api<{ title: string; regions: string[]; items: Item[] }>(
        "/share/" + token,
      ),
  });
  if (query.isPending) return <Loading />;
  if (query.error || !query.data) return <ErrorState error={query.error} />;
  const t = query.data;
  return (
    <div className="page article-page">
      <div className="notice">
        <LockKeyhole size={17} />
        함께 보는 읽기 전용 일정이에요. 편집하려면 별도로 여행 초대를 수락해야
        해요.
      </div>
      <div className="page-heading">
        <span className="eyebrow">A JOURNEY TO SHARE</span>
        <h1>{t.title}</h1>
        <p>{t.regions.join(" → ")}</p>
      </div>
      {[...new Set(t.items.map((i) => i.dayIndex))].map((d) => (
        <section className="panel shared-day" key={d}>
          <h2>DAY {d + 1}</h2>
          {t.items
            .filter((i) => i.dayIndex === d)
            .map((i, n) => (
              <div className="booking-row" key={n}>
                <span className="badge subtle">{i.startTime}</span>
                <div>
                  <strong>{i.placeName}</strong>
                  <p>
                    {i.category} · {i.duration}분
                  </p>
                </div>
                <a
                  className="text-button"
                  href={i.placeUrl}
                  target="_blank"
                  rel="noreferrer"
                >
                  장소 보기
                  <ArrowRight size={15} />
                </a>
              </div>
            ))}
        </section>
      ))}
      <Link className="button primary" to="/plan">
        나만의 여행 만들기
        <ArrowRight size={17} />
      </Link>
    </div>
  );
}
