import { useState, type FormEvent } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { Mail, CheckCircle2, Loader2 } from "lucide-react";
import { api } from "../../shared/api";
import { useConfig } from "../../shared/providers";

/** 인증 링크는 버튼을 눌러야 소비합니다. 메일 보안 스캐너가 링크를 미리 읽어도 인증이 완료되지 않습니다. */
export default function AccountPage({
  mode,
}: {
  mode: "forgot" | "verify" | "reset";
}) {
  const [params] = useSearchParams();
  const config = useConfig();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      const result = await api<{ message: string }>(
        "/auth/" + (mode === "forgot" ? "reset/send" : mode),
        {
          method: "POST",
          body:
            mode === "forgot"
              ? { email }
              : { token: params.get("token"), password },
        },
      );
      setMessage(result.message);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="page narrow-page">
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
        {message ? (
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
                  : "10자 이상으로 설정해 주세요. 변경 후에는 모든 기기에서 다시 로그인해야 해요."}
            </p>
            {mode === "forgot" ? (
              <label>
                이메일
                <input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  required
                  autoComplete="email"
                />
              </label>
            ) : mode === "reset" ? (
              <label>
                새 비밀번호
                <input
                  type="password"
                  value={password}
                  minLength={10}
                  onChange={(e) => setPassword(e.target.value)}
                  required
                  autoComplete="new-password"
                />
              </label>
            ) : null}
            {error && (
              <p className="field-error" role="alert">
                {error}
              </p>
            )}
            {!config.data?.emailConfigured && (
              <div className="notice">
                메일 서비스 연결 후 사용할 수 있어요.
              </div>
            )}
            <button
              className="button primary full"
              disabled={busy || !config.data?.emailConfigured}
            >
              {busy && <Loader2 className="spin" size={17} />}
              {mode === "forgot"
                ? "재설정 메일 받기"
                : mode === "verify"
                  ? "이메일 인증하기"
                  : "비밀번호 변경하기"}
            </button>
          </>
        )}
      </form>
    </div>
  );
}
