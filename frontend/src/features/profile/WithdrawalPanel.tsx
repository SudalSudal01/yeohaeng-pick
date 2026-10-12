import { useCallback, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router-dom";
import { ArrowRight, Loader2, UserRoundMinus } from "lucide-react";
import { api, ApiError } from "../../shared/api";
import { useToast } from "../../shared/providers";
import { ErrorState, Loading, Modal } from "../../shared/ui";

type Preview = {
  ownedTrips: { id: string; title: string; memberCount: number }[];
  joinedTripCount: number;
};

/** 탈퇴 버튼을 설정 저장과 떨어뜨려 배치합니다. 서버의 소유 여행 확인이 끝나기 전에는 실행 양식을 열지 않습니다. */
export default function WithdrawalPanel() {
  const [opened, setOpened] = useState(false);
  const busyRef = useRef(false);
  const close = useCallback(() => {
    if (!busyRef.current) setOpened(false);
  }, []);
  return (
    <section className="panel withdrawal-panel">
      <h2>
        <UserRoundMinus size={21} /> 계정 관리
      </h2>
      <p className="field-help">
        탈퇴 전에 소유한 여행과 삭제·보존되는 기록을 확인할 수 있어요.
      </p>
      <div className="withdrawal-panel-actions">
        <button
          className="button secondary danger-text"
          onClick={() => setOpened(true)}
        >
          회원 탈퇴 안내 보기
        </button>
      </div>
      {opened && <WithdrawalModal close={close} busyRef={busyRef} />}
    </section>
  );
}

/** 네트워크 오류 때 비밀번호는 비우고 확인 화면은 유지합니다. 완료 뒤에는 비공개 캐시도 모두 비웁니다. */
function WithdrawalModal({
  close,
  busyRef,
}: {
  close: () => void;
  busyRef: React.RefObject<boolean>;
}) {
  const client = useQueryClient();
  const navigate = useNavigate();
  const toast = useToast();
  const [password, setPassword] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const preview = useQuery({
    queryKey: ["withdrawal-preview"],
    queryFn: () => api<Preview>("/auth/withdrawal"),
    staleTime: 0,
    gcTime: 0,
    retry: false,
  });
  async function withdraw(e: React.FormEvent) {
    e.preventDefault();
    if (
      busyRef.current ||
      !confirmed ||
      !password ||
      !preview.data ||
      preview.data.ownedTrips.length ||
      preview.isFetching ||
      preview.error
    )
      return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      await api("/auth/account", {
        method: "DELETE",
        body: { password, confirmed },
      });
      setPassword("");
      // 진행 중인 읽기 요청을 먼저 취소해야 삭제 후 오래된 개인 자료가 캐시에 되돌아오지 않습니다.
      await client.cancelQueries();
      client.clear();
      client.setQueryData(["me"], null);
      navigate("/account/withdrawn", { replace: true });
      toast("회원 탈퇴가 완료됐어요.");
    } catch (e) {
      setPassword("");
      setConfirmed(false);
      setError(e as Error);
      if (e instanceof ApiError && e.status === 409) await preview.refetch();
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }
  return (
    <Modal
      title="회원 탈퇴 전 확인해 주세요"
      onClose={close}
      className="withdrawal-modal"
    >
      <p className="withdrawal-intro">
        탈퇴하면 기존 계정으로 다시 로그인하거나 기록을 복원할 수 없어요.
      </p>
      {preview.isPending ? (
        <Loading text="내 여행을 확인하고 있어요" />
      ) : preview.error ? (
        <ErrorState
          error={preview.error}
          retry={() => void preview.refetch()}
        />
      ) : (
        preview.data && (
          <>
            {preview.data.ownedTrips.length > 0 ? (
              <section
                className="withdrawal-blockers"
                aria-label="먼저 정리할 여행"
              >
                <h3>
                  소유한 여행 {preview.data.ownedTrips.length}개를 먼저 정리해
                  주세요
                </h3>
                <p>
                  함께하는 동행에게 소유권을 넘기거나, 여행 상세에서 직접 삭제할
                  수 있어요. 혼자 소유한 여행도 정리가 필요해요.
                </p>
                <ul>
                  {preview.data.ownedTrips.map((t) => (
                    <li key={t.id}>
                      <strong>{t.title}</strong>
                      <span>참여 계정 {t.memberCount}명</span>
                      <Link
                        className="button secondary"
                        to={`/trips/${t.id}`}
                        onClick={close}
                      >
                        여행 확인하기 <ArrowRight size={16} />
                      </Link>
                    </li>
                  ))}
                </ul>
              </section>
            ) : (
              <p className="withdrawal-ready">
                정리할 소유 여행이 없어요. 참여 중인 여행{" "}
                {preview.data.joinedTripCount}개에서는 자동으로 나가요.
              </p>
            )}
            <div className="withdrawal-policy">
              <section>
                <h3>삭제되는 정보</h3>
                <p>
                  이메일·비밀번호·닉네임·기본 취향·수신 설정을 지워요. 내
                  게시글과 댓글은 휴지통 본문까지 삭제하고, 초대·차단·인증
                  링크와 개인 찜도 정리해요.
                </p>
              </section>
              <section>
                <h3>함께 남긴 기록</h3>
                <p>
                  공동 여행의 일정·예약·경비·변경 이력과 기존 대화·신고 기록은
                  유지돼요. 작성자 이름은 ‘탈퇴한 회원’으로 바뀌고, 개인 대화는
                  더 보낼 수 없어요.
                </p>
              </section>
              <section>
                <h3>별도로 확인할 내용</h3>
                <p>
                  기록에 직접 적은 개인정보까지 자동으로 지워지지는 않아요. 외부
                  예매·결제는 취소되지 않으며, 다시 가입해도 이전 기록과
                  연결되지 않아요.
                </p>
              </section>
            </div>
            {preview.data.ownedTrips.length === 0 && (
              <form onSubmit={withdraw}>
                <label>
                  현재 비밀번호
                  <input
                    type="password"
                    autoComplete="current-password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    required
                    maxLength={72}
                    disabled={busy}
                    aria-describedby="withdrawal-password-help"
                  />
                </label>
                <p id="withdrawal-password-help" className="field-help">
                  본인 확인을 위해 지금 로그인한 계정의 비밀번호를 입력해
                  주세요.
                </p>
                <label className="ownership-confirm withdrawal-confirm">
                  <input
                    type="checkbox"
                    checked={confirmed}
                    onChange={(e) => setConfirmed(e.target.checked)}
                    required
                    disabled={busy}
                  />
                  <span>
                    삭제·보존되는 기록을 확인했고, 계정을 복원할 수 없는 것에
                    동의해요.
                  </span>
                </label>
                {error && <ErrorState error={error} />}
                <div className="membership-actions">
                  <button
                    className="button danger-button"
                    disabled={
                      busy || !confirmed || !password || preview.isFetching
                    }
                  >
                    {busy && <Loader2 size={17} className="spin" />}
                    {busy ? "탈퇴 처리 중" : "확인 후 회원 탈퇴"}
                  </button>
                  <button
                    type="button"
                    className="button secondary"
                    disabled={busy}
                    onClick={close}
                  >
                    취소
                  </button>
                </div>
              </form>
            )}
            {preview.data.ownedTrips.length > 0 && (
              <div className="membership-actions">
                <button className="button secondary" onClick={close}>
                  닫기
                </button>
              </div>
            )}
          </>
        )
      )}
    </Modal>
  );
}
