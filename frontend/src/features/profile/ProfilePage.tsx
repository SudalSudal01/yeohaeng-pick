import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { Check, Settings2, ShieldBan, ArrowRight } from "lucide-react";
import { useAuth, useToast, useConfig } from "../../shared/providers";
import { api } from "../../shared/api";
import type { Member } from "../../shared/types";
import WithdrawalPanel from "./WithdrawalPanel";
import { ErrorState, Loading } from "../../shared/ui";

/** 기본 취향과 개인 수신 설정을 관리합니다. 이 값을 바꿔도 이미 생성한 여행 조건을 자동 변경하지 않습니다. */
export default function ProfilePage() {
  const { user } = useAuth();
  const config = useConfig();
  const client = useQueryClient();
  const toast = useToast();
  const [name, setName] = useState("");
  const [preferences, setPreferences] = useState<string[]>([]);
  const [scope, setScope] = useState("ALL");
  const [busy, setBusy] = useState(false);
  const [sendingVerification, setSendingVerification] = useState(false);
  const [unblocking, setUnblocking] = useState<string[]>([]);
  const saving = useRef(false);
  const verifying = useRef(false);
  const unblockingIds = useRef(new Set<string>());
  const mounted = useRef(false);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  const blocks = useQuery({
    queryKey: ["blocks"],
    queryFn: () =>
      api<{ blockedId: string; nickname: string }[]>("/messages/blocks"),
  });
  useEffect(() => {
    if (user) {
      setName(user.nickname);
      setPreferences(user.preferences);
      setScope(user.dmScope);
    }
  }, [user]);
  if (!user) return null;
  return (
    <div className="page profile-page">
      <div className="page-heading">
        <span className="eyebrow">A LITTLE ABOUT YOU</span>
        <h1>나의 취향, 나의 여행</h1>
        <p>나를 소개하고 편안한 여행을 위한 설정을 골라요.</p>
      </div>
      <div className="profile-layout">
        <aside className="profile-summary">
          <span className="avatar large-avatar">
            {user.nickname.slice(0, 1)}
          </span>
          <h2>{user.nickname}</h2>
          <p>{user.email}</p>
          <span className="badge subtle">
            {user.verified ? "이메일 인증 완료" : "이메일 미인증"}
          </span>
          {!user.verified && (
            <button
              className="text-button"
              disabled={sendingVerification || !config.data?.emailConfigured}
              onClick={async () => {
                if (verifying.current) return;
                verifying.current = true;
                setSendingVerification(true);
                try {
                  await api("/auth/verify/send", {
                    method: "POST",
                    body: { email: user.email },
                  });
                  if (mounted.current) toast("인증 메일을 보냈어요.");
                } catch (e) {
                  if (mounted.current) toast((e as Error).message, true);
                } finally {
                  verifying.current = false;
                  if (mounted.current) setSendingVerification(false);
                }
              }}
            >
              {sendingVerification
                ? "인증 메일을 보내고 있어요…"
                : config.data?.emailConfigured
                  ? "인증 메일 받기"
                  : "메일 연결 후 인증 가능"}
            </button>
          )}
          <Link className="text-button" to="/trips">
            내 여행 보러 가기
            <ArrowRight size={17} />
          </Link>
          <Link className="text-button" to="/favorites">
            찜한 장소 보러 가기 <ArrowRight size={16} />
          </Link>
          {user.role === "ADMIN" && (
            <Link className="text-button" to="/admin">
              관리자 화면
              <ArrowRight size={17} />
            </Link>
          )}
        </aside>
        <form
          className="panel"
          onSubmit={async (e) => {
            e.preventDefault();
            if (saving.current) return;
            saving.current = true;
            setBusy(true);
            try {
              const m = await api<Member>("/auth/profile", {
                method: "PUT",
                body: { nickname: name, preferences, dmScope: scope },
              });
              // 저장을 기다리는 동안 로그아웃/계정 전환한 경우 이전 회원을 me 캐시에 되살리지 않습니다.
              if (
                !mounted.current ||
                client.getQueryData<Member | null>(["me"])?.id !== user.id
              )
                return;
              client.setQueryData(["me"], m);
              toast("기본 취향과 설정을 저장했어요.");
            } catch (e) {
              if (mounted.current) toast((e as Error).message, true);
            } finally {
              saving.current = false;
              if (mounted.current) setBusy(false);
            }
          }}
        >
          <h2>
            <Settings2 size={21} /> 나의 기본 설정
          </h2>
          <label>
            닉네임
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              minLength={2}
              maxLength={30}
              required
              disabled={busy}
            />
          </label>
          <label>좋아하는 여행</label>
          <p className="muted small">
            새 여행을 만들 때 기본으로 선택돼요. 여행마다 따로 바꿀 수 있어요.
          </p>
          <div className="preference-choices">
            {["자연", "관광", "맛집", "카페", "전시", "체험"].map((p) => (
              <button
                type="button"
                className={
                  "preference-choice " +
                  (preferences.includes(p) ? "selected" : "")
                }
                key={p}
                disabled={busy}
                onClick={() =>
                  setPreferences(
                    preferences.includes(p)
                      ? preferences.filter((v) => v !== p)
                      : [...preferences, p],
                  )
                }
              >
                {p}
                {preferences.includes(p) && <Check size={16} />}
              </button>
            ))}
          </div>
          <label>
            새로운 대화 요청
            <select
              disabled={busy}
              value={scope}
              onChange={(e) => setScope(e.target.value)}
            >
              <option value="ALL">모든 회원에게 요청 받기</option>
              <option value="COMPANIONS">
                같은 여행의 동행에게만 요청 받기
              </option>
              <option value="OFF">새로운 대화 요청 받지 않기</option>
            </select>
          </label>
          <p className="small muted">
            기본적으로 첫 연락은 요청함에 도착해요. 이미 수락한 대화는 유지되며
            특정 사용자를 별도로 차단할 수 있어요.
          </p>
          <div className="profile-save-actions">
            <button className="button primary" disabled={busy}>
              {busy ? "저장 중" : "설정 저장하기"}
            </button>
          </div>
        </form>
        <section className="panel blocked-panel">
          <h2>
            <ShieldBan size={21} /> 차단한 사용자
          </h2>
          {blocks.isPending ? (
            <Loading text="차단 목록을 불러오고 있어요" />
          ) : blocks.error ? (
            <ErrorState
              error={blocks.error}
              retry={() => void blocks.refetch()}
            />
          ) : !blocks.data?.length ? (
            <p className="muted">차단한 사용자가 없어요.</p>
          ) : (
            blocks.data.map((b) => (
              <div className="booking-row" key={b.blockedId}>
                <strong>{b.nickname}</strong>
                <button
                  className="text-button"
                  disabled={unblocking.includes(b.blockedId)}
                  onClick={async () => {
                    if (unblockingIds.current.has(b.blockedId)) return;
                    unblockingIds.current.add(b.blockedId);
                    setUnblocking([...unblockingIds.current]);
                    try {
                      await api("/messages/blocks/" + b.blockedId, {
                        method: "PUT",
                        body: { enabled: false },
                      });
                      await blocks.refetch();
                      if (mounted.current) toast("차단을 해제했어요.");
                    } catch (e) {
                      if (mounted.current) toast((e as Error).message, true);
                    } finally {
                      unblockingIds.current.delete(b.blockedId);
                      if (mounted.current)
                        setUnblocking([...unblockingIds.current]);
                    }
                  }}
                >
                  {unblocking.includes(b.blockedId) ? "해제 중…" : "차단 해제"}
                </button>
              </div>
            ))
          )}
        </section>
        <WithdrawalPanel />
      </div>
    </div>
  );
}
