import { useEffect, useRef, useState, type FormEvent } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { Mail, CheckCircle2, Loader2 } from "lucide-react";
import { api } from "../../shared/api";
import { useConfig } from "../../shared/providers";
import { ErrorState, Loading } from "../../shared/ui";
import {
  runSessionTransition,
  startSessionChange,
  clearSessionCache,
} from "../../shared/sessionTransition";
import {
  needsMailConnection,
  passwordError,
  validAccountToken,
} from "./authPolicy";
import "./auth.css";

/** 인증 링크는 버튼을 눌러야 소비합니다. 메일 보안 스캐너가 링크를 미리 읽어도 인증이 완료되지 않습니다. */
export default function AccountPage({
  mode,
}: {
  mode: "forgot" | "verify" | "reset";
}) {
  const [params] = useSearchParams();
  const config = useConfig();
  const client = useQueryClient();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const mounted = useRef(false);
  const sending = useRef(false);
  const passwordInput = useRef<HTMLInputElement>(null);
  const confirmationInput = useRef<HTMLInputElement>(null);
  const token = params.get("token");
  const validLink = mode === "forgot" || validAccountToken(token);
  const needsMail = needsMailConnection(mode);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (sending.current || !validLink) return;
    const invalid = mode === "reset" ? passwordError(password) : "";
    if (invalid || (mode === "reset" && password !== confirmation)) {
      setError(invalid || "비밀번호 확인이 일치하지 않아요.");
      (invalid ? passwordInput : confirmationInput).current?.focus();
      return;
    }
    sending.current = true;
    const sourcePath = window.location.pathname + window.location.search;
    const stillHere = () =>
      mounted.current &&
      window.location.pathname + window.location.search === sourcePath;
    setBusy(true);
    setError("");
    try {
      const send = () =>
        api<{ message: string }>(
          "/auth/" + (mode === "forgot" ? "reset/send" : mode),
          {
            method: "POST",
            body: mode === "forgot" ? { email } : { token, password },
          },
        );
      // 재설정도 현재 쿠키의 세션을 끝내므로 로그인/로그아웃/탈퇴와 같은 전환 경계를 사용합니다.
      const result =
        mode === "reset"
          ? await runSessionTransition(client, async () => {
              startSessionChange(client);
              try {
                return await send();
              } catch (error) {
                clearSessionCache(client);
                throw error;
              }
            })
          : await send();
      if (stillHere()) {
        setMessage(result.message);
        setPassword("");
        setConfirmation("");
      }
    } catch (e) {
      if (stillHere()) setError((e as Error).message);
    } finally {
      sending.current = false;
      if (stillHere()) setBusy(false);
    }
  }
  return (
    <div className="page narrow-page account-flow">
      <form className="panel" onSubmit={submit}>
        <span className="green-icon">
          <Mail size={26} />
        </span>
        <h1>
          {mode === "forgot"
            ? "비밀번호를 잊으셨나요?"
            : mode === "verify"
              ? "이메일 인증"
              : "새 비밀번호 설정"}
        </h1>
        {!validLink ? (
          <div className="account-invalid">
            <p role="alert">
              올바른 인증 링크가 아니에요. 메일의 링크를 다시 확인하거나 새
              링크를 받아 주세요.
            </p>
            <Link
              className="button primary"
              to={mode === "reset" ? "/account/forgot" : "/profile"}
            >
              {mode === "reset" ? "새 재설정 링크 받기" : "마이페이지로 이동"}
            </Link>
          </div>
        ) : message ? (
          <>
            <CheckCircle2 className="green" />
            <p role="status">{message}</p>
            <Link className="button primary" to="/login">
              로그인으로 돌아가기
            </Link>
          </>
        ) : (
          <>
            <p className="muted">
              {mode === "forgot"
                ? "가입한 이메일로 30분간 사용할 수 있는 재설정 링크를 보내요."
                : mode === "verify"
                  ? "아래 버튼을 눌러 이메일 인증을 완료해 주세요."
                  : "변경 후에는 모든 기기에서 다시 로그인해야 해요."}
            </p>
            {mode === "forgot" ? (
              <label>
                이메일
                <input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  required
                  disabled={busy}
                  maxLength={254}
                  autoComplete="email"
                />
              </label>
            ) : mode === "reset" ? (
              <label className="account-field">
                새 비밀번호
                <input
                  ref={passwordInput}
                  type="password"
                  value={password}
                  minLength={10}
                  onChange={(e) => setPassword(e.target.value)}
                  required
                  autoComplete="new-password"
                  disabled={busy}
                  maxLength={72}
                  aria-describedby="reset-password-help"
                />
              </label>
            ) : null}
            {mode === "reset" && (
              <>
                <p id="reset-password-help" className="account-help">
                  10자 이상, UTF-8 72바이트 이하로 설정해 주세요.
                </p>
                <label className="account-field">
                  새 비밀번호 확인
                  <input
                    ref={confirmationInput}
                    type="password"
                    autoComplete="new-password"
                    value={confirmation}
                    onChange={(e) => setConfirmation(e.target.value)}
                    maxLength={72}
                    required
                    disabled={busy}
                  />
                </label>
              </>
            )}
            {error && (
              <p className="field-error" role="alert">
                {error}
              </p>
            )}
            {needsMail && config.isPending && (
              <Loading text="메일 연결 상태를 확인하고 있어요" />
            )}
            {needsMail && config.error && (
              <ErrorState
                error={config.error}
                retry={() => void config.refetch()}
              />
            )}
            {needsMail && config.data?.emailConfigured === false && (
              <div className="notice">
                메일 서비스 연결 후 사용할 수 있어요.
              </div>
            )}
            <button
              className="button primary full account-action"
              disabled={busy || (needsMail && !config.data?.emailConfigured)}
            >
              {busy && <Loader2 className="spin" size={17} />}
              {mode === "forgot"
                ? "재설정 메일 받기"
                : mode === "verify"
                  ? "이메일 인증하기"
                  : "비밀번호 변경하기"}
            </button>
            {mode === "reset" && (
              <Link className="text-button account-action" to="/account/forgot">
                새 재설정 링크 받기
              </Link>
            )}
          </>
        )}
      </form>
    </div>
  );
}
