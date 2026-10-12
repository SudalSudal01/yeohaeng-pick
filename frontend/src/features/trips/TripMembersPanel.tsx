import { useCallback, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import {
  Loader2,
  LogOut,
  Mail,
  MessageCircle,
  UserMinus,
  Users,
} from "lucide-react";
import { api, ApiError, accessEnded } from "../../shared/api";
import { Modal, ErrorState } from "../../shared/ui";
import { useToast } from "../../shared/providers";
import type { Trip } from "../../shared/types";

type Choice = {
  id: string;
  nickname: string;
  leaving: boolean;
  version: number;
};

/** 편집 계정의 참여와 실제 여행 인원을 구분합니다. 해제 전에 남는 기록·접근 종료 범위를 한 화면에서 설명합니다. */
export default function TripMembersPanel({
  trip,
  userId,
  invite,
  groupChat,
  refresh,
  left,
}: {
  trip: Trip;
  userId: string;
  invite: () => void;
  groupChat: () => void;
  refresh: () => Promise<void>;
  left: () => void;
}) {
  const owner = userId === trip.ownerId;
  const [choice, setChoice] = useState<Choice | null>(null);
  const busyRef = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const client = useQueryClient();
  const toast = useToast();
  const close = useCallback(() => {
    if (!busyRef.current) {
      setChoice(null);
      setError(null);
    }
  }, []);
  const stale =
    !!choice &&
    (trip.version !== choice.version ||
      (error instanceof ApiError &&
        (error.status === 409 || error.status === 404)));
  function open(id: string, nickname: string, leaving: boolean) {
    setError(null);
    setChoice({ id, nickname, leaving, version: trip.version });
  }
  async function confirm() {
    if (!choice || stale || busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      await api(
        `/trips/${trip.id}/${choice.leaving ? "leave" : `members/${choice.id}/remove`}`,
        { method: "POST", body: { version: choice.version } },
      );
      if (choice.leaving) {
        // 나간 여행의 비교 캐시와 대화 캐시는 제거합니다. 다시 참여할 때도 서버에서 새 권한으로 조회합니다.
        left();
        client.removeQueries({
          predicate: (q) => q.queryKey.includes(trip.id),
        });
        client.removeQueries({ queryKey: ["room"] });
        client.removeQueries({ queryKey: ["rooms"] });
        await client.invalidateQueries({ queryKey: ["trips"] });
        toast("여행 참여를 종료했어요.");
      } else {
        await refresh();
        await client.invalidateQueries({ queryKey: ["rooms"] });
        toast(`${choice.nickname}님의 여행 참여를 해제했어요.`);
      }
      setChoice(null);
    } catch (e) {
      setError(e as Error);
      // 확인 창을 열어 둔 동안 소유자가 참여를 해제했을 수도 있어요. 서버 권한을 바로 다시 조회합니다.
      if (accessEnded(e)) await refresh();
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }
  return (
    <section className="panel trip-members-panel" aria-label="여행 동행 관리">
      <div className="trip-members-heading">
        <div>
          <h2>
            <Users size={21} />
            함께하는 사람들 <span className="badge">{trip.members.length}</span>
          </h2>
          <p className="small muted">
            현재 편집 참여 계정이에요. 계획한 여행 인원 {trip.partySize}명과
            다를 수 있어요.
          </p>
        </div>
        {owner && (
          <button className="button secondary" onClick={invite}>
            <Mail size={16} />
            동행 초대
          </button>
        )}
      </div>
      <div className="trip-member-list">
        {trip.members.map((m) => (
          <article
            className="trip-member"
            key={m.id}
            aria-label={m.nickname + " 참여 정보"}
          >
            <div className="trip-member-profile">
              <span className="avatar">{m.nickname.slice(0, 1)}</span>
              <div>
                <strong>
                  {m.nickname}{" "}
                  {m.id === userId && <span className="badge subtle">나</span>}
                </strong>
                <p>
                  {m.preferences.join(" · ") ||
                    "기본 취향을 아직 고르지 않았어요."}
                </p>
                {m.id === trip.ownerId && (
                  <span className="badge subtle">여행 소유자</span>
                )}
              </div>
            </div>
            {owner && m.id !== trip.ownerId && (
              <button
                className="button secondary"
                onClick={() => open(m.id, m.nickname, false)}
                aria-label={m.nickname + "님 참여 해제"}
              >
                <UserMinus size={16} />
                참여 해제
              </button>
            )}
          </article>
        ))}
      </div>
      <div className="trip-members-chat">
        <p className="small muted">
          현재 참여 중인 동행만 여행 단체 대화를 볼 수 있어요. 개인 메시지 수신
          설정은 별도로 관리돼요.
        </p>
        <button className="button secondary full" onClick={groupChat}>
          <MessageCircle size={17} />
          여행 단체 대화 열기
        </button>
      </div>
      {!owner && (
        <div className="trip-members-leave">
          <p className="small muted">
            함께 준비하는 일을 마쳤다면 이 여행에서 나갈 수 있어요. 다시
            참여하려면 새 초대를 수락해야 해요.
          </p>
          <button
            className="button secondary"
            onClick={() =>
              open(
                userId,
                trip.members.find((m) => m.id === userId)?.nickname ?? "나",
                true,
              )
            }
          >
            <LogOut size={17} />이 여행에서 나가기
          </button>
        </div>
      )}
      {choice && (
        <Modal
          title={
            choice.leaving
              ? "이 여행에서 나갈까요?"
              : "동행의 참여를 해제할까요?"
          }
          onClose={close}
          className="membership-modal"
        >
          <div className="membership-target">
            <span className="badge">
              {choice.leaving ? "나의 여행 참여 종료" : "소유자의 동행 관리"}
            </span>
            <h3>{trip.title}</h3>
            <p>
              {choice.leaving ? "내" : choice.nickname + "님의"} 참여를
              종료해요.
            </p>
          </div>
          <ul className="membership-effects">
            <li>
              비공개 일정·예약 기록·경비와 여행 단체 대화의 조회·편집 권한이
              종료돼요.
            </li>
            <li>
              기존 일정·예약·메시지·지출 기록은 여행에 남아요. 외부 예약은
              취소되지 않아요.
            </li>
            <li>
              계획한 인원·참고 예산·여행 취향은 유지돼요. 개인 대화와 공개 일정
              링크는 별도로 관리해요.
            </li>
            <li>
              다시 참여하려면 소유자가 새 초대를 보내고 해당 동행이 수락해야
              해요.
            </li>
          </ul>
          {error && <ErrorState error={error} />}
          {stale && (
            <div className="notice danger" role="status">
              여행이 변경되어 진행할 수 없어요. 최신 참여자를 확인한 뒤 다시
              선택해 주세요.
            </div>
          )}
          <div className="membership-actions">
            {stale ? (
              <button
                className="button secondary full"
                disabled={busy}
                onClick={async () => {
                  await refresh();
                  close();
                }}
              >
                최신 참여자 확인하기
              </button>
            ) : (
              <>
                <button
                  className="button danger-button"
                  disabled={busy}
                  onClick={confirm}
                >
                  {busy ? (
                    <Loader2 size={17} className="spin" />
                  ) : (
                    <UserMinus size={17} />
                  )}{" "}
                  {choice.leaving
                    ? "확인 후 여행에서 나가기"
                    : "확인 후 참여 해제"}
                </button>
                <button
                  className="button secondary"
                  disabled={busy}
                  onClick={close}
                >
                  계속 함께 준비하기
                </button>
              </>
            )}
          </div>
        </Modal>
      )}
    </section>
  );
}
