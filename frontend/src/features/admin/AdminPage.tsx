import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { ShieldCheck } from "lucide-react";
import { api } from "../../shared/api";
import { useAuth, useConfig, useToast } from "../../shared/providers";
import { Empty, Loading, ErrorState } from "../../shared/ui";
type Report = {
  id: string;
  targetType: string;
  reason: string;
  status: string;
  resolution: string;
  reportedMessage?: { body: string };
};

/** 운영자 화면도 서버의 ROLE_ADMIN 검사를 통과해야 데이터를 받을 수 있습니다. 일반 회원에게 요청 자체를 보내지 않습니다. */
export default function AdminPage() {
  const { user } = useAuth();
  const config = useConfig();
  const toast = useToast();
  const [notes, setNotes] = useState<Record<string, string>>({});
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
        {reports.isPending ? (
          <Loading />
        ) : reports.error ? (
          <ErrorState error={reports.error} />
        ) : !reports.data?.length ? (
          <p className="muted">접수된 신고가 없어요.</p>
        ) : (
          reports.data.map((r) => (
            <article className="report-card" key={r.id}>
              <span className="badge">
                {r.targetType} ·{" "}
                {r.status === "OPEN" ? "처리 대기" : "처리 완료"}
              </span>
              <p>{r.reason}</p>
              {r.reportedMessage && (
                <blockquote>{r.reportedMessage.body}</blockquote>
              )}
              {r.status === "OPEN" ? (
                <form
                  className="inline-form"
                  onSubmit={async (e) => {
                    e.preventDefault();
                    try {
                      await api("/admin/reports/" + r.id + "/resolve", {
                        method: "POST",
                        body: {
                          resolution: notes[r.id],
                          hidePost: r.targetType === "POST",
                        },
                      });
                      await reports.refetch();
                      toast("처리 결과와 감사 기록을 저장했어요.");
                    } catch (e) {
                      toast((e as Error).message, true);
                    }
                  }}
                >
                  <input
                    aria-label="신고 처리 사유"
                    value={notes[r.id] || ""}
                    onChange={(e) =>
                      setNotes({ ...notes, [r.id]: e.target.value })
                    }
                    placeholder={
                      r.targetType === "POST"
                        ? "처리 사유 입력 · 게시글을 숨깁니다"
                        : "처리 사유 입력"
                    }
                    required
                  />
                  <button className="button primary">처리</button>
                </form>
              ) : (
                <p className="muted">{r.resolution}</p>
              )}
            </article>
          ))
        )}
      </section>
    </div>
  );
}
