import { useCallback, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Pencil, Trash2, Users, Loader2 } from "lucide-react";
import { api, ApiError } from "../../shared/api";
import { ErrorState, Modal } from "../../shared/ui";
import { useToast } from "../../shared/providers";
import type { Post } from "../../shared/types";
import PostEditor from "./PostEditor";

/** 작성자 도구입니다. 역할 표시는 편의 기능이며 서버가 작성자·상태·버전을 다시 검사합니다. */
export default function PostManagement({
  post,
  refresh,
  removed,
}: {
  post: Post;
  refresh: () => Promise<Post | undefined>;
  removed: () => void;
}) {
  const [editing, setEditing] = useState<Post | null>(null);
  const [deleting, setDeleting] = useState<Post | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [error, setError] = useState<Error | null>(null);
  const client = useQueryClient();
  const toast = useToast();
  const closeEdit = useCallback(() => setEditing(null), []);
  const closeDelete = useCallback(() => {
    if (!busyRef.current) {
      setDeleting(null);
      setError(null);
    }
  }, []);
  const stale =
    !!deleting &&
    (deleting.version !== post.version ||
      (error instanceof ApiError && error.status === 409));
  async function update(action: "delete" | "status") {
    if (busyRef.current || (action === "delete" && stale)) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      await api(
        "/community/posts/" + post.id + (action === "status" ? "/status" : ""),
        {
          method: action === "delete" ? "DELETE" : "PUT",
          body:
            action === "delete"
              ? { version: deleting?.version }
              : {
                  version: post.version,
                  status: post.status === "OPEN" ? "CLOSED" : "OPEN",
                },
        },
      );
      await client.invalidateQueries({ queryKey: ["posts"] });
      await client.invalidateQueries({ queryKey: ["post-trash"] });
      if (action === "delete") {
        removed();
        client.removeQueries({ queryKey: ["post", post.id] });
        toast("내 글 휴지통으로 옮겼어요.");
      } else {
        await refresh();
        toast(
          post.status === "OPEN"
            ? "동행 모집을 마감했어요."
            : "동행 모집을 다시 열었어요.",
        );
      }
    } catch (e) {
      setError(e as Error);
      if (action === "status") {
        toast((e as Error).message, true);
      }
      // 다른 창의 삭제/운영진 숨김은 다음 폴링까지 기다리지 않고 공개 여부를 다시 확인합니다.
      if (
        action === "status" ||
        (e instanceof ApiError && (e.status === 403 || e.status === 404))
      )
        await refresh();
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }
  return (
    <section className="post-management" aria-label="내 글 관리">
      <h2>내 이야기 관리</h2>
      <p className="field-help">
        수정한 내용은 바로 공개돼요. 삭제한 글은 커뮤니티의 내 글 휴지통에서
        복원할 수 있어요.
      </p>
      <div className="post-owner-actions">
        <button
          className="button secondary"
          disabled={busy}
          onClick={() => setEditing(post)}
        >
          <Pencil size={17} />글 수정하기
        </button>
        {post.category === "동행" && (
          <button
            className="button secondary"
            disabled={busy}
            onClick={() => update("status")}
          >
            <Users size={17} />
            {post.status === "OPEN" ? "모집 마감하기" : "다시 모집하기"}
          </button>
        )}
        <button
          className="button secondary"
          disabled={busy}
          onClick={() => {
            setError(null);
            setDeleting(post);
          }}
        >
          <Trash2 size={17} />글 삭제하기
        </button>
      </div>
      {editing && (
        <PostEditor
          key={editing.version}
          initial={editing}
          latestVersion={post.version}
          close={closeEdit}
          reload={async () => {
            const p = await refresh();
            if (p) setEditing(p);
          }}
          done={async () => {
            await refresh();
            await client.invalidateQueries({ queryKey: ["posts"] });
            setEditing(null);
            toast("이야기를 수정했어요.");
          }}
        />
      )}
      {deleting && (
        <Modal
          title="이 글을 휴지통으로 옮길까요?"
          onClose={closeDelete}
          className="post-delete-modal"
        >
          <div className="post-delete-target">
            <span className="badge">
              {deleting.category} · {deleting.region}
            </span>
            <h3>{deleting.title}</h3>
          </div>
          <ul className="post-delete-effects">
            <li>공개 목록과 상세에서 숨겨지고, 새 댓글을 남길 수 없어요.</li>
            <li>
              본문·기존 댓글은 보관돼요. 내 글 휴지통에서 다시 공개할 수 있어요.
            </li>
            <li>동행 모집은 마감돼요. 복원 후 직접 다시 모집할 수 있어요.</li>
            <li>
              이미 나누던 개인 대화와 별도로 초대받은 여행 참여는 유지돼요.
            </li>
          </ul>
          {error && <ErrorState error={error} />}{" "}
          {stale && (
            <div className="notice danger" role="status">
              글이 변경되어 삭제할 수 없어요. 최신 글을 확인한 뒤 다시 선택해
              주세요.
            </div>
          )}
          <div className="post-form-actions">
            {stale ? (
              <button
                className="button secondary"
                onClick={async () => {
                  await refresh();
                  closeDelete();
                }}
              >
                최신 글 확인하기
              </button>
            ) : (
              <button
                className="button danger-button"
                disabled={busy}
                onClick={() => update("delete")}
              >
                {busy ? (
                  <Loader2 size={17} className="spin" />
                ) : (
                  <Trash2 size={17} />
                )}
                휴지통으로 옮기기
              </button>
            )}
            <button
              className="button secondary"
              disabled={busy}
              onClick={closeDelete}
            >
              글 유지하기
            </button>
          </div>
        </Modal>
      )}
    </section>
  );
}
