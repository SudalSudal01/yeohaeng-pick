import { useId, useState } from "react";
import { Loader2 } from "lucide-react";
import { api } from "../../shared/api";
import { useToast } from "../../shared/providers";

export type Report = {
  id: string;
  targetType: "POST" | "MESSAGE" | "USER";
  targetId: string;
  reason: string;
  status: "OPEN" | "RESOLVED";
  resolution: string;
  createdAt: string;
  capturedAt: string | null;
  resolvedAt: string | null;
  evidence: {
    nickname?: string;
    title?: string;
    body?: string;
    category?: string;
    region?: string;
    version?: number;
  } | null;
};
const labels = { POST: "게시글", MESSAGE: "메시지", USER: "회원" };

/** DB의 현지 시각 문자열은 표시할 때만 읽습니다. 오프셋이 없는 값에 UTC를 임의로 덧붙이지 않습니다. */
function time(value: string | null) {
  if (!value) return "시각 기록 없음";
  const date = new Date(value.replace(" ", "T"));
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat("ko-KR", {
        dateStyle: "medium",
        timeStyle: "short",
      }).format(date);
}

/** 증거와 처리 입력을 분리합니다. 이전 신고에 지금의 원문을 보여주거나 자동으로 숨김 처리하지 않습니다. */
export default function ReportCard({
  report: r,
  refresh,
}: {
  report: Report;
  refresh: () => Promise<unknown>;
}) {
  const id = useId();
  const toast = useToast();
  const [note, setNote] = useState("");
  const [hidePost, setHidePost] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  return (
    <article className="report-card">
      <div className="report-heading">
        <span className={"badge " + (r.status === "OPEN" ? "" : "green")}>
          {labels[r.targetType]} 신고 ·{" "}
          {r.status === "OPEN" ? "처리 대기" : "처리 완료"}
        </span>
        <span className="report-meta">접수 {time(r.createdAt)}</span>
      </div>
      <div className="report-reason">
        <h3>신고 사유</h3>
        <p>{r.reason}</p>
      </div>
      {r.evidence ? (
        <details className="report-evidence" open>
          <summary>신고 당시 내용</summary>
          <div className="report-evidence-content">
            <p className="report-meta">
              {r.evidence.nickname || "이름 기록 없음"}
              {r.evidence.category && ` · ${r.evidence.category}`}
              {r.evidence.region && ` · ${r.evidence.region}`}
              {r.evidence.version != null && ` · 글 버전 ${r.evidence.version}`}
            </p>
            {r.evidence.title && <h3>{r.evidence.title}</h3>}
            {r.evidence.body != null && (
              <div
                className="report-evidence-body"
                tabIndex={0}
                role="region"
                aria-label="보존된 신고 내용"
              >
                {r.evidence.body}
              </div>
            )}
            <p className="report-meta">
              보존 {time(r.capturedAt)} · 이후 수정·삭제와 별도로 보존해요.
            </p>
          </div>
        </details>
      ) : (
        <p className="notice">
          이전 신고라 당시 내용이 보존되지 않았어요. 현재 내용을 신고 당시의
          증거로 표시하지 않아요.
        </p>
      )}
      {r.status === "OPEN" ? (
        <form
          className="report-resolution"
          onSubmit={async (e) => {
            e.preventDefault();
            if (busy) return;
            setBusy(true);
            setError("");
            try {
              await api(`/admin/reports/${r.id}/resolve`, {
                method: "POST",
                body: { resolution: note.trim(), hidePost },
              });
              toast("처리 결과와 감사 기록을 저장했어요.");
              await refresh();
            } catch (error) {
              setError((error as Error).message);
              // 재조회로 처리 양식이 사라져도 다른 관리자의 선처리 안내는 알림에 남깁니다.
              toast((error as Error).message, true);
              // 다른 관리자가 먼저 처리했을 때 오래된 실행 버튼을 계속 보여주지 않습니다.
              await refresh();
            } finally {
              setBusy(false);
            }
          }}
        >
          <label htmlFor={id}>처리 사유</label>
          <textarea
            id={id}
            value={note}
            onChange={(e) => setNote(e.target.value)}
            maxLength={1000}
            required
            rows={3}
            disabled={busy}
            placeholder="확인한 내용과 조치 사유를 남겨 주세요."
            aria-describedby={id + "-help"}
          />
          {r.targetType === "POST" && (
            <label className="report-hide-choice">
              <input
                type="checkbox"
                checked={hidePost}
                onChange={(e) => setHidePost(e.target.checked)}
                disabled={busy}
              />
              <span>현재 게시글도 숨기기</span>
            </label>
          )}
          <p id={id + "-help"} className="field-help">
            처리 후에는 변경할 수 없어요.{" "}
            {r.targetType === "POST"
              ? "숨김을 선택하면 현재 글을 비공개로 전환하고, 이미 삭제된 글도 공개하지 않아요."
              : "신고된 내용만 확인하며 다른 개인 대화는 열람하지 않아요."}
          </p>
          {error && (
            <p className="notice danger" role="alert">
              {error}
            </p>
          )}
          <div className="report-actions">
            <button className="button primary" disabled={busy || !note.trim()}>
              {busy && <Loader2 size={18} className="spin" />}
              {busy
                ? "저장 중…"
                : hidePost
                  ? "글 숨김 · 처리 완료"
                  : "처리 완료로 저장"}
            </button>
          </div>
        </form>
      ) : (
        <div className="report-result">
          <h3>처리 결과</h3>
          <p>{r.resolution}</p>
          <p className="report-meta">{time(r.resolvedAt)}</p>
        </div>
      )}
    </article>
  );
}
