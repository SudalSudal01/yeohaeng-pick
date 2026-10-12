import { useCallback, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { RotateCcw, Loader2 } from "lucide-react";
import { api } from "../../shared/api";
import type { DeletedPost } from "../../shared/types";
import { ErrorState, Loading, Modal } from "../../shared/ui";
import { useToast } from "../../shared/providers";

/** 삭제된 글은 본인에게만 표시합니다. 복원해도 운영진 숨김·기존 개인 대화 설정을 우회하지 않습니다. */
export default function PostTrashModal({
  close,
  restored,
}: {
  close: () => void;
  restored: (id: string) => void;
}) {
  const client = useQueryClient();
  const toast = useToast();
  const [busy, setBusy] = useState<string | null>(null);
  const busyRef = useRef(false);
  const [error, setError] = useState<Error | null>(null);
  const safeClose = useCallback(() => {
    if (!busyRef.current) close();
  }, [close]);
  const query = useQuery({
    queryKey: ["post-trash"],
    queryFn: () => api<DeletedPost[]>("/community/trash"),
    gcTime: 0,
    retry: false,
  });
  async function restore(p: DeletedPost) {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(p.id);
    setError(null);
    try {
      await api("/community/posts/" + p.id + "/restore", {
        method: "POST",
        body: { version: p.version },
      });
      await client.invalidateQueries({ queryKey: ["posts"] });
      await client.invalidateQueries({ queryKey: ["post", p.id] });
      await client.invalidateQueries({ queryKey: ["post-trash"] });
      toast(
        p.category === "동행"
          ? "글을 복원했어요. 동행 모집은 마감 상태로 돌아와요."
          : "글과 기존 댓글을 복원했어요.",
      );
      restored(p.id);
    } catch (e) {
      setError(e as Error);
      await query.refetch();
    } finally {
      busyRef.current = false;
      setBusy(null);
    }
  }
  return (
    <Modal
      title="내 글 휴지통"
      onClose={safeClose}
      className="post-trash-modal"
    >
      <p className="post-trash-intro">
        내가 삭제한 글만 보여요. 복원하면 본문과 기존 댓글이 다시 공개돼요. 동행
        모집은 마감 상태로 돌아오며, 글에서 직접 다시 모집할 수 있어요.
      </p>
      {error && <ErrorState error={error} />}{" "}
      {query.isPending ? (
        <Loading />
      ) : query.error ? (
        <ErrorState error={query.error} retry={() => query.refetch()} />
      ) : !query.data?.length ? (
        <div className="post-trash-empty">
          <RotateCcw size={30} />
          <h3>삭제한 이야기가 없어요</h3>
          <p>삭제한 글은 이곳에서 다시 찾을 수 있어요.</p>
        </div>
      ) : (
        <div className="post-trash-list">
          {query.data.map((p) => (
            <article className="post-trash-card" key={p.id}>
              <span className="badge subtle">
                {p.category} · {p.region}
              </span>
              <h3>{p.title}</h3>
              <p>{new Date(p.deletedAt).toLocaleDateString("ko-KR")} 삭제</p>
              <p className="field-help">
                {p.hidden
                  ? "운영진이 숨긴 글은 복원할 수 없어요."
                  : p.category === "동행"
                    ? "복원 후 모집 마감 상태로 공개돼요."
                    : "복원하면 기존 댓글과 함께 공개돼요."}
              </p>
              <button
                className="button secondary"
                disabled={!!busy || p.hidden}
                onClick={() => restore(p)}
              >
                {busy === p.id ? (
                  <Loader2 size={17} className="spin" />
                ) : (
                  <RotateCcw size={17} />
                )}
                이 글 복원하기
              </button>
            </article>
          ))}
        </div>
      )}
      <div className="post-form-actions">
        <button
          className="button secondary full"
          disabled={!!busy}
          onClick={safeClose}
        >
          커뮤니티로 돌아가기
        </button>
      </div>
    </Modal>
  );
}
