import { useCallback, useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useSearchParams } from "react-router-dom";
import { Plus, Users, MessageCircle, Trash2 } from "lucide-react";
import { api } from "../../shared/api";
import type { CommunityPostPage } from "../../shared/types";
import { useAuth, useToast } from "../../shared/providers";
import { Empty, Loading, ErrorState } from "../../shared/ui";
import PostEditor from "./PostEditor";
import PostTrashModal from "./PostTrashModal";
import CommunitySearchForm from "./CommunitySearchForm";
import CommunityResults from "./CommunityResults";
import {
  communityCategories,
  communitySearchParams,
  readCommunityFilters,
  type CommunityFilters,
} from "./communitySearch";
import "./communitySearch.css";

/** 공개 글과 개인 여행은 분리합니다. 검색 조건과 페이지는 URL에 저장하고 서버의 전체 공개 목록을 조회합니다. */
export default function CommunityPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const toast = useToast();
  const client = useQueryClient();
  const [params, setParams] = useSearchParams();
  const filters = readCommunityFilters(params);
  const search = communitySearchParams(filters).toString();
  const returnTo = "/community" + (search ? "?" + search : "");
  const formKey = communitySearchParams({ ...filters, page: 1 }).toString();
  const [write, setWrite] = useState(false);
  const [trash, setTrash] = useState(false);
  const resultsRef = useRef<HTMLElement>(null);
  const pageFocus = useRef(false);
  const closeTrash = useCallback(() => setTrash(false), []);
  const close = useCallback(() => setWrite(false), []);
  const posts = useQuery({
    queryKey: ["posts", search],
    queryFn: () =>
      api<CommunityPostPage>("/community/posts" + (search ? "?" + search : "")),
    refetchInterval: 15000,
    retry: false,
  });
  // 삭제 등으로 마지막 페이지가 줄었으면 서버가 보정한 주소로 교체합니다. 잘못된 페이지를 뒤로 가기에 쌓지 않습니다.
  useEffect(() => {
    if (
      posts.data &&
      !posts.isFetching &&
      !posts.error &&
      posts.data.page !== filters.page
    ) {
      setParams(communitySearchParams({ ...filters, page: posts.data.page }), {
        replace: true,
      });
    }
  }, [posts.data, posts.isFetching, posts.error, search, setParams]);
  // 페이지 버튼으로 이동한 경우만 결과 시작에 포커스를 줍니다. 주기적 갱신은 읽는 위치를 건드리지 않습니다.
  useEffect(() => {
    if (pageFocus.current && !posts.isFetching) {
      pageFocus.current = false;
      resultsRef.current?.focus({ preventScroll: true });
      resultsRef.current?.scrollIntoView({ block: "start" });
    }
  }, [posts.isFetching]);
  function apply(next: CommunityFilters) {
    setParams(communitySearchParams(next));
    // 같은 조건의 검색 버튼은 현재 결과를 갱신합니다. 다른 조건은 queryKey 변경으로 조회합니다.
    if (communitySearchParams(next).toString() === search) void posts.refetch();
  }
  const hasFilters =
    !!filters.q ||
    !!filters.region ||
    filters.category !== "전체" ||
    !!filters.status;
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
        <div className="community-heading-art" aria-hidden="true">
          <MessageCircle size={60} strokeWidth={1} />
          <Users size={38} />
        </div>
      </div>
      <div className="community-toolbar">
        <div className="chips" role="group" aria-label="이야기 종류">
          {communityCategories.map((category) => (
            <button
              key={category}
              className={
                "chip " + (filters.category === category ? "selected" : "")
              }
              aria-pressed={filters.category === category}
              onClick={() =>
                apply({
                  ...filters,
                  category,
                  status: category === "동행" ? filters.status : "",
                  page: 1,
                })
              }
            >
              {category === "동행" ? "동행 찾기" : category}
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
              user
                ? setWrite(true)
                : navigate("/login?next=" + encodeURIComponent(returnTo))
            }
          >
            <Plus size={17} />
            글쓰기
          </button>
        </div>
      </div>
      <CommunitySearchForm
        key={formKey}
        filters={filters}
        apply={apply}
        reset={() =>
          apply({ q: "", region: "", category: "전체", status: "", page: 1 })
        }
      />
      <section
        className="community-results"
        ref={resultsRef}
        tabIndex={-1}
        aria-label="여행 이야기 검색 결과"
        aria-busy={posts.isFetching}
      >
        {posts.isPending ? (
          <Loading text="여행 이야기를 찾고 있어요" />
        ) : posts.error ? (
          <ErrorState error={posts.error} retry={() => posts.refetch()} />
        ) : !posts.data?.items.length ? (
          <>
            <Empty
              title={
                hasFilters
                  ? "조건에 맞는 이야기가 아직 없어요"
                  : "첫 번째 여행 이야기를 기다려요"
              }
              text={
                hasFilters
                  ? "검색어를 짧게 바꾸거나 지역·모집 상태 조건을 줄여 보세요."
                  : "발견한 장소, 다녀온 코스, 함께하고 싶은 여행을 공유해 주세요."
              }
            />
            {hasFilters && (
              <div className="community-empty-actions">
                <button
                  className="button secondary"
                  onClick={() =>
                    apply({
                      q: "",
                      region: "",
                      category: "전체",
                      status: "",
                      page: 1,
                    })
                  }
                >
                  모든 이야기 보기
                </button>
              </div>
            )}
          </>
        ) : (
          <CommunityResults
            data={posts.data}
            returnTo={returnTo}
            changePage={(page) => {
              pageFocus.current = true;
              apply({ ...filters, page });
            }}
          />
        )}
      </section>
      {write && (
        <PostEditor
          close={close}
          done={async (post) => {
            await client.invalidateQueries({ queryKey: ["posts"] });
            toast("여행 이야기를 등록했어요.");
            navigate(
              "/community/" + post.id + "?" + new URLSearchParams({ returnTo }),
            );
          }}
        />
      )}
      {trash && (
        <PostTrashModal
          close={closeTrash}
          restored={(id) => {
            setTrash(false);
            navigate(
              "/community/" + id + "?" + new URLSearchParams({ returnTo }),
            );
          }}
        />
      )}
    </div>
  );
}
