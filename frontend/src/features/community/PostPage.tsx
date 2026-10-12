import { useCallback, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  ArrowLeft,
  ArrowRight,
  Flag,
  CalendarDays,
  MessageCircle,
  Loader2,
} from "lucide-react";
import { api, ApiError, calendarDateLabel } from "../../shared/api";
import type { Post } from "../../shared/types";
import { useAuth, useToast } from "../../shared/providers";
import { ErrorState, Loading, Modal } from "../../shared/ui";
import PostManagement from "./PostManagement";
import PostComments from "./PostComments";

/** 공개 상세는 삭제/숨김을 주기적으로 다시 확인합니다. 개인 대화 요청과 여행 초대는 별도 권한입니다. */
export default function PostPage() {
  const { id = "" } = useParams();
  const { user } = useAuth();
  const navigate = useNavigate();
  const toast = useToast();
  const [comment, setComment] = useState("");
  const [commentBusy, setCommentBusy] = useState(false);
  const commentRef = useRef(false);
  const [report, setReport] = useState(false);
  const [reason, setReason] = useState("");
  const [reportBusy, setReportBusy] = useState(false);
  const reportRef = useRef(false);
  const closeReport = useCallback(() => {
    if (!reportRef.current) setReport(false);
  }, []);
  const query = useQuery({
    queryKey: ["post", id],
    queryFn: () => api<Post>("/community/posts/" + id),
    refetchInterval: (q) =>
      q.state.error instanceof ApiError && q.state.error.status === 404
        ? false
        : 15000,
    retry: false,
    gcTime: 0,
  });
  async function refresh() {
    const r = await query.refetch();
    return r.isError ? undefined : r.data;
  }
  async function submitComment(e: React.FormEvent) {
    e.preventDefault();
    if (commentRef.current || !comment.trim()) return;
    commentRef.current = true;
    setCommentBusy(true);
    try {
      await api("/community/posts/" + id + "/comments", {
        method: "POST",
        body: { body: comment },
      });
      setComment("");
      await refresh();
    } catch (e) {
      toast((e as Error).message, true);
      if (e instanceof ApiError && e.status === 404) await refresh();
    } finally {
      commentRef.current = false;
      setCommentBusy(false);
    }
  }
  if (query.isPending) return <Loading />;
  if (query.error instanceof ApiError && query.error.status === 404)
    return (
      <div className="page access-ended" role="status">
        <h1>이 이야기를 볼 수 없어요</h1>
        <p>
          작성자가 삭제했거나 공개되지 않은 글이에요. 내가 삭제한 글은
          커뮤니티의 내 글 휴지통에서 찾을 수 있어요.
        </p>
        <Link className="button primary" to="/community">
          커뮤니티로 돌아가기
        </Link>
      </div>
    );
  if (!query.data)
    return <ErrorState error={query.error} retry={() => query.refetch()} />;
  const p = query.data;
  return (
    <div className="page article-page">
      {query.error && (
        <ErrorState error={query.error} retry={() => query.refetch()} />
      )}
      <Link className="back-link" to="/community">
        <ArrowLeft size={16} />
        커뮤니티
      </Link>
      <article className="panel article">
        <span className="badge">
          {p.category} · {p.region}
        </span>
        <h1>{p.title}</h1>
        <div className="article-author">
          <span className="avatar">{p.nickname.slice(0, 1)}</span>
          <div>
            <strong>{p.nickname}</strong>
            <p>
              {new Date(p.createdAt).toLocaleDateString("ko-KR")}{" "}
              {p.updatedAt && <span>· 수정됨</span>}
            </p>
          </div>
          {user && user.id !== p.authorId && (
            <button className="text-button" onClick={() => setReport(true)}>
              <Flag size={15} />
              신고
            </button>
          )}
        </div>
        {p.category === "동행" && (
          <div className="notice recruit-notice">
            <CalendarDays size={18} />
            <span>
              {calendarDateLabel(p.recruitmentDate)} · {p.capacity}명 모집 ·{" "}
              {p.status === "OPEN"
                ? "모집 중"
                : p.status === "CANCELLED"
                  ? "모집 취소"
                  : "모집 마감"}
            </span>
          </div>
        )}
        <div className="article-body">{p.body}</div>
        {p.category === "동행" &&
          p.status === "OPEN" &&
          user?.id !== p.authorId && (
            <div className="post-contact">
              <p className="field-help">
                상대방의 수신 설정과 수락에 따라 대화할 수 있어요. 대화를
                시작해도 여행 편집 권한은 생기지 않아요.
              </p>
              <Link
                className="button primary"
                to={
                  "/messages?recipient=" +
                  p.authorId +
                  "&name=" +
                  encodeURIComponent(p.nickname)
                }
              >
                <MessageCircle size={17} />
                비공개 대화 요청하기
              </Link>
            </div>
          )}
        {user?.id === p.authorId && (
          <PostManagement
            post={p}
            refresh={refresh}
            removed={() => navigate("/community", { replace: true })}
          />
        )}
      </article>
      <section className="panel post-comments">
        <h2>
          함께 나누는 이야기{" "}
          <span className="badge subtle">{p.comments?.length || 0}</span>
        </h2>
        <PostComments post={p} refresh={refresh} />
        {user ? (
          <form className="inline-form" onSubmit={submitComment}>
            <input
              aria-label="댓글 내용"
              placeholder="따뜻한 한마디를 남겨 주세요"
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              maxLength={2000}
              required
              disabled={commentBusy}
            />
            <button
              className="button primary"
              disabled={commentBusy || !comment.trim()}
            >
              {commentBusy ? <Loader2 size={17} className="spin" /> : "등록"}
            </button>
          </form>
        ) : (
          <Link className="text-button" to={"/login?next=/community/" + id}>
            로그인하고 이야기 나누기
            <ArrowRight size={16} />
          </Link>
        )}
      </section>
      {report && (
        <Modal title="게시글 신고" onClose={closeReport}>
          <p className="field-help">
            접수 당시의 제목·본문·작성자 닉네임을 운영 확인용으로 보존해요. 이후
            글이 수정·삭제되어도 신고 내용은 유지돼요.
          </p>
          <label>
            신고 사유
            <textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              rows={4}
              maxLength={1000}
              disabled={reportBusy}
            />
          </label>
          <div className="post-form-actions">
            <button
              className="button primary full"
              disabled={reportBusy || !reason.trim()}
              onClick={async () => {
                if (reportRef.current) return;
                reportRef.current = true;
                setReportBusy(true);
                try {
                  await api("/reports", {
                    method: "POST",
                    body: { type: "POST", targetId: id, reason },
                  });
                  toast("신고를 접수했어요.");
                  setReport(false);
                } catch (e) {
                  toast((e as Error).message, true);
                } finally {
                  reportRef.current = false;
                  setReportBusy(false);
                }
              }}
            >
              신고 접수하기
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}
