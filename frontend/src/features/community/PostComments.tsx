import { useCallback, useRef, useState } from "react";
import { Loader2, Pencil, Trash2 } from "lucide-react";
import { api, ApiError } from "../../shared/api";
import { useAuth, useToast } from "../../shared/providers";
import { ErrorState, Modal } from "../../shared/ui";
import type { CommunityComment, Post } from "../../shared/types";

/** 댓글 목록과 관리 창을 분리해 폴링으로 댓글이 사라져도 진행 중인 확인·초안이 조용히 없어지지 않게 합니다. */
export default function PostComments({
  post,
  refresh,
}: {
  post: Post;
  refresh: () => Promise<Post | undefined>;
}) {
  const { user } = useAuth();
  const toast = useToast();
  const [target, setTarget] = useState<{
    comment: CommunityComment;
    action: "edit" | "delete";
  } | null>(null);
  const [body, setBody] = useState("");
  const [error, setError] = useState<Error | null>(null);
  // 최신 조회 실패가 충돌 표시를 지워 오래된 버전의 저장 버튼을 다시 열지 않도록 별도로 기억합니다.
  const [conflicted, setConflicted] = useState(false);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const close = useCallback(() => {
    if (!busyRef.current) setTarget(null);
  }, []);
  const current = post.comments?.find((c) => c.id === target?.comment.id);
  const missing = !!target && !current;
  const stale =
    !!target &&
    ((!!current && current.version !== target.comment.version) || conflicted);

  function open(comment: CommunityComment, action: "edit" | "delete") {
    setTarget({ comment, action });
    setBody(comment.body);
    setError(null);
    setConflicted(false);
  }

  async function reload() {
    if (!target || busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    try {
      const latest = await refresh();
      if (!latest) {
        setError(
          new Error(
            "최신 댓글을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.",
          ),
        );
        return;
      }
      const comment = latest.comments?.find((c) => c.id === target.comment.id);
      if (comment) {
        setTarget({ ...target, comment });
        setBody(comment.body);
        setError(null);
        setConflicted(false);
      }
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (
      !target ||
      busyRef.current ||
      stale ||
      missing ||
      (target.action === "edit" && !body.trim())
    )
      return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      await api(`/community/posts/${post.id}/comments/${target.comment.id}`, {
        method: target.action === "edit" ? "PUT" : "DELETE",
        body:
          target.action === "edit"
            ? { version: target.comment.version, body }
            : { version: target.comment.version },
      });
      await refresh();
      setTarget(null);
      toast(
        target.action === "edit" ? "댓글을 수정했어요." : "댓글을 삭제했어요.",
      );
    } catch (e) {
      setError(e as Error);
      if (e instanceof ApiError && e.status === 409) setConflicted(true);
      // 권한·공개 상태·버전 변화는 즉시 확인합니다. 실패한 초안은 그대로 남겨 복사할 수 있습니다.
      if (e instanceof ApiError && [403, 404, 409].includes(e.status))
        await refresh();
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }

  return (
    <>
      {!post.comments?.length && (
        <p className="comment-empty">
          아직 이야기가 없어요. 첫 댓글을 남겨 보세요.
        </p>
      )}
      {post.comments?.map((c) => (
        <div className="comment managed-comment" key={c.id}>
          <span className="avatar small-avatar">{c.nickname.slice(0, 1)}</span>
          <div className="comment-content">
            <strong>{c.nickname}</strong>
            <div className="comment-meta">
              <time dateTime={c.createdAt}>
                {new Date(c.createdAt).toLocaleString("ko-KR")}
              </time>
              {c.updatedAt && <span> · 수정됨</span>}
            </div>
            <p>{c.body}</p>
            {user?.id === c.authorId && (
              <div className="comment-tools" aria-label="내 댓글 관리">
                <button className="text-button" onClick={() => open(c, "edit")}>
                  <Pencil size={16} />
                  댓글 수정
                </button>
                <button
                  className="text-button"
                  onClick={() => open(c, "delete")}
                >
                  <Trash2 size={16} />
                  댓글 삭제
                </button>
              </div>
            )}
          </div>
        </div>
      ))}
      {target && (
        <Modal
          title={
            target.action === "edit"
              ? "내 댓글 수정하기"
              : "이 댓글을 삭제할까요?"
          }
          onClose={close}
          className="comment-management-modal"
        >
          <form onSubmit={submit}>
            {target.action === "edit" ? (
              <label className="comment-edit-field">
                댓글 내용
                <textarea
                  value={body}
                  onChange={(e) => setBody(e.target.value)}
                  rows={6}
                  maxLength={2000}
                  required
                  disabled={busy}
                  readOnly={missing}
                  aria-describedby="comment-edit-help"
                  aria-label="댓글 내용"
                />
                <span id="comment-edit-help" className="field-help">
                  수정한 내용은 바로 공개돼요. 댓글은 최대 2,000자까지 쓸 수
                  있어요.
                </span>
                <span className="comment-meta">
                  {body.length.toLocaleString()} / 2,000자
                </span>
              </label>
            ) : (
              <>
                <blockquote className="comment-delete-preview">
                  {target.comment.body}
                </blockquote>
                <p className="field-help">
                  댓글은 공개 목록에서 사라지고 직접 복원할 수 없어요. 게시글과
                  다른 사람의 댓글은 유지돼요.
                </p>
              </>
            )}
            {error && <ErrorState error={error} />}
            {missing ? (
              <div className="notice danger" role="status">
                이미 삭제된 댓글이에요. 작성 중인 내용은 복사해 둘 수 있어요.
              </div>
            ) : (
              stale && (
                <div className="notice danger" role="status">
                  다른 창에서 댓글이 변경됐어요. 필요한 초안을 복사한 뒤 최신
                  댓글을 불러와 주세요.
                </div>
              )
            )}
            <div className="post-form-actions comment-form-actions">
              {stale && !missing ? (
                <button
                  type="button"
                  className="button secondary"
                  disabled={busy}
                  onClick={reload}
                >
                  최신 댓글 불러오기
                </button>
              ) : (
                !missing && (
                  <button
                    className={
                      "button " +
                      (target.action === "edit" ? "primary" : "danger-button")
                    }
                    disabled={
                      busy || (target.action === "edit" && !body.trim())
                    }
                  >
                    {busy && <Loader2 size={17} className="spin" />}
                    {target.action === "edit"
                      ? "수정 저장하기"
                      : "댓글 삭제하기"}
                  </button>
                )
              )}
              <button
                type="button"
                className="button secondary"
                disabled={busy}
                onClick={close}
              >
                {missing ? "닫기" : "취소"}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </>
  );
}
