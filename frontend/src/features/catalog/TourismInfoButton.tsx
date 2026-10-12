import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
} from "react";
import { useQuery } from "@tanstack/react-query";
import {
  ArrowLeft,
  ArrowRight,
  ArrowUpRight,
  BookOpen,
  Check,
  MapPin,
  Search,
} from "lucide-react";
import { api } from "../../shared/api";
import { useConfig } from "../../shared/providers";
import { Modal, Loading, ErrorState, Empty } from "../../shared/ui";
import type {
  TourismContent,
  TourismDetail,
  TourismField,
  TourismSearch,
} from "../../shared/types";

type Target = { name: string; region: string; url: string };

/** 탐색·저장된 일정에서 같은 관광정보 화면을 엽니다. 사용자 선택 전 이름만으로 장소를 병합하지 않습니다. */
export default function TourismInfoButton({
  target,
  onSetConditions,
}: {
  target: Target;
  onSetConditions?: () => void;
}) {
  const [open, setOpen] = useState(false);
  const close = useCallback(() => setOpen(false), []);
  return (
    <>
      <button
        className="text-button tourism-open-button"
        onClick={() => setOpen(true)}
        aria-label={target.name + " 운영시간·이용정보"}
      >
        <BookOpen size={17} />
        운영시간·이용정보
      </button>
      {open && (
        <TourismInfoModal
          target={target}
          close={close}
          onSetConditions={
            onSetConditions
              ? () => {
                  close();
                  onSetConditions();
                }
              : undefined
          }
        />
      )}
    </>
  );
}

