import { Component, Suspense, lazy, type ReactNode } from "react";
import { useLocation } from "react-router-dom";
import { ErrorState, Loading } from "../../shared/ui";

// 홈/일정 화면의 첫 다운로드에 게시글 양식·휴지통 코드를 넣지 않습니다. 해당 경로에 들어갈 때 로드합니다.
const CommunityPage = lazy(() => import("./CommunityPage"));
const PostPage = lazy(() => import("./PostPage"));

/** 분리된 화면 파일의 네트워크 실패도 공통 내비게이션을 유지한 채 다시 열 수 있게 안내합니다. */
class CommunityLoadBoundary extends Component<
  { children: ReactNode },
  { failed: boolean }
> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  render() {
    return this.state.failed ? (
      <ErrorState
        error={
          new Error(
            "화면을 불러오지 못했어요. 연결을 확인하고 다시 열어 주세요.",
          )
        }
        retry={() => window.location.reload()}
      />
    ) : (
      this.props.children
    );
  }
}
export default function CommunityRoute({
  detail = false,
}: {
  detail?: boolean;
}) {
  const location = useLocation();
  return (
    <CommunityLoadBoundary key={location.pathname}>
      <Suspense fallback={<Loading text="여행 이야기를 불러오고 있어요" />}>
        {detail ? <PostPage /> : <CommunityPage />}
      </Suspense>
    </CommunityLoadBoundary>
  );
}
