import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  BarChart3,
  Check,
  Clock3,
  Loader2,
  LockKeyhole,
  Trash2,
  Users,
} from "lucide-react";
import type { TripPoll } from "../../shared/types";
import { ErrorState, Modal } from "../../shared/ui";
import {
  formatKstWallDateTime,
  isLeadingPollOption,
  pollOptionPercent,
  pollStatusLabel,
  samePollSelection,
  selectedPollOptionIds,
  togglePollSelection,
} from "./tripPolls";

type Confirmation = { action: "close" | "delete"; version: number };

/** createdAt은 기존 DB 현지 시각 계약을 유지하므로 마감 시각과 별도로 표시합니다. */
function pollCreatedDateTime(value: string) {
  const date = new Date(value);
  return Number.isFinite(date.getTime())
    ? date.toLocaleString("ko-KR", {
        month: "long",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit",
      })
    : "시각 확인 필요";
}

/** 진행·직접 마감·자동 종료가 가리키는 시각을 섞지 않고 실제 상태에 맞춰 설명합니다. */
function pollTimingLabel(poll: TripPoll) {
  if (poll.status === "OPEN")
    return poll.closesAt
      ? `${formatKstWallDateTime(poll.closesAt)} 자동 종료`
      : "직접 마감할 때까지 진행";
  if (poll.status === "CLOSED")
    return poll.closedAt
      ? `${formatKstWallDateTime(poll.closedAt)} 직접 마감`
      : "직접 마감 시각 확인 필요";
  return poll.closesAt
    ? `${formatKstWallDateTime(poll.closesAt)} 기간 종료`
    : "종료 시각 확인 필요";
}

