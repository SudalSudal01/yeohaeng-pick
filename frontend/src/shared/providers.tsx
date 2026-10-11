import { createContext, useContext, useState, type ReactNode } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError } from "./api";
import type { Member, Config } from "./types";
import { X, CheckCircle2, AlertCircle } from "lucide-react";

/** 로그인 상태는 서버에서 확인합니다. 새로고침·로그아웃·다른 계정 로그인 시 이전 회원 데이터 캐시를 비웁니다. */
export function useAuth() {
  const client = useQueryClient();
  const query = useQuery({
    queryKey: ["me"],
    queryFn: () =>
      api<Member>("/auth/me").catch((e) => {
        if (e instanceof ApiError && e.status === 401) return null;
        throw e;
      }),
    retry: false,
  });
  const login = async (email: string, password: string) => {
    await api("/auth/login", { method: "POST", body: { email, password } });
    client.clear();
    client.setQueryData(["me"], await api<Member>("/auth/me"));
  };
  const logout = async () => {
    await api("/auth/logout", { method: "POST" });
    client.clear();
    client.setQueryData(["me"], null);
  };
  return { user: query.data, loading: query.isPending, login, logout };
}
export function useConfig() {
  return useQuery({
    queryKey: ["config"],
    queryFn: () => api<Config>("/config"),
    staleTime: 60000,
  });
}
type Toast = { id: number; text: string; error: boolean };
const ToastContext = createContext<(text: string, error?: boolean) => void>(
  () => {},
);
export const useToast = () => useContext(ToastContext);
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const dismiss = (id: number) =>
    setToasts((t) => t.filter((x) => x.id !== id));
  const notify = (text: string, error = false) => {
    const id = Date.now() + Math.random();
    setToasts((t) => [...t.slice(-2), { id, text, error }]);
    setTimeout(() => dismiss(id), 6000);
  };
  return (
    <ToastContext value={notify}>
      {children}
      <div className="toasts" aria-live="polite">
        {toasts.map((t) => (
          <div className={`toast ${t.error ? "error" : ""}`} key={t.id}>
            {t.error ? <AlertCircle size={19} /> : <CheckCircle2 size={19} />}
            <span>{t.text}</span>
            <button
              className="icon-button"
              aria-label="알림 닫기"
              onClick={() => dismiss(t.id)}
            >
              <X size={17} />
            </button>
          </div>
        ))}
      </div>
    </ToastContext>
  );
}
