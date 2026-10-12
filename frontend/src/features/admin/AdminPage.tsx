import { useQuery } from "@tanstack/react-query";
import { ShieldCheck } from "lucide-react";
import ReportCard, { type Report } from "./ReportCard";
import { api } from "../../shared/api";
import { useAuth, useConfig } from "../../shared/providers";
import { Empty, Loading, ErrorState } from "../../shared/ui";
/** 운영자 화면도 서버의 ROLE_ADMIN 검사를 통과해야 데이터를 받을 수 있습니다. 일반 회원에게 요청 자체를 보내지 않습니다. */
export default function AdminPage() {
  const { user } = useAuth();
  const config = useConfig();
  const reports = useQuery({
    queryKey: ["reports"],
    queryFn: () => api<Report[]>("/admin/reports"),
    enabled: user?.role === "ADMIN",
  });
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
      <section className="panel">
        <h2>접수된 신고</h2>
        <p className="field-help">
          최근 100건을 보여줘요. 당시 내용과 신고 사유를 확인한 뒤 조치를 선택해
          주세요.
        </p>
        {reports.isPending ? (
          <Loading />
        ) : reports.error ? (
          <ErrorState error={reports.error} />
        ) : !reports.data?.length ? (
          <p className="muted">접수된 신고가 없어요.</p>
        ) : (
          reports.data.map((r) => (
            <ReportCard
              key={r.id}
              report={r}
              refresh={() => reports.refetch()}
            />
          ))
        )}
      </section>
    </div>
  );
}