function TourismInfoModal({
  target,
  close,
  onSetConditions,
}: {
  target: Target;
  close: () => void;
  onSetConditions?: () => void;
}) {
  const config = useConfig();
  const ready = !!config.data?.tourismConfigured;
  const [draft, setDraft] = useState(target.name);
  const [search, setSearch] = useState({ query: target.name, page: 1 });
  const [chosen, setChosen] = useState<TourismContent | null>(null);
  const resultsRef = useRef<HTMLElement>(null);
  const detailRef = useRef<HTMLElement>(null);
  const revealDetail = useCallback(() => {
    detailRef.current?.scrollIntoView({
      behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches
        ? "auto"
        : "smooth",
      block: "start",
    });
  }, []);
  const results = useQuery({
    queryKey: ["tourism-search", target.region, search.query, search.page],
    queryFn: () =>
      api<TourismSearch>(
        `/catalog/tourism/search?region=${encodeURIComponent(target.region)}&query=${encodeURIComponent(search.query)}&page=${search.page}`,
      ),
    enabled: ready,
    retry: false,
    staleTime: 300000,
  });
  const detail = useQuery({
    queryKey: ["tourism-detail", chosen?.id],
    queryFn: () => api<TourismDetail>(`/catalog/tourism/${chosen!.id}`),
    enabled: ready && !!chosen,
    retry: false,
    staleTime: 1800000,
  });
  useEffect(() => {
    // 로딩 상태는 높이가 낮아 아래쪽에서 더 스크롤할 수 없을 수 있습니다.
    // 응답/오류가 표시된 뒤에도 다시 이동해 긴 목록 아래의 안내를 바로 읽게 합니다.
    if (chosen) revealDetail();
  }, [chosen?.id, detail.data, detail.isError, revealDetail]);
  function submit(event: FormEvent) {
    event.preventDefault();
    if (!draft.trim() || results.isFetching) return;
    setChosen(null);
    if (draft.trim() === search.query && search.page === 1)
      void results.refetch();
    else setSearch({ query: draft.trim(), page: 1 });
  }
  function page(next: number) {
    setChosen(null);
    setSearch({ ...search, page: next });
  }
  const dirty = draft.trim() !== search.query;
  return (
    <Modal title="운영시간·이용정보" onClose={close} className="tourism-modal">
      <div className="tourism-target">
        <span className="small muted">찾고 있는 장소 · {target.region}</span>
        <strong>{target.name}</strong>
        <a
          className="text-button"
          href={target.url}
          target="_blank"
          rel="noreferrer"
        >
          지도 상세·리뷰 보기
          <ArrowUpRight size={15} />
        </a>
      </div>
      <p className="muted">
        관광공사에 등록된 정보를 찾아요. 이름이 비슷할 수 있으니 주소와 장소
        종류를 확인한 뒤 선택해 주세요.
      </p>
      {config.isPending ? (
        <Loading text="관광정보 연결 확인 중" />
      ) : config.error ? (
        <ErrorState error={config.error} retry={() => config.refetch()} />
      ) : !ready ? (
        <div className="notice">
          <BookOpen size={19} />
          <span>
            관광공사 정보 연결을 준비 중이에요. 지금은 위의 지도 상세에서 이용
            시간과 요금을 확인해 주세요.
          </span>
        </div>
      ) : (
        <>
          <form onSubmit={submit} className="tourism-search-form">
            <label>
              관광정보 검색어
              <input
                value={draft}
                maxLength={80}
                required
                onChange={(e) => setDraft(e.target.value)}
                placeholder="장소 이름을 짧게 검색해 보세요"
              />
            </label>
            <button
              className="button primary"
              disabled={results.isFetching || !draft.trim()}
            >
              <Search size={17} />
              {results.isFetching ? "검색 중" : "검색"}
            </button>
          </form>
          {dirty && (
            <p className="small muted" role="status">
              검색어를 바꿨어요. 검색 버튼을 눌러 새 결과를 확인해 주세요.
            </p>
          )}
          <section
            ref={resultsRef}
            className="tourism-results"
            aria-label="관광공사 장소 선택"
          >
            <h3>1. 같은 장소를 선택해 주세요</h3>
            {results.isPending ? (
              <Loading text="관광공사 장소 검색 중" />
            ) : results.error ? (
              <ErrorState
                error={results.error}
                retry={() => results.refetch()}
              />
            ) : (
              results.data && (
                <>
                  <p className="small muted">
                    공식 검색 {results.data.totalCount}건 · {search.page}페이지
                    · 현재 지역의 결과 {results.data.items.length}곳
                  </p>
                  {!results.data.items.length ? (
                    <Empty
                      title="이 검색어로 찾은 관광정보가 없어요"
                      text="지점명이나 괄호를 빼고 검색해 보세요. 등록되지 않은 장소는 지도 상세에서 확인할 수 있어요."
                    />
                  ) : (
                    <div className="tourism-candidates">
                      {results.data.items.map((c) => (
                        <button
                          className={
                            "tourism-candidate " +
                            (chosen?.id === c.id ? "selected" : "")
                          }
                          key={c.id}
                          aria-pressed={chosen?.id === c.id}
                          onClick={() => {
                            setChosen(c);
                            // 결과 목록으로 돌아온 뒤 같은 장소를 다시 눌러도 안내로 이동합니다.
                            if (chosen?.id === c.id) revealDetail();
                          }}
                        >
                          <span className="badge subtle">{c.category}</span>
                          <strong>{c.title}</strong>
                          <span className="tourism-address">
                            <MapPin size={15} />
                            {c.address ||
                              "주소 미제공 · 같은 장소인지 별도 확인"}
                          </span>
                          <span className="tourism-candidate-action">
                            {chosen?.id === c.id ? (
                              <>
                                <Check size={16} />
                                선택한 장소
                              </>
                            ) : (
                              <>
                                이 장소 정보 보기
                                <ArrowRight size={15} />
                              </>
                            )}
                          </span>
                        </button>
                      ))}
                    </div>
                  )}
                  {results.data.totalCount > 20 && (
                    <nav
                      className="tourism-pagination"
                      aria-label="관광정보 검색 페이지"
                    >
                      <button
                        className="button secondary"
                        disabled={
                          search.page <= 1 || results.isFetching || dirty
                        }
                        onClick={() => page(search.page - 1)}
                      >
                        <ArrowLeft size={16} />
                        이전 결과
                      </button>
                      <span>{search.page}페이지</span>
                      <button
                        className="button secondary"
                        disabled={
                          search.page >= 50 ||
                          search.page * 20 >= results.data.totalCount ||
                          results.isFetching ||
                          dirty
                        }
                        onClick={() => page(search.page + 1)}
                      >
                        다음 결과
                        <ArrowRight size={16} />
                      </button>
                    </nav>
                  )}
                </>
              )
            )}
          </section>
          {chosen && (
            <section
              ref={detailRef}
              className="tourism-detail"
              aria-label="선택한 관광정보"
              aria-live="polite"
            >
              <h3>2. 방문 전 이용 안내를 확인해요</h3>
              <button
                className="text-button tourism-return"
                onClick={() =>
                  resultsRef.current?.scrollIntoView({
                    behavior: "auto",
                    block: "start",
                  })
                }
              >
                <ArrowLeft size={15} />
                장소 선택으로 돌아가기
              </button>
              {detail.isPending ? (
                <Loading text="선택한 장소의 이용 안내 확인 중" />
              ) : detail.error ? (
                <ErrorState
                  error={detail.error}
                  retry={() => detail.refetch()}
                />
              ) : (
                detail.data && (
                  <>
                    <TourismFacts detail={detail.data} />
                    {onSetConditions && (
                      <div className="tourism-set-conditions">
                        <p className="small muted">
                          방문 날짜에 맞는 안내를 확인했다면 시간과 예상 비용을
                          직접 정해 주세요. 원문을 자동으로 저장하지 않아요.
                        </p>
                        <button
                          className="button primary"
                          onClick={onSetConditions}
                        >
                          확인 후 방문 조건 설정
                        </button>
                      </div>
                    )}
                  </>
                )
              )}
            </section>
          )}
        </>
      )}
    </Modal>
  );
}

