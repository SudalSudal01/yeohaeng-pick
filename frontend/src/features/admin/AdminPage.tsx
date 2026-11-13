import { useEffect, useRef } from "react";
import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, ArrowRight, ShieldCheck } from "lucide-react";
import { useSearchParams } from "react-router-dom";
import ReportCard, { type Report } from "./ReportCard";
import { api } from "../../shared/api";
import { useAuth, useConfig } from "../../shared/providers";
import { Empty, Loading, ErrorState } from "../../shared/ui";
import "./admin.css";

type ReportStatus = "OPEN" | "RESOLVED" | "ALL";
type ReportPage = {
  items: Report[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
};
/** 운영자 화면도 서버의 ROLE_ADMIN 검사를 통과해야 데이터를 받을 수 있습니다. 일반 회원에게 요청 자체를 보내지 않습니다. */
export default function AdminPage() {
  const { user } = useAuth();
  const config = useConfig();
  const [params, setParams] = useSearchParams();
  const requestedStatus = params.get("status");
  const status: ReportStatus =
    requestedStatus === "RESOLVED" || requestedStatus === "ALL"
      ? requestedStatus
      : "OPEN";
  const rawPage = params.get("page") || "1";
  const page = /^[1-9]\d*$/.test(rawPage)
    ? Math.min(Number(rawPage), 1000000)
    : 1;
  const resultsRef = useRef<HTMLElement>(null);
  const focusAfterPage = useRef(false);
  const change = (nextStatus: ReportStatus, nextPage = 1, replace = false) => {
    const next = new URLSearchParams();
    if (nextStatus !== "OPEN") next.set("status", nextStatus);
    if (nextPage > 1) next.set("page", String(nextPage));
    setParams(next, { replace });
  };
  const reports = useQuery({
    queryKey: ["reports", status, page],
    queryFn: () =>
      api<ReportPage>(
        `/admin/reports/search?status=${status}&page=${page}&pageSize=20`,
      ),
    enabled: user?.role === "ADMIN",
    retry: false,
  });
  useEffect(() => {
    if (
      reports.data &&
      !reports.isFetching &&
      !reports.error &&
      reports.data.page !== page
    )
      change(status, reports.data.page, true);
  }, [reports.data, reports.isFetching, reports.error, page, status]);
  useEffect(() => {
    if (focusAfterPage.current && !reports.isFetching) {
      focusAfterPage.current = false;
      resultsRef.current?.focus({ preventScroll: true });
      resultsRef.current?.scrollIntoView({ block: "start" });
    }
  }, [reports.isFetching]);
  if (user?.role !== "ADMIN")
    return (
      <Empty
        title="관리자 전용 화면이에요"
        text="운영 권한이 있는 계정으로 이용해 주세요."
      />
    );
  return (
    <div className="page">
      <div className="page-heading">
        <span className="eyebrow">TRAVELPICK OPERATIONS</span>
        <h1>
          <ShieldCheck size={32} /> 서비스 운영
        </h1>
        <p>신고된 내용과 서비스 연결 상태를 확인해요.</p>
      </div>
      {config.isPending ? (
        <Loading text="서비스 설정을 확인하고 있어요" />
      ) : config.error ? (
        <ErrorState error={config.error} retry={() => config.refetch()} />
      ) : (
        <div className="integration-status">
          {[
            { label: "지도 SDK", ready: !!config.data?.mapKey },
            { label: "AI 추천", ready: !!config.data?.aiConfigured },
            { label: "메일 발송", ready: !!config.data?.emailConfigured },
            { label: "운영 데이터", ready: !config.data?.sampleData },
          ].map((s) => (
            <div key={s.label}>
              <span className={"status-dot " + (s.ready ? "ready" : "")} />
              <strong>{s.label}</strong>
              <span>{s.ready ? "설정됨" : "설정 필요"}</span>
            </div>
          ))}
        </div>
      )}
      <section
        className="panel admin-report-results"
        ref={resultsRef}
        tabIndex={-1}
        aria-label="신고 처리 목록"
        aria-busy={reports.isFetching}
      >
        <h2>접수된 신고</h2>
        <p className="field-help">
          처리 대기부터 확인해요. 오래된 신고도 페이지를 이동해 찾고, 당시
          내용과 신고 사유를 확인한 뒤 조치를 선택해 주세요.
        </p>
        <div
          className="admin-report-filters"
          role="group"
          aria-label="신고 처리 상태"
        >
          {(
            [
              ["OPEN", "처리 대기"],
              ["RESOLVED", "처리 완료"],
              ["ALL", "전체 신고"],
            ] as const
          ).map(([value, label]) => (
            <button
              key={value}
              className="button secondary"
              aria-pressed={status === value}
              onClick={() => change(value)}
            >
              {label}
            </button>
          ))}
        </div>
        {reports.isPending ? (
          <Loading />
        ) : reports.error ? (
          <ErrorState error={reports.error} retry={() => reports.refetch()} />
        ) : !reports.data?.items.length ? (
          <p className="muted" role="status">
            {status === "OPEN"
              ? "처리를 기다리는 신고가 없어요."
              : "해당 상태의 신고가 없어요."}
          </p>
        ) : (
          <>
            <p className="admin-report-summary" role="status">
              {reports.data.total.toLocaleString()}건 ·{" "}
              {(reports.data.page - 1) * reports.data.pageSize + 1}–
              {(reports.data.page - 1) * reports.data.pageSize +
                reports.data.items.length}
              번째 신고
            </p>
            {reports.data.items.map((r) => (
              <ReportCard
                key={r.id}
                report={r}
                refresh={() => reports.refetch()}
              />
            ))}
            {reports.data.totalPages > 1 && (
              <nav
                className="admin-report-pagination"
                aria-label="신고 페이지 이동"
              >
                <button
                  className="button secondary"
                  disabled={reports.data.page <= 1}
                  onClick={() => {
                    focusAfterPage.current = true;
                    change(status, reports.data!.page - 1);
                  }}
                >
                  <ArrowLeft size={17} />
                  이전
                </button>
                <span aria-current="page">
                  {reports.data.page.toLocaleString()} /{" "}
                  {reports.data.totalPages.toLocaleString()} 페이지
                </span>
                <button
                  className="button secondary"
                  disabled={reports.data.page >= reports.data.totalPages}
                  onClick={() => {
                    focusAfterPage.current = true;
                    change(status, reports.data!.page + 1);
                  }}
                >
                  다음
                  <ArrowRight size={17} />
                </button>
              </nav>
            )}
          </>
        )}
      </section>
    </div>
  );
}