/** 한 투표의 입력 초안은 15초 재조회 중 표 수만 바뀌어도 유지하고, 서버의 내 선택이 바뀐 경우에만 다시 맞춥니다. */
export default function TripPollCard({
  poll,
  saveBallot,
  closePoll,
  deletePoll,
}: {
  poll: TripPoll;
  saveBallot: (pollId: string, optionIds: string[]) => Promise<void>;
  closePoll: (pollId: string, version: number) => Promise<void>;
  deletePoll: (pollId: string, version: number) => Promise<void>;
}) {
  const serverSelection = useMemo(() => selectedPollOptionIds(poll), [poll]);
  const serverSelectionKey = serverSelection.join("|");
  const optionKey = poll.options.map((option) => option.id).join("|");
  const [draft, setDraft] = useState(serverSelection);
  const busyRef = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null);

  useEffect(() => {
    const available = new Set(poll.options.map((option) => option.id));
    setDraft(serverSelection.filter((id) => available.has(id)));
    setError(null);
    // 문자열 키를 사용해 표 수만 갱신될 때 작성 중인 선택을 덮지 않습니다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [poll.id, serverSelectionKey, optionKey]);

  const open = poll.status === "OPEN";
  const changed = !samePollSelection(draft, serverSelection);
  const staleConfirmation =
    confirmation != null && confirmation.version !== poll.version;
  const dismissConfirmation = useCallback(() => {
    if (!busyRef.current) {
      setConfirmation(null);
      setError(null);
    }
  }, []);

  function toggle(optionId: string) {
    if (!open || busy) return;
    setError(null);
    setDraft((current) =>
      togglePollSelection(poll.selectionType, current, optionId),
    );
  }

  async function submitBallot() {
    if (!open || !changed || busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      await saveBallot(poll.id, draft);
    } catch (caught) {
      setError(caught as Error);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }

  async function confirmManagement() {
    if (!confirmation || staleConfirmation || busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      if (confirmation.action === "close")
        await closePoll(poll.id, confirmation.version);
      else await deletePoll(poll.id, confirmation.version);
      setConfirmation(null);
    } catch (caught) {
      setError(caught as Error);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }

  const submitLabel =
    serverSelection.length > 0 && draft.length === 0
      ? "내 투표 취소하기"
      : serverSelection.length > 0
        ? "내 선택 바꾸기"
        : "내 선택 저장하기";

  return (
    <article className={`trip-poll-card poll-${poll.status.toLowerCase()}`}>
      <header className="trip-poll-card-heading">
        <div>
          <span className={`badge poll-status poll-status-${poll.status}`}>
            {open ? <BarChart3 size={14} /> : <LockKeyhole size={14} />}
            {pollStatusLabel(poll.status)}
          </span>
          <h3>{poll.title}</h3>
        </div>
        <div className="trip-poll-author">
          <span>{poll.creatorName}님이 만듦</span>
          <time dateTime={poll.createdAt}>
            {pollCreatedDateTime(poll.createdAt)}
          </time>
        </div>
      </header>

      <div className="trip-poll-summary" id={`poll-summary-${poll.id}`}>
        <span>
          <Users size={16} />
          {poll.votedMemberCount}/{poll.eligibleMemberCount}명 참여
        </span>
        <span>
          <Clock3 size={16} />
          {pollTimingLabel(poll)}
        </span>
        <span>
          {poll.selectionType === "SINGLE" ? "하나만 선택" : "여러 개 선택"}
        </span>
      </div>

      <fieldset
        className="trip-poll-options"
        disabled={!open || busy}
        aria-describedby={`poll-summary-${poll.id}`}
      >
        <legend className="sr-only">{poll.title} 선택지</legend>
        {[...poll.options]
          .sort((left, right) => left.sortOrder - right.sortOrder)
          .map((option) => {
            const selected = draft.includes(option.id);
            const percent = pollOptionPercent(poll, option.voteCount);
            const leading = isLeadingPollOption(poll, option.voteCount);
            return (
              <label
                className={`trip-poll-option ${selected ? "selected" : ""}`}
                key={option.id}
              >
                <span className="trip-poll-option-control">
                  <input
                    type={
                      poll.selectionType === "SINGLE" ? "radio" : "checkbox"
                    }
                    name={`trip-poll-${poll.id}`}
                    value={option.id}
                    checked={selected}
                    onChange={() => toggle(option.id)}
                  />
                  <span className="trip-poll-option-label">
                    <strong>{option.label}</strong>
                    <span>
                      {option.voteCount}명 · {percent}%
                      {option.selectedByMe && " · 저장된 내 선택"}
                      {leading &&
                        ` · ${poll.options.filter((item) => item.voteCount === option.voteCount).length > 1 ? "공동 1위" : "현재 1위"}`}
                    </span>
                  </span>
                  {selected && <Check size={18} aria-hidden="true" />}
                </span>
                <span className="trip-poll-result-track" aria-hidden="true">
                  <span style={{ width: `${percent}%` }} />
                </span>
              </label>
            );
          })}
      </fieldset>

      {open ? (
        <div className="trip-poll-vote-area">
          <p className="field-help">
            마감 전에는 선택을 바꾸거나 취소할 수 있어요. 다른 사람의 개별
            선택은 공개하지 않아요.
          </p>
          <div className="trip-poll-vote-actions">
            {poll.selectionType === "SINGLE" && draft.length > 0 && (
              <button
                type="button"
                className="text-button trip-poll-clear"
                onClick={() => {
                  setDraft([]);
                  setError(null);
                }}
                disabled={busy}
              >
                선택 지우기
              </button>
            )}
            <button
              type="button"
              className="button primary trip-poll-save"
              disabled={!changed || busy}
              onClick={submitBallot}
            >
              {busy && <Loader2 size={17} className="spin" />}
              {busy ? "선택을 저장하고 있어요" : submitLabel}
            </button>
          </div>
        </div>
      ) : (
        <p className="trip-poll-finished-note" role="status">
          이 투표는{" "}
          {poll.status === "EXPIRED" ? "마감 시각이 지나" : "직접 마감되어"} 더
          이상 선택을 바꿀 수 없어요.
        </p>
      )}

      {error && !confirmation && <ErrorState error={error} />}

      {poll.canManage && (
        <div className="trip-poll-manage-actions">
          {open && (
            <button
              type="button"
              className="button secondary"
              onClick={() => {
                setError(null);
                setConfirmation({ action: "close", version: poll.version });
              }}
              disabled={busy}
            >
              <LockKeyhole size={16} />
              투표 마감
            </button>
          )}
          <button
            type="button"
            className="text-button danger-text"
            onClick={() => {
              setError(null);
              setConfirmation({ action: "delete", version: poll.version });
            }}
            disabled={busy}
          >
            <Trash2 size={16} />
            투표 삭제
          </button>
        </div>
      )}

      {confirmation && (
        <Modal
          title={
            confirmation.action === "close"
              ? "이 투표를 마감할까요?"
              : "이 투표를 삭제할까요?"
          }
          onClose={dismissConfirmation}
          className="trip-poll-confirm-modal"
        >
          <div className="trip-poll-confirm-target">
            <span className="badge">
              {confirmation.action === "close" ? "투표 마감" : "투표 삭제"}
            </span>
            <strong>{poll.title}</strong>
            <p>
              현재 {poll.votedMemberCount}/{poll.eligibleMemberCount}명이
              참여했어요.
            </p>
          </div>
          <p className="field-help">
            {confirmation.action === "close"
              ? "마감하면 결과는 남지만 동행이 선택을 추가하거나 바꿀 수 없어요."
              : "질문과 선택 결과가 여행에서 사라지며 되돌릴 수 없어요."}
          </p>
          {error && <ErrorState error={error} />}
          {staleConfirmation && (
            <div className="notice danger" role="status">
              확인하는 동안 투표가 변경됐어요. 최신 상태를 확인한 뒤 다시 진행해
              주세요.
            </div>
          )}
          <div className="trip-poll-confirm-actions">
            {staleConfirmation ? (
              <button
                type="button"
                className="button secondary full"
                onClick={() => {
                  setConfirmation(null);
                  setError(null);
                }}
              >
                최신 투표 확인하기
              </button>
            ) : (
              <>
                <button
                  type="button"
                  className={`button ${confirmation.action === "delete" ? "danger-button" : "primary"}`}
                  onClick={confirmManagement}
                  disabled={busy}
                >
                  {busy && <Loader2 size={17} className="spin" />}
                  {confirmation.action === "close"
                    ? "확인 후 투표 마감"
                    : "확인 후 투표 삭제"}
                </button>
                <button
                  type="button"
                  className="button secondary"
                  onClick={() => setConfirmation(null)}
                  disabled={busy}
                >
                  취소
                </button>
              </>
            )}
          </div>
        </Modal>
      )}
    </article>
  );
}
