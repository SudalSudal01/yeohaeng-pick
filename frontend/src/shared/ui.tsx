import { useEffect, useRef, type ReactNode } from "react";
import { X, ArrowRight, Compass, Loader2, AlertCircle } from "lucide-react";
import { Link, useLocation, Navigate } from "react-router-dom";
import { useAuth } from "./providers";

/** 공통 모달은 네이티브 dialog의 포커스 제어·Escape·배경 비활성화를 사용합니다. */
export function Modal({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const d = ref.current;
    d?.showModal();
    const cancel = (e: Event) => {
      e.preventDefault();
      onClose();
    };
    d?.addEventListener("cancel", cancel);
    return () => {
      d?.removeEventListener("cancel", cancel);
      d?.close();
    };
  }, [onClose]);
  return (
    <dialog ref={ref} className="modal" aria-label={title}>
      <div className="modal-header">
        <h2>{title}</h2>
        <button className="icon-button" onClick={onClose} aria-label="닫기">
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
    <div className="notice danger" role="alert">
      <AlertCircle size={20} />
      <span>
        {error instanceof Error ? error.message : "정보를 불러오지 못했어요."}
      </span>
      {retry && (
        <button className="text-button" onClick={retry}>
          다시 시도
        </button>
      )}
    </div>
  );
}
export function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();
  const location = useLocation();
  if (loading) return <Loading />;
  if (!user)
    return (
      <Navigate
        replace
        to={
          "/login?next=" +
          encodeURIComponent(location.pathname + location.search)
        }
      />
    );
  return children;
}
