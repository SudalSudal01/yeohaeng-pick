import { useEffect, useRef, useState, type FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { ArrowRight, Eye, EyeOff, MapPin, Loader2 } from "lucide-react";
import { api } from "../../shared/api";
import { useAuth, useToast } from "../../shared/providers";
import { returnPath } from "./returnPath";
import { passwordError } from "./authPolicy";
import "./auth.css";

/** 로그인 뒤에는 원래 가려던 내부 화면으로 돌아갑니다. 외부 URL로 이동하는 open redirect를 차단합니다. */
export default function AuthPage({ register = false }: { register?: boolean }) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [nickname, setNickname] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [visible, setVisible] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const { login } = useAuth();
  const toast = useToast();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const target = returnPath(params.get("next"), window.location.origin);
  const mounted = useRef(false);
  const sending = useRef(false);
  const passwordInput = useRef<HTMLInputElement>(null);
  const confirmationInput = useRef<HTMLInputElement>(null);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (sending.current) return;
    const invalid = register ? passwordError(password) : "";
    if (invalid || (register && confirmation !== password)) {
      setError(invalid || "비밀번호 확인이 일치하지 않아요.");
      (invalid ? passwordInput : confirmationInput).current?.focus();
      return;
    }
    sending.current = true;
    const sourcePath = window.location.pathname + window.location.search;
    // Suspense 전환 중에는 이전 화면이 잠시 마운트된 채 남을 수 있어 실제 주소도 함께 검사합니다.
    const stillHere = () =>
      mounted.current &&
      window.location.pathname + window.location.search === sourcePath;
    setBusy(true);
    setError("");
    try {
      if (register)
        await api("/auth/register", {
          method: "POST",
          body: { email, password, nickname },
        });
      if (!stillHere()) return;
      await login(email, password);
      if (!stillHere()) return;
      toast(
        register
          ? "가입을 환영해요. 기본 취향을 설정해 보세요."
          : "다시 만나서 반가워요.",
      );
      navigate(register ? "/profile" : target);
    } catch (e) {
      if (stillHere()) setError((e as Error).message);
    } finally {
      sending.current = false;
      if (stillHere()) setBusy(false);
    }
  }
  return (
    <section className="auth-layout">
      <div className="auth-story">
        <MapPin size={35} />
        <span className="eyebrow light">YOUR JOURNEY BEGINS HERE</span>
        <h1>
          함께 고르고,
          <br />
          기분 좋게 떠나요.
        </h1>
        <p>
          여행을 계획하는 시간도
          <br />
          여행만큼 즐거울 수 있도록.
        </p>
        <div className="auth-story-foot">여행픽 · EVERY TRIP, YOUR PICK</div>
      </div>
      <form className="auth-form" onSubmit={submit}>
        <span className="eyebrow">WELCOME TO 여행픽</span>
        <h1>
          {register ? "우리의 첫 여행을 시작해요" : "다시 만나서 반가워요"}
        </h1>
        <p>
          {register
            ? "취향을 담을 계정을 만들어 주세요."
            : "로그인하고 준비하던 여행을 이어가세요."}
        </p>
        {register && (
          <label>
            닉네임
            <input
              value={nickname}
              onChange={(e) => setNickname(e.target.value)}
              minLength={2}
              maxLength={30}
              autoComplete="nickname"
              placeholder="여행에서 불리고 싶은 이름"
              required
              disabled={busy}
            />
          </label>
        )}
        <label>
          이메일
          <input
            type="email"
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="hello@example.com"
            required
            disabled={busy}
          />
        </label>
        <label>
          비밀번호
          <div className="password-field">
            <input
              ref={passwordInput}
              type={visible ? "text" : "password"}
              autoComplete={register ? "new-password" : "current-password"}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              minLength={register ? 10 : 1}
              placeholder={register ? "10자 이상" : "비밀번호 입력"}
              required
              disabled={busy}
              maxLength={register ? 72 : undefined}
              aria-describedby={register ? "auth-password-help" : undefined}
            />
            <button
              type="button"
              className="icon-button"
              aria-label={visible ? "비밀번호 숨기기" : "비밀번호 보기"}
              onClick={() => setVisible(!visible)}
              disabled={busy}
            >
              {visible ? <EyeOff size={19} /> : <Eye size={19} />}
            </button>
          </div>
        </label>
        {register && (
          <>
            <p id="auth-password-help" className="auth-password-help">
              10자 이상, UTF-8 72바이트 이하로 설정해 주세요.
            </p>
            <label>
              비밀번호 확인
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
        <button className="button primary full" disabled={busy}>
          {busy ? <Loader2 className="spin" size={18} /> : null}
          {register ? "회원가입" : "로그인"}
          <ArrowRight size={18} />
        </button>
        <p className="auth-switch">
          {register ? "이미 계정이 있나요?" : "여행픽이 처음인가요?"}{" "}
          <Link
            to={
              (register ? "/login" : "/register") +
              "?next=" +
              encodeURIComponent(target)
            }
          >
            {register ? "로그인" : "회원가입"}
          </Link>
        </p>
        <Link className="text-button" to="/account/forgot">
          비밀번호를 잊으셨나요?
        </Link>
      </form>
    </section>
  );
}
