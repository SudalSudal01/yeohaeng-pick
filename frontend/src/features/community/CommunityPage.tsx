import { useCallback, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  Plus,
  ArrowRight,
  MapPin,
  Users,
  MessageCircle,
  ArrowLeft,
  Flag,
  CalendarDays,
} from "lucide-react";
import { api } from "../../shared/api";
import type { Post } from "../../shared/types";
import { useAuth, useToast } from "../../shared/providers";
import { Empty, Loading, ErrorState, Modal } from "../../shared/ui";

/** 공개 글과 개인 여행의 정보는 분리합니다. 모집글을 읽거나 메시지를 보내도 공동 편집 권한은 생기지 않습니다. */
export default function CommunityPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const toast = useToast();
  const client = useQueryClient();
  const [category, setCategory] = useState("전체");
  const [write, setWrite] = useState(false);
  const close = useCallback(() => setWrite(false), []);
  const posts = useQuery({
    queryKey: ["posts"],
    queryFn: () => api<Post[]>("/community/posts"),
  });
  const filtered = posts.data?.filter(
    (p) => category === "전체" || category === p.category,
  );
  return (
    <div className="page">
      <div className="community-heading">
        <span className="eyebrow">OUR TRAVEL STORIES</span>
        <h1>
          여행의 다음 이야기는,
          <br />
          함께 나누는 순간.
        </h1>
        <p>솔직한 후기부터 새로운 동행까지. 여행하는 마음을 나눠요.</p>
        <div className="community-heading-art">
          <MessageCircle size={60} strokeWidth={1} />
          <Users size={38} />
        </div>
      </div>
      <div className="community-toolbar">
        <div className="chips">
          {["전체", "후기", "추천", "코스", "동행", "의견"].map((c) => (
            <button
              key={c}
              className={"chip " + (category === c ? "selected" : "")}
              onClick={() => setCategory(c)}
            >
              {c === "동행" ? "동행 찾기" : c}
            </button>
          ))}
        </div>
        <button
          className="button primary"
          onClick={() =>
            user ? setWrite(true) : navigate("/login?next=/community")
          }
        >
          <Plus size={17} />
          글쓰기
        </button>
      </div>
      {posts.isPending ? (
        <Loading />
      ) : posts.error ? (
        <ErrorState error={posts.error} />
      ) : !filtered?.length ? (
        <Empty
          title={
            category === "동행"
              ? "함께 떠날 사람을 찾아볼까요?"
              : "첫 번째 여행 이야기를 기다려요"
          }
          text="발견한 장소, 다녀온 코스, 함께하고 싶은 여행을 공유해 주세요."
        />
      ) : (
        <div className="post-grid">
          {filtered.map((p) => (
            <Link className="post-card" to={"/community/" + p.id} key={p.id}>
              <div className="post-card-meta">
                <span
                  className={
                    "badge " +
                    (p.category === "동행" ? "orange-badge" : "subtle")
                  }
                >
                  {p.category === "동행"
                    ? p.status === "OPEN"
                      ? "동행 모집 중"
                      : "모집 종료"
                    : p.category}
                </span>
                <span>
                  <MapPin size={13} />
                  {p.region}
                </span>
              </div>
              <h2>{p.title}</h2>
              <p className="post-excerpt">{p.body}</p>
              {p.category === "동행" && (
                <div className="recruit-meta">
                  <CalendarDays size={15} />
                  {p.recruitmentDate}
                  <Users size={15} />
                  {p.capacity}명
                </div>
              )}
              <div className="post-author">
                <span className="avatar small-avatar">
                  {p.nickname.slice(0, 1)}
                </span>
                <span>{p.nickname}</span>
                <span className="post-date">
                  {new Date(p.createdAt).toLocaleDateString("ko-KR")}
                </span>
                <ArrowRight size={17} />
              </div>
            </Link>
          ))}
        </div>
      )}
      {write && (
        <WritePost
          close={close}
          done={async (id) => {
            await client.invalidateQueries({ queryKey: ["posts"] });
            toast("여행 이야기를 등록했어요.");
            navigate("/community/" + id);
          }}
        />
      )}
    </div>
  );
}
function WritePost({
  close,
  done,
}: {
  close: () => void;
  done: (id: string) => void;
}) {
  const [form, setForm] = useState({
    category: "후기",
    region: "서울",
    title: "",
    body: "",
    recruitmentDate: "",
    capacity: 2,
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error>();
  return (
    <Modal title="여행 이야기 나누기" onClose={close}>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          try {
            const p = await api<Post>("/community/posts", {
              method: "POST",
              body: { ...form, recruitmentDate: form.recruitmentDate || null },
            });
            done(p.id);
          } catch (e) {
            setError(e as Error);
          } finally {
            setBusy(false);
          }
        }}
      >
        <div className="form-grid">
          <label>
            이야기 종류
            <select
              value={form.category}
              onChange={(e) => setForm({ ...form, category: e.target.value })}
            >
              {["후기", "추천", "코스", "동행", "의견"].map((c) => (
                <option key={c}>{c}</option>
              ))}
            </select>
          </label>
          <label>
            여행 지역
            <input
              value={form.region}
              onChange={(e) => setForm({ ...form, region: e.target.value })}
              maxLength={50}
              required
            />
          </label>
        </div>
        <label>
          제목
          <input
            placeholder="어떤 이야기를 나누고 싶나요?"
            value={form.title}
            onChange={(e) => setForm({ ...form, title: e.target.value })}
            maxLength={120}
            required
          />
        </label>
        {form.category === "동행" && (
          <div className="form-grid">
            <label>
              여행 날짜
              <input
                type="date"
                value={form.recruitmentDate}
                onChange={(e) =>
                  setForm({ ...form, recruitmentDate: e.target.value })
                }
                required
              />
            </label>
            <label>
              모집 인원
              <input
                type="number"
                min={1}
                max={20}
                value={form.capacity}
                onChange={(e) =>
                  setForm({ ...form, capacity: Number(e.target.value) })
                }
              />
            </label>
          </div>
        )}
        <label>
          내용
          <textarea
            rows={7}
            placeholder={
              form.category === "동행"
                ? "예산, 이동 방식, 여행 스타일과 고정·변경 가능한 조건을 적어 주세요. 연락처는 공개하지 않고 비공개 대화로 이야기해요."
                : "추천하는 이유와 직접 경험한 내용을 들려주세요."
            }
            value={form.body}
            onChange={(e) => setForm({ ...form, body: e.target.value })}
            maxLength={10000}
            required
          />
        </label>
        {error && <ErrorState error={error} />}
        <button className="button primary full" disabled={busy}>
          {busy ? "등록하고 있어요" : "이야기 등록하기"}
        </button>
      </form>
    </Modal>
  );
}
export function PostPage() {
  const { id = "" } = useParams();
  const { user } = useAuth();
  const toast = useToast();
  const client = useQueryClient();
  const [comment, setComment] = useState("");
  const [report, setReport] = useState(false);
  const [reason, setReason] = useState("");
  const close = useCallback(() => setReport(false), []);
  const query = useQuery({
    queryKey: ["post", id],
    queryFn: () => api<Post>("/community/posts/" + id),
  });
  if (query.isPending) return <Loading />;
  if (query.error || !query.data) return <ErrorState error={query.error} />;
  const p = query.data;
  async function submitComment(e: React.FormEvent) {
    e.preventDefault();
    try {
      await api("/community/posts/" + id + "/comments", {
        method: "POST",
        body: { body: comment },
      });
      setComment("");
      await client.invalidateQueries({ queryKey: ["post", id] });
    } catch (e) {
      toast((e as Error).message, true);
    }
  }
  return (
    <div className="page article-page">
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
            <p>{new Date(p.createdAt).toLocaleDateString("ko-KR")}</p>
          </div>
          {user && user.id !== p.authorId && (
            <button className="text-button" onClick={() => setReport(true)}>
              <Flag size={15} />
              신고
            </button>
          )}
        </div>
        {p.category === "동행" && (
          <div className="notice">
            <CalendarDays size={18} />
            {p.recruitmentDate} · {p.capacity}명 모집 ·{" "}
            {p.status === "OPEN" ? "모집 중" : "모집 종료"}
          </div>
        )}
        <div className="article-body">{p.body}</div>
        {p.category === "동행" &&
          p.status === "OPEN" &&
          user?.id !== p.authorId && (
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
          )}
        {user?.id === p.authorId && p.category === "동행" && (
          <button
            className="button secondary"
            onClick={async () => {
              try {
                await api("/community/posts/" + id + "/status", {
                  method: "PUT",
                  body: { status: p.status === "OPEN" ? "CLOSED" : "OPEN" },
                });
                await query.refetch();
                await client.invalidateQueries({ queryKey: ["posts"] });
              } catch (e) {
                toast((e as Error).message, true);
              }
            }}
          >
            {p.status === "OPEN" ? "모집 마감하기" : "다시 모집하기"}
          </button>
        )}
      </article>
      <section className="panel">
        <h2>
          함께 나누는 이야기{" "}
          <span className="badge subtle">{p.comments?.length || 0}</span>
        </h2>
        {p.comments?.map((c) => (
          <div className="comment" key={c.id}>
            <span className="avatar small-avatar">
              {c.nickname.slice(0, 1)}
            </span>
            <div>
              <strong>{c.nickname}</strong>
              <p>{c.body}</p>
            </div>
          </div>
        ))}
        {user ? (
          <form className="inline-form" onSubmit={submitComment}>
            <input
              aria-label="댓글 내용"
              placeholder="따뜻한 한마디를 남겨 주세요"
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              maxLength={2000}
              required
            />
            <button className="button primary">등록</button>
          </form>
        ) : (
          <Link className="text-button" to={"/login?next=/community/" + id}>
            로그인하고 이야기 나누기
            <ArrowRight size={16} />
          </Link>
        )}
      </section>
      {report && (
        <Modal title="게시글 신고" onClose={close}>
          <label>
            신고 사유
            <textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              rows={4}
              maxLength={1000}
            />
          </label>
          <button
            className="button primary full"
            disabled={!reason.trim()}
            onClick={async () => {
              try {
                await api("/reports", {
                  method: "POST",
                  body: { type: "POST", targetId: id, reason },
                });
                toast("신고를 접수했어요.");
                setReport(false);
              } catch (e) {
                toast((e as Error).message, true);
              }
            }}
          >
            신고 접수하기
          </button>
        </Modal>
      )}
    </div>
  );
}
