import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ArrowDown,
  ArrowLeft,
  Check,
  Flag,
  Send,
  ShieldBan,
} from "lucide-react";
import { api, accessEnded, ApiError } from "../../shared/api";
import type { Room } from "../../shared/types";
import { useAuth, useToast } from "../../shared/providers";
import { ErrorState, Loading, Modal } from "../../shared/ui";
import {
  canWrite,
  draftAfterSent,
  hasMessageGap,
  mergeMessages,
  roomTitle,
  otherMemberCode,
  type ChatMessage,
} from "./messageState";

/** 방별로 마운트되는 대화 패널입니다. 전송·신고의 응답은 시작한 방에만 반영합니다.
 * 최신 페이지 폴링과 이전 페이지 조회를 합치며, 이전 글을 읽는 중에는 화면을 아래로 끌어내리지 않습니다. */
export default function ConversationPanel({
  id,
  draft,
  updateDraft,
  requestIdFor,
  acknowledge,
  greetings,
  onUseGreeting,
  onDiscardGreeting,
  onBack,
  onChanged,
  onAccessEnded,
}: {
  id: string;
  draft: string;
  updateDraft: (
    id: string,
    value: string | ((previous: string) => string),
  ) => void;
  requestIdFor: (id: string, body: string) => string;
  acknowledge: (id: string, requestId: string) => void;
  greetings: string[];
  onUseGreeting: (id: string, greeting: string) => void;
  onDiscardGreeting: (id: string, greeting: string) => void;
  onBack: () => void;
  onChanged: (room: Room) => void;
  onAccessEnded: (id: string, error?: unknown) => void;
}) {
  const { user } = useAuth();
  const member = user?.id || "";
  const client = useQueryClient();
  const toast = useToast();
  const room = useQuery({
    queryKey: ["room", id],
    queryFn: () => api<Room>("/messages/" + encodeURIComponent(id)),
    refetchInterval: (query) =>
      accessEnded(query.state.error) ||
      (query.state.error instanceof ApiError &&
        query.state.error.status === 401)
        ? false
        : 5000,
    retry: false,
    gcTime: 0,
  });
  const current = room.data;
  const ended =
    accessEnded(room.error) ||
    (room.error instanceof ApiError && room.error.status === 401);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [hasOlder, setHasOlder] = useState(false);
  const [olderCursor, setOlderCursor] = useState<string | null>(null);
  const [olderBusy, setOlderBusy] = useState(false);
  const [olderError, setOlderError] = useState<unknown>();
  const [action, setAction] = useState<string | null>(null);
  const [actionError, setActionError] = useState<unknown>();
  const [newMessages, setNewMessages] = useState(false);
  const [pendingLatest, setPendingLatest] = useState<Room | null>(null);
  const [locallyBlocked, setLocallyBlocked] = useState(false);
  const [blockOpen, setBlockOpen] = useState(false);
  const [report, setReport] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [reportError, setReportError] = useState<unknown>();
  const viewport = useRef<HTMLDivElement>(null);
  const composer = useRef<HTMLTextAreaElement>(null);
  const history = useRef<ChatMessage[]>([]);
  const mounted = useRef(false);
  const mutating = useRef(false);
  const paging = useRef(false);
  const firstPage = useRef(true);
  const nearBottom = useRef(true);
  const forceBottom = useRef(true);
  const previousLast = useRef<string | undefined>(undefined);
  const previousStatus = useRef<Room["status"] | undefined>(undefined);
  const restoreAnchor = useRef<{ height: number; top: number } | null>(null);
  const handledEnd = useRef(false);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    if (!current || ended) return;
    const incoming = current.messages || [];
    // 탈퇴 등으로 표시 정보가 정리되면 과거에 캐시한 닉네임도 최신 페이지부터 다시 받습니다.
    const privacyReset =
      previousStatus.current !== undefined &&
      previousStatus.current !== current.status &&
      current.status === "CLOSED";
    previousStatus.current = current.status;
    if (firstPage.current || privacyReset) {
      firstPage.current = false;
      history.current = incoming;
      setMessages(incoming);
      setHasOlder(Boolean(current.hasOlder));
      setOlderCursor(current.olderCursor || null);
      setPendingLatest(null);
      forceBottom.current = true;
      return;
    }
    if (hasMessageGap(history.current, incoming)) {
      if (!nearBottom.current) {
        setPendingLatest(current);
        setNewMessages(true);
        return;
      }
      // 한 페이지 넘게 도착한 경우 최신 묶음으로 옮기고, 이전 기록은 이전 보기로 이어갑니다.
      history.current = incoming;
      setMessages(incoming);
      setHasOlder(Boolean(current.hasOlder));
      setOlderCursor(current.olderCursor || null);
      setPendingLatest(null);
      forceBottom.current = true;
      return;
    }
    history.current = mergeMessages(history.current, incoming);
    setMessages(history.current);
  }, [current, ended]);

  useLayoutEffect(() => {
    if (!ended || handledEnd.current) return;
    handledEnd.current = true;
    history.current = [];
    setMessages([]);
    setReport(null);
    setPendingLatest(null);
    updateDraft(id, "");
    onAccessEnded(id, room.error);
    void client.invalidateQueries({ queryKey: ["rooms"] });
    if (room.error instanceof ApiError && room.error.status === 401)
      void client.invalidateQueries({ queryKey: ["me"] });
  }, [ended, id, updateDraft, onAccessEnded, client, room.error]);

  useLayoutEffect(() => {
    const scroller = viewport.current;
    if (!scroller) return;
    const last = messages.at(-1)?.id;
    if (restoreAnchor.current) {
      scroller.scrollTop =
        restoreAnchor.current.top +
        scroller.scrollHeight -
        restoreAnchor.current.height;
      restoreAnchor.current = null;
    } else if (forceBottom.current || nearBottom.current) {
      scroller.scrollTop = scroller.scrollHeight;
      nearBottom.current = true;
      forceBottom.current = false;
      setNewMessages(false);
    } else if (last && previousLast.current && last !== previousLast.current)
      setNewMessages(true);
    previousLast.current = last;
  }, [messages]);

  useEffect(() => {
    const scroller = viewport.current;
    if (!scroller) return;
    // 키보드·주소창 때문에 읽기 영역 높이가 바뀌어도 마지막 글을 보고 있었다면 계속 끝을 유지합니다.
    // 이전 대화를 읽는 중에는 사용자의 위치를 바꾸지 않습니다.
    const observer = new ResizeObserver(() => {
      if (nearBottom.current && !restoreAnchor.current)
        scroller.scrollTop = scroller.scrollHeight;
    });
    observer.observe(scroller);
    return () => observer.disconnect();
  }, [Boolean(current), ended]);

  async function refresh() {
    await Promise.all([
      client.invalidateQueries({ queryKey: ["rooms"] }),
      client.invalidateQueries({ queryKey: ["room", id] }),
    ]);
  }
  async function loadOlder() {
    if (!olderCursor || paging.current) return;
    paging.current = true;
    setOlderBusy(true);
    setOlderError(undefined);
    try {
      const page = await api<Room>(
        `/messages/${encodeURIComponent(id)}?before=${encodeURIComponent(olderCursor)}&limit=50`,
      );
      if (!mounted.current) return;
      const scroller = viewport.current;
      if (scroller)
        restoreAnchor.current = {
          height: scroller.scrollHeight,
          top: scroller.scrollTop,
        };
      history.current = mergeMessages(history.current, page.messages || []);
      setMessages(history.current);
      setHasOlder(Boolean(page.hasOlder));
      setOlderCursor(page.olderCursor || null);
    } catch (failure) {
      if (!mounted.current) return;
      setOlderError(failure);
      if (accessEnded(failure)) await refresh();
    } finally {
      paging.current = false;
      if (mounted.current) setOlderBusy(false);
    }
  }
  const latest = () => {
    if (pendingLatest) {
      const incoming = pendingLatest.messages || [];
      history.current = incoming;
      forceBottom.current = true;
      setMessages(incoming);
      setHasOlder(Boolean(pendingLatest.hasOlder));
      setOlderCursor(pendingLatest.olderCursor || null);
      setPendingLatest(null);
    } else if (viewport.current)
      viewport.current.scrollTop = viewport.current.scrollHeight;
    nearBottom.current = true;
    setNewMessages(false);
    composer.current?.focus({ preventScroll: true });
  };
  const writable = Boolean(
    current && canWrite(current) && !locallyBlocked && !ended,
  );
  async function send(event: React.FormEvent) {
    event.preventDefault();
    if (mutating.current || !writable || !draft.trim()) return;
    const sent = draft;
    const requestId = requestIdFor(id, sent);
    mutating.current = true;
    setAction("send");
    setActionError(undefined);
    try {
      const data = await api<Room>("/messages/" + encodeURIComponent(id), {
        method: "POST",
        body: { body: sent, requestId },
      });
      if (client.getQueryData<{ id: string }>(["me"])?.id !== member) return;
      // 결과는 명시한 방의 캐시에만 저장합니다. 현재 선택한 다른 방의 작성 내용은 건드리지 않습니다.
      acknowledge(id, requestId);
      updateDraft(id, (previous) => draftAfterSent(previous, sent));
      if (mounted.current) {
        forceBottom.current = true;
        nearBottom.current = true;
        client.setQueryData(["room", id], data);
      }
      await client.invalidateQueries({ queryKey: ["rooms"] });
    } catch (failure) {
      if (mounted.current) setActionError(failure);
      if (accessEnded(failure)) await refresh();
    } finally {
      mutating.current = false;
      if (mounted.current) setAction(null);
    }
  }
  async function respond(accept: boolean) {
    if (mutating.current) return;
    mutating.current = true;
    setAction(accept ? "accept" : "decline");
    setActionError(undefined);
    try {
      const data = await api<Room>(
        `/messages/${encodeURIComponent(id)}/respond`,
        { method: "POST", body: { accept } },
      );
      if (
        !mounted.current ||
        client.getQueryData<{ id: string }>(["me"])?.id !== member
      )
        return;
      client.setQueryData(["room", id], data);
      await client.invalidateQueries({ queryKey: ["rooms"] });
      if (mounted.current) onChanged(data);
    } catch (failure) {
      if (mounted.current) setActionError(failure);
      await refresh();
    } finally {
      mutating.current = false;
      if (mounted.current) setAction(null);
    }
  }
  async function block() {
    if (!current || mutating.current) return;
    const other =
      current.requesterId === member
        ? current.recipientId
        : current.requesterId;
    mutating.current = true;
    setAction("block");
    setActionError(undefined);
    try {
      await api("/messages/blocks/" + encodeURIComponent(other), {
        method: "PUT",
        body: { enabled: true },
      });
      if (mounted.current) {
        setLocallyBlocked(true);
        setBlockOpen(false);
        toast("사용자를 차단했어요. 새 메시지와 요청을 주고받지 않아요.");
      }
      await refresh();
    } catch (failure) {
      if (mounted.current) setActionError(failure);
    } finally {
      mutating.current = false;
      if (mounted.current) setAction(null);
    }
  }
  async function sendReport(event: React.FormEvent) {
    event.preventDefault();
    if (!report || !reason.trim() || mutating.current) return;
    mutating.current = true;
    setAction("report");
    setReportError(undefined);
    try {
      await api("/reports", {
        method: "POST",
        body: { type: "MESSAGE", targetId: report, reason },
      });
      if (mounted.current) {
        toast("신고를 접수했어요.");
        setReport(null);
        setReason("");
      }
    } catch (failure) {
      if (mounted.current) setReportError(failure);
    } finally {
      mutating.current = false;
      if (mounted.current) setAction(null);
    }
  }
  if (room.isPending)
    return (
      <section className="conversation-panel">
        <button className="conversation-back text-button" onClick={onBack}>
          <ArrowLeft size={18} />
          목록으로
        </button>
        <Loading text="대화를 불러오고 있어요" />
      </section>
    );
  if (ended)
    return (
      <section
        className="conversation-panel conversation-unavailable"
        role="status"
      >
        <h2>이 대화에 접근할 수 없어요</h2>
        <p>
          참여 권한이나 로그인 상태가 바뀌었어요. 여행 단체 대화는 현재 참여
          중인 동행만 이용할 수 있어요.
        </p>
        <div className="conversation-recovery">
          <button className="button secondary" onClick={onBack}>
            다른 대화 보기
          </button>
          <Link className="button secondary" to="/trips">
            내 여행 확인하기
          </Link>
        </div>
      </section>
    );
  if (!current)
    return (
      <section className="conversation-panel">
        <button className="conversation-back text-button" onClick={onBack}>
          <ArrowLeft size={18} />
          목록으로
        </button>
        <ErrorState
          error={room.error || new Error("대화를 불러오지 못했어요.")}
          retry={() => void room.refetch()}
        />
      </section>
    );
  const blocked = current.blocked || locallyBlocked;
  return (
    <section
      className={
        "conversation-panel" +
        (!writable ? " is-readonly" : "") +
        (current.status === "PENDING" ? " is-pending" : "")
      }
      aria-label={roomTitle(current, member) + " 대화"}
    >
      <header className="conversation-header">
        <button
          className="icon-button conversation-back"
          onClick={onBack}
          aria-label="대화 목록으로 돌아가기"
        >
          <ArrowLeft size={20} />
        </button>
        <div>
          <strong>{roomTitle(current, member)}</strong>
          <span>
            {current.tripId
              ? "현재 동행과 함께하는 단체 대화"
              : current.status === "PENDING"
                ? "대화 요청 · 수락 대기"
                : `개인 대화 · 회원 ${otherMemberCode(current, member)}`}
          </span>
        </div>
        {!current.tripId && current.status !== "CLOSED" && !blocked && (
          <button
            className="text-button conversation-block"
            disabled={Boolean(action)}
            onClick={() => {
              setActionError(undefined);
              setBlockOpen(true);
            }}
          >
            <ShieldBan size={16} />
            사용자 차단
          </button>
        )}
      </header>
      {room.error && (
        <div className="conversation-sync-error">
          <ErrorState error={room.error} retry={() => void room.refetch()} />
        </div>
      )}
      {(blocked ||
        current.status === "CLOSED" ||
        current.status === "DECLINED" ||
        current.status === "PENDING" ||
        (!writable && current.status === "ACTIVE")) && (
        <div className="conversation-notice">
          <p>
            <span className="conversation-notice-full">
              {blocked
                ? "차단된 대화예요. 기존 기록은 확인할 수 있지만 새 메시지는 보낼 수 없어요."
                : current.status === "CLOSED"
                  ? "탈퇴한 회원과의 대화가 종료됐어요. 기존 기록은 확인할 수 있어요."
                  : current.status === "DECLINED"
                    ? "거절된 대화 요청이에요. 새 메시지는 보낼 수 없어요."
                    : current.status === "PENDING"
                      ? current.recipientId === member
                        ? "새 대화 요청이에요. 첫 인사를 읽고 수락할지 선택해 주세요."
                        : "상대방의 수락을 기다리고 있어요."
                      : "현재 새 메시지를 보낼 수 없어요. 상태를 다시 확인해 주세요."}
            </span>
            {current.status === "PENDING" && !blocked && (
              <span className="conversation-notice-compact">
                {current.recipientId === member
                  ? "새 대화 요청이에요."
                  : "상대방의 수락을 기다리고 있어요."}
              </span>
            )}
          </p>
          {!blocked &&
            current.status === "PENDING" &&
            current.recipientId === member && (
              <div className="conversation-request-actions">
                <button
                  className="button secondary"
                  disabled={Boolean(action)}
                  onClick={() => void respond(false)}
                >
                  거절
                </button>
                <button
                  className="button primary"
                  disabled={Boolean(action)}
                  onClick={() => void respond(true)}
                >
                  <Check size={17} />
                  수락
                </button>
              </div>
            )}
          {blocked && (
            <Link className="text-button" to="/profile">
              차단 설정 확인하기
            </Link>
          )}
        </div>
      )}
      <div className="conversation-history-wrap">
        <div
          className="conversation-history"
          ref={viewport}
          tabIndex={0}
          role="region"
          aria-label="대화 내용"
          onScroll={() => {
            const el = viewport.current;
            if (el) {
              nearBottom.current =
                el.scrollHeight - el.clientHeight - el.scrollTop < 80;
              if (nearBottom.current && !pendingLatest) setNewMessages(false);
            }
          }}
        >
          {hasOlder && (
            <button
              className="button secondary history-older"
              onClick={() => void loadOlder()}
              disabled={olderBusy}
            >
              {olderBusy ? "이전 대화를 불러오는 중…" : "이전 대화 보기"}
            </button>
          )}
          {Boolean(olderError) && (
            <ErrorState error={olderError} retry={() => void loadOlder()} />
          )}
          {!messages.length && (
            <p className="conversation-empty">
              아직 메시지가 없어요. 첫 이야기를 남겨 보세요.
            </p>
          )}
          {messages.map((message) => (
            <article
              key={message.id}
              className={
                "conversation-message" +
                (message.senderId === member ? " own" : "")
              }
            >
              <span className="conversation-author">{message.nickname}</span>
              <p className="conversation-bubble">{message.body}</p>
              <div className="conversation-message-meta">
                <time dateTime={message.createdAt}>
                  {new Date(message.createdAt).toLocaleString("ko-KR", {
                    month: "numeric",
                    day: "numeric",
                    hour: "2-digit",
                    minute: "2-digit",
                  })}
                </time>
                {message.senderId !== member && (
                  <button
                    className="icon-button"
                    aria-label={message.nickname + "님의 메시지 신고"}
                    onClick={() => {
                      setReportError(undefined);
                      setReport(message.id);
                    }}
                  >
                    <Flag size={14} />
                  </button>
                )}
              </div>
            </article>
          ))}
        </div>
        {newMessages && (
          <button
            className="button secondary conversation-new"
            onClick={latest}
          >
            <ArrowDown size={16} />새 메시지 보기
          </button>
        )}
      </div>
      {Boolean(actionError) && !blockOpen && (
        <div className="conversation-action-error">
          <ErrorState error={actionError} />
          {actionError instanceof ApiError && actionError.status === 0 && (
            <p>
              전송 결과를 확인하지 못했어요. 최근 메시지를 확인한 뒤 다시 보내
              주세요.
            </p>
          )}
        </div>
      )}
      {greetings.length > 0 && (
        <details className="conversation-greetings">
          <summary>아직 보내지 않은 첫 인사 {greetings.length}개</summary>
          <p>
            기존 방을 열어 첫 인사는 전송되지 않았어요. 입력창이 비어 있을 때
            불러올 수 있어요.
          </p>
          {greetings.map((greeting) => (
            <div key={greeting}>
              <p>{greeting}</p>
              <div>
                <button
                  className="button secondary"
                  disabled={Boolean(draft)}
                  onClick={() => onUseGreeting(id, greeting)}
                >
                  입력창으로 불러오기
                </button>
                <button
                  className="text-button"
                  onClick={() => onDiscardGreeting(id, greeting)}
                >
                  보관 문구 지우기
                </button>
              </div>
            </div>
          ))}
        </details>
      )}
      {writable ? (
        <form className="conversation-composer" onSubmit={send}>
          <label className="sr-only" htmlFor="conversation-draft">
            메시지
          </label>
          <textarea
            id="conversation-draft"
            ref={composer}
            rows={2}
            value={draft}
            onChange={(event) => updateDraft(id, event.target.value)}
            disabled={!writable || Boolean(action)}
            maxLength={2000}
            placeholder={
              writable
                ? "여행 이야기를 나눠 보세요"
                : "현재 새 메시지를 보낼 수 없어요"
            }
            onKeyDown={(event) => {
              if (
                event.key === "Enter" &&
                !event.shiftKey &&
                !event.nativeEvent.isComposing &&
                event.nativeEvent.keyCode !== 229
              ) {
                event.preventDefault();
                event.currentTarget.form?.requestSubmit();
              }
            }}
          />
          <button
            className="button primary"
            type="submit"
            disabled={!writable || Boolean(action) || !draft.trim()}
            aria-label="메시지 보내기"
          >
            <Send size={19} />
            <span>{action === "send" ? "전송 중" : "보내기"}</span>
          </button>
          <p className="composer-help">
            Enter로 보내기 · Shift+Enter로 줄바꿈{" "}
            <span>{draft.length.toLocaleString()} / 2,000자</span>
          </p>
        </form>
      ) : (
        <div
          className="conversation-composer conversation-readonly"
          role="status"
        >
          {current.status === "PENDING" && !blocked
            ? "수락 후 메시지를 보낼 수 있어요."
            : "현재는 대화 기록만 볼 수 있어요."}
        </div>
      )}
      {blockOpen && (
        <Modal
          title="사용자를 차단할까요?"
          onClose={() => setBlockOpen(false)}
          className="message-action-dialog"
        >
          <p className="field-help">
            기존 대화는 남지만 새 메시지와 대화 요청을 주고받지 않아요. 함께하는
            여행의 참여 권한은 따로 관리해요.
          </p>
          {Boolean(actionError) && <ErrorState error={actionError} />}
          <div className="message-dialog-actions">
            <button
              className="button secondary"
              disabled={Boolean(action)}
              onClick={() => setBlockOpen(false)}
            >
              돌아가기
            </button>
            <button
              className="button primary"
              disabled={Boolean(action)}
              onClick={() => void block()}
            >
              {action === "block" ? "차단 중…" : "사용자 차단"}
            </button>
          </div>
        </Modal>
      )}
      {report && (
        <Modal
          title="메시지 신고"
          onClose={() => {
            setReport(null);
            setReportError(undefined);
          }}
          className="message-action-dialog"
        >
          <p className="field-help">
            선택한 메시지 한 건과 당시 닉네임만 운영 확인용으로 보존해요. 다른
            개인 대화는 운영자에게 공개하지 않아요.
          </p>
          <form onSubmit={sendReport}>
            <label htmlFor="message-report-reason">신고 사유</label>
            <textarea
              id="message-report-reason"
              rows={4}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              maxLength={1000}
              required
              disabled={action === "report"}
            />
            {Boolean(reportError) && <ErrorState error={reportError} />}
            <button
              className="button primary full"
              type="submit"
              disabled={Boolean(action) || !reason.trim()}
            >
              {action === "report" ? "접수 중…" : "신고 접수"}
            </button>
          </form>
        </Modal>
      )}
    </section>
  );
}
