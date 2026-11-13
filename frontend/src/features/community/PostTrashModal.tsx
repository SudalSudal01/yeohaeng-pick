import { useCallback, useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, ArrowRight, RotateCcw, Loader2 } from "lucide-react";
import { api } from "../../shared/api";
import type { DeletedPost } from "../../shared/types";
import { ErrorState, Loading, Modal } from "../../shared/ui";
import { useToast } from "../../shared/providers";
import { useCommunitySession } from "./useCommunitySession";

type TrashPage = {
  items: DeletedPost[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
};

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
  const currentSession = useCommunitySession();
  const [page, setPage] = useState(1);
  const listRef = useRef<HTMLDivElement>(null);
  const focusAfterPage = useRef(false);
  const [busy, setBusy] = useState<string | null>(null);
  const busyRef = useRef(false);
  const [error, setError] = useState<Error | null>(null);
  const safeClose = useCallback(() => {
    if (!busyRef.current) close();
  }, [close]);
  const query = useQuery({
    queryKey: ["post-trash", page],
    queryFn: () =>
      api<TrashPage>(`/community/trash/search?page=${page}&pageSize=12`),
    gcTime: 0,
    retry: false,
  });
  useEffect(() => {
    if (
      query.data &&
      !query.isFetching &&
      !query.error &&
      query.data.page !== page
    )
      setPage(query.data.page);
    if (focusAfterPage.current && !query.isFetching) {
      focusAfterPage.current = false;
      listRef.current?.focus({ preventScroll: true });
      listRef.current?.scrollIntoView({ block: "start" });
    }
  }, [query.data, query.isFetching, query.error, page]);
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
      if (!currentSession()) return;
      await client.invalidateQueries({ queryKey: ["posts"] });
      await client.invalidateQueries({ queryKey: ["post", p.id] });
      await client.invalidateQueries({ queryKey: ["post-trash"] });
      if (!currentSession()) return;
      toast(
        p.category === "동행"
          ? "글을 복원했어요. 동행 모집은 마감 상태로 돌아와요."
          : "글과 기존 댓글을 복원했어요.",
      );
      restored(p.id);
    } catch (e) {
      if (!currentSession()) return;
      setError(e as Error);
      await query.refetch();
    } finally {
      busyRef.current = false;
      if (currentSession()) setBusy(null);
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
      ) : !query.data?.items.length ? (
        <div className="post-trash-empty">
          <RotateCcw size={30} />
          <h3>삭제한 이야기가 없어요</h3>
          <p>삭제한 글은 이곳에서 다시 찾을 수 있어요.</p>
        </div>
      ) : (
        <>
          <p className="post-trash-summary" role="status">
            삭제한 글 {query.data.total.toLocaleString()}개 ·{" "}
            {query.data.page.toLocaleString()} /{" "}
            {query.data.totalPages.toLocaleString()} 페이지
          </p>
          <div
            className="post-trash-list"
            ref={listRef}
            tabIndex={-1}
            aria-label="내가 삭제한 글"
          >
            {query.data.items.map((p) => (
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
          {query.data.totalPages > 1 && (
            <nav
              className="community-pagination"
              aria-label="내 글 휴지통 페이지 이동"
            >
              <button
                className="button secondary"
                disabled={!!busy || query.data.page <= 1}
                onClick={() => {
                  focusAfterPage.current = true;
                  setPage(query.data!.page - 1);
                }}
              >
                <ArrowLeft size={17} />
                이전
              </button>
              <span aria-current="page">
                {query.data.page.toLocaleString()} /{" "}
                {query.data.totalPages.toLocaleString()} 페이지
              </span>
              <button
                className="button secondary"
                disabled={!!busy || query.data.page >= query.data.totalPages}
                onClick={() => {
                  focusAfterPage.current = true;
                  setPage(query.data!.page + 1);
                }}
              >
                다음
                <ArrowRight size={17} />
              </button>
            </nav>
          )}
        </>
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