/** 제공 여부는 API 필드 기준입니다. 원문을 '오늘 영업 중/무료 확정' 등으로 재해석하지 않습니다. */
function TourismFacts({ detail }: { detail: TourismDetail }) {
  const updated = detail.content.providerUpdatedAt;
  const formatted = /^\d{14}$/.test(updated)
    ? `${updated.slice(0, 4)}.${updated.slice(4, 6)}.${updated.slice(6, 8)} ${updated.slice(8, 10)}:${updated.slice(10, 12)}`
    : updated;
  return (
    <>
      <div className="tourism-selected">
        <span className="badge">{detail.content.category}</span>
        <h4>{detail.content.title}</h4>
        <p>{detail.content.address || "주소 미제공"}</p>
      </div>
      <p className="small muted">
        관광공사가 제공한 안내예요. 여행 날짜의 임시 휴무·입장 마감·요금 변경은
        방문 전에 다시 확인해 주세요.
      </p>
      <div className="tourism-facts">
        {[detail.hours, detail.restDays, detail.fees].map((f) => (
          <TourismFact key={f.label} field={f} />
        ))}
      </div>
      {detail.additional.length > 0 && (
        <details className="tourism-additional">
          <summary>문의·주차 등 추가 안내</summary>
          {detail.additional.map((f) => (
            <div key={f.label}>
              <strong>{f.label}</strong>
              <p>{f.text}</p>
            </div>
          ))}
        </details>
      )}
      {detail.overview && (
        <details className="tourism-additional">
          <summary>장소 소개 더 보기</summary>
          <p>{detail.overview}</p>
        </details>
      )}
      <p className="small muted">
        이용요금에는 종류·할인 조건 등이 포함될 수 있어요. 여행 예산·예약 상태는
        바뀌지 않아요.
      </p>
      <div className="tourism-source">
        <strong>{detail.source}</strong>
        <span>
          조회 시각 {new Date(detail.queriedAt).toLocaleString("ko-KR")}
        </span>
        <span>공급자 수정 표기 {formatted || "미제공"}</span>
      </div>
      <div className="tourism-detail-actions">
        {detail.homepageUrl && (
          <a
            className="button secondary"
            href={detail.homepageUrl}
            target="_blank"
            rel="noreferrer"
          >
            장소 홈페이지
            <ArrowUpRight size={16} />
          </a>
        )}
        <a
          className="text-button"
          href={detail.sourceUrl}
          target="_blank"
          rel="noreferrer"
        >
          관광공사 데이터 출처
          <ArrowUpRight size={15} />
        </a>
      </div>
    </>
  );
}
function TourismFact({ field }: { field: TourismField }) {
  return (
    <article className="tourism-fact">
      <div>
        <h4>{field.label}</h4>
        <span
          className={
            "badge " + (field.status === "NOT_PROVIDED" ? "subtle" : "")
          }
        >
          {field.status === "PROVIDED" ? "제공된 안내" : "미제공"}
        </span>
      </div>
      <p>
        {field.status === "PROVIDED"
          ? field.text
          : "관광공사 미제공 · 방문 전 별도 확인"}
      </p>
    </article>
  );
}
