import { Fragment, useEffect, useRef, type ReactNode } from "react";
import { X, ArrowRight, Compass, Loader2, AlertCircle } from "lucide-react";
import { Link, useLocation, Navigate } from "react-router-dom";
import { useAuth } from "./providers";
import "./ui.css";

/** 공통 모달은 네이티브 dialog의 포커스 제어·Escape·배경 비활성화를 사용합니다. */
export function Modal({
  title,
  onClose,
  children,
  className = "",
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
  className?: string;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    const d = ref.current;
    d?.showModal();
    const cancel = (e: Event) => {
      e.preventDefault();
      close.current();
    };
    d?.addEventListener("cancel", cancel);
    return () => {
      d?.removeEventListener("cancel", cancel);
      d?.close();
    };
    // 입력·전송 상태 때문에 onClose가 바뀌어도 dialog를 닫았다 다시 열지 않습니다.
    // 처음 연 포커스와 사용자가 읽던 스크롤을 유지하며 Escape는 항상 최신 닫기 규칙을 따릅니다.
  }, []);
  return (
    <dialog ref={ref} className={"modal " + className} aria-label={title}>
      <div className="modal-header">
        <h2>{title}</h2>
        <button
          type="button"
          className="icon-button"
          onClick={onClose}
          aria-label="닫기"
        >
          <X />
        </button>
      </div>
      {children}
    </dialog>
  );
}
export function Empty({
  title,
  text,
  action,
  to,
}: {
  title: string;
  text: string;
  action?: string;
  to?: string;
}) {
  return (
    <div className="empty">
      <span className="empty-icon">
        <Compass size={35} />
      </span>
      <h2>{title}</h2>
      <p>{text}</p>
      {action && to && (
        <Link className="button primary" to={to}>
          {action}
          <ArrowRight size={18} />
        </Link>
      )}
    </div>
  );
}
export function Loading({ text = "잠시만 기다려 주세요" }: { text?: string }) {
  return (
    <div className="loading" role="status">
      <Loader2 className="spin" />
      {text}
    </div>
  );
}
export function ErrorState({
  error,
  retry,
}: {
  error: unknown;
  retry?: () => void;
}) {
  return (
    <div className="notice danger error-state" role="alert">
      <AlertCircle size={20} />
      <span>
        {error instanceof Error ? error.message : "정보를 불러오지 못했어요."}
      </span>
      {retry && (
        <button type="button" className="text-button" onClick={retry}>
          다시 시도
        </button>
      )}
    </div>
  );
}
export function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();
  const location = useLocation();
  const target = location.pathname + location.search + location.hash;
  // 확인된 회원이 없는 전환만 본문을 숨깁니다. 본인 탈퇴처럼 주체를 고정한 작업의 입력/오류 문맥은 유지합니다.
  // 로그인·로그아웃·다른 탭의 전환은 먼저 me를 null로 정리하므로 이전 회원의 화면을 남기지 않습니다.
  if (loading && !user) return <Loading />;
  // 비공개 화면이 Suspense 전환으로 잠깐 남아 있어도 이미 이동한 완료/공개 경로를 로그인으로 덮지 않습니다.
  if (
    !user &&
    window.location.pathname + window.location.search + window.location.hash !==
      target
  )
    return <Loading />;
  if (!user)
    return (
      <Navigate replace to={"/login?next=" + encodeURIComponent(target)} />
    );
  // 쿠키 재확인으로 A에서 B로 바뀌어도 같은 페이지의 A 초안·선택 상태를 B 화면에 재사용하지 않습니다.
  return <Fragment key={user.id}>{children}</Fragment>;
}
