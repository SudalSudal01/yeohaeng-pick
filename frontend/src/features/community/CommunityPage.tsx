import { useCallback, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router-dom";
import {
  Plus,
  ArrowRight,
  MapPin,
  Users,
  MessageCircle,
  CalendarDays,
  Trash2,
} from "lucide-react";
import { api, calendarDateLabel } from "../../shared/api";
import type { Post } from "../../shared/types";
import { useAuth, useToast } from "../../shared/providers";
import { Empty, Loading, ErrorState } from "../../shared/ui";
import PostEditor from "./PostEditor";
import PostTrashModal from "./PostTrashModal";

/** 공개 글과 개인 여행의 정보는 분리합니다. 모집글을 읽거나 메시지를 보내도 공동 편집 권한은 생기지 않습니다. */
export default function CommunityPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const toast = useToast();
  const client = useQueryClient();
  const [category, setCategory] = useState("전체");
  const [write, setWrite] = useState(false);
  const [trash, setTrash] = useState(false);
  const closeTrash = useCallback(() => setTrash(false), []);
  const close = useCallback(() => setWrite(false), []);
  const posts = useQuery({
    queryKey: ["posts"],
    queryFn: () => api<Post[]>("/community/posts"),
    refetchInterval: 15000,
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
        <div className="community-write-actions">
          {user && (
            <button className="button secondary" onClick={() => setTrash(true)}>
              <Trash2 size={17} />내 글 휴지통
            </button>
          )}
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
                  {calendarDateLabel(p.recruitmentDate)}
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
        <PostEditor
          close={close}
          done={async (post) => {
            await client.invalidateQueries({ queryKey: ["posts"] });
            toast("여행 이야기를 등록했어요.");
            navigate("/community/" + post.id);
          }}
        />
      )}
      {trash && (
        <PostTrashModal
          close={closeTrash}
          restored={(id) => {
            setTrash(false);
            navigate("/community/" + id);
          }}
        />
      )}
    </div>
  );
}
