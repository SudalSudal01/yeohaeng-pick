import { useState, type FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { ArrowRight, Eye, EyeOff, MapPin, Loader2 } from "lucide-react";
import { api } from "../../shared/api";
import { useAuth, useToast } from "../../shared/providers";

/** 로그인 뒤에는 원래 가려던 내부 화면으로 돌아갑니다. 외부 URL로 이동하는 open redirect를 차단합니다. */
export default function AuthPage({ register = false }: { register?: boolean }) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [nickname, setNickname] = useState("");
  const [visible, setVisible] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const { login } = useAuth();
  const toast = useToast();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = params.get("next");
  const target =
    next?.startsWith("/") && !next.startsWith("//") ? next : "/trips";
  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      if (register)
        await api("/auth/register", {
          method: "POST",
          body: { email, password, nickname },
        });
      await login(email, password);
      toast(
        register
          ? "가입을 환영해요. 기본 취향을 설정해 보세요."
          : "다시 만나서 반가워요.",
      );
      navigate(register ? "/profile" : target);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
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
          />
        </label>
        <label>
          비밀번호
          <div className="password-field">
            <input
              type={visible ? "text" : "password"}
              autoComplete={register ? "new-password" : "current-password"}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              minLength={register ? 10 : 1}
              placeholder={register ? "10자 이상" : "비밀번호 입력"}
              required
            />
            <button
              type="button"
              className="icon-button"
              aria-label={visible ? "비밀번호 숨기기" : "비밀번호 보기"}
              onClick={() => setVisible(!visible)}
            >
              {visible ? <EyeOff size={19} /> : <Eye size={19} />}
            </button>
          </div>
        </label>
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
