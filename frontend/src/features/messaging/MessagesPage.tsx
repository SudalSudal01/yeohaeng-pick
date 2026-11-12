import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type CSSProperties,
} from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { MessageCircle, Plus } from "lucide-react";
import { api, ApiError } from "../../shared/api";
import type { Room } from "../../shared/types";
import { useAuth } from "../../shared/providers";
import { ErrorState, Loading } from "../../shared/ui";
import ConversationPanel from "./ConversationPanel";
import RecipientRequestModal from "./RecipientRequestModal";
import {
  roomTitle,
  otherMemberCode,
  roomsForTab,
  type ConversationTab,
} from "./messageState";
import { useMessageViewport } from "./useMessageViewport";
import "./MessagesPage.css";

/** 목록과 상세를 모바일에서 한 화면씩 보여 주고, 대화방 선택은 주소에 남깁니다.
 * 이메일 없이 닉네임 검색 또는 커뮤니티 작성자 링크로 대화를 요청합니다. */
export default function MessagesPage() {
  const [params, setParams] = useSearchParams();
  const { user } = useAuth();
  const client = useQueryClient();
  const id = params.get("room") || "";
  const recipient = params.get("recipient") || "";
  const page = useRef<HTMLDivElement>(null);
  const height = useMessageViewport(page);
  const [write, setWrite] = useState(Boolean(recipient));
  const [tab, setTab] = useState<ConversationTab>("ACTIVE");
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [greetings, setGreetings] = useState<Record<string, string[]>>({});
  const sendAttempts = useRef<
    Record<string, { body: string; requestId: string }>
  >({});
  const [unavailable, setUnavailable] = useState<string[]>([]);
  const [sessionEnded, setSessionEnded] = useState(false);
  const clearedSession = useRef(false);
  const member = user?.id || "";
  useEffect(() => {
    if (recipient) setWrite(true);
  }, [recipient]);
  const rooms = useQuery({
    queryKey: ["rooms"],
    queryFn: () => api<Room[]>("/messages"),
    refetchInterval: 10000,
  });
  const expired =
    sessionEnded ||
    (rooms.error instanceof ApiError && rooms.error.status === 401);
  useEffect(() => {
    if (!rooms.data) return;
    // 재초대 등으로 서버 목록에 다시 포함된 방은 새 권한으로 열 수 있게 합니다.
    setUnavailable((previous) => {
      const next = previous.filter(
        (roomId) => !rooms.data.some((room) => room.id === roomId),
      );
      return next.length === previous.length ? previous : next;
    });
  }, [rooms.data]);
  useEffect(() => {
    if (!expired || clearedSession.current) return;
    clearedSession.current = true;
    // 목록이나 상세 중 어느 요청이 먼저 401을 받아도 남아 있던 비공개 방·초안·요청 모달을 함께 정리합니다.
    setSessionEnded(true);
    setWrite(false);
    setDrafts({});
    setGreetings({});
    sendAttempts.current = {};
    client.setQueryData(["rooms"], []);
    client.removeQueries({ queryKey: ["room"] });
    client.removeQueries({ queryKey: ["message-recipient"] });
    client.removeQueries({ queryKey: ["message-recipient-search"] });
    void client.invalidateQueries({ queryKey: ["me"] });
  }, [expired, client]);
  const listed = roomsForTab(rooms.data || [], tab, member).filter(
    (room) => !unavailable.includes(room.id),
  );
  const count = (value: ConversationTab) =>
    roomsForTab(rooms.data || [], value, member).filter(
      (room) => !unavailable.includes(room.id),
    ).length;
  const close = useCallback(() => {
    setWrite(false);
    setParams((previous) => {
      const next = new URLSearchParams(previous);
      next.delete("recipient");
      next.delete("name");
      return next;
    });
  }, [setParams]);
  const updateDraft = useCallback(
    (roomId: string, value: string | ((previous: string) => string)) => {
      // 비동기 전송의 완료 순서와 관계없이 지정한 방의 초안만 바꿉니다. 브라우저 저장소에 개인 문구를 남기지 않습니다.
      if (
        typeof value === "string" &&
        sendAttempts.current[roomId]?.body !== value
      )
        delete sendAttempts.current[roomId];
      setDrafts((previous) => ({
        ...previous,
        [roomId]:
          typeof value === "function" ? value(previous[roomId] || "") : value,
      }));
    },
    [],
  );
  const requestIdFor = useCallback((roomId: string, body: string) => {
    const attempt = sendAttempts.current[roomId];
    if (attempt?.body === body) return attempt.requestId;
    const requestId = crypto.randomUUID();
    sendAttempts.current[roomId] = { body, requestId };
    return requestId;
  }, []);
  const acknowledge = useCallback((roomId: string, requestId: string) => {
    if (sendAttempts.current[roomId]?.requestId === requestId)
      delete sendAttempts.current[roomId];
  }, []);
  const accessEnded = useCallback((roomId: string, error?: unknown) => {
    if (error instanceof ApiError && error.status === 401)
      setSessionEnded(true);
    setUnavailable((previous) =>
      previous.includes(roomId) ? previous : [...previous, roomId],
    );
    setGreetings((previous) => ({ ...previous, [roomId]: [] }));
  }, []);
  const select = (room: Room) => {
    setParams({ room: room.id });
  };
  const changed = (room: Room) => {
    if (room.status === "ACTIVE") setTab("ACTIVE");
    else if (room.status === "DECLINED") setParams({});
  };
  const requested = (room: Room, greeting: string) => {
    if (room.requestCreated === false) {
      // 기존 방으로 이동했다고 첫 인사가 전송된 것은 아닙니다. 작성 중인 초안을 덮어쓰지 않고 별도로 보관합니다.
      if (!drafts[room.id]) updateDraft(room.id, greeting);
      else if (drafts[room.id] !== greeting)
        setGreetings((previous) => ({
          ...previous,
          [room.id]: [...new Set([...(previous[room.id] || []), greeting])],
        }));
    }
    setWrite(false);
    setTab(
      room.status === "PENDING"
        ? room.recipientId === member
          ? "INCOMING"
          : "OUTGOING"
        : "ACTIVE",
    );
    setParams({ room: room.id });
    void client.invalidateQueries({ queryKey: ["rooms"] });
  };
  const discardGreeting = (roomId: string, greeting: string) =>
    setGreetings((previous) => ({
      ...previous,
      [roomId]: (previous[roomId] || []).filter((value) => value !== greeting),
    }));
  const useGreeting = (roomId: string, greeting: string) => {
    if (drafts[roomId]) return;
    updateDraft(roomId, greeting);
    discardGreeting(roomId, greeting);
  };
  const tabs: { value: ConversationTab; title: string }[] = [
    { value: "ACTIVE", title: "대화" },
    { value: "INCOMING", title: "받은 요청" },
    { value: "OUTGOING", title: "보낸 요청" },
  ];
  if (expired)
    return (
      <div
        ref={page}
        className="page messaging-page messaging-session-ended"
        role="alert"
      >
        <h1>다시 로그인해 주세요</h1>
        <p>
          로그인이 만료되어 대화 화면을 닫았어요. 로그인하면 대화를 다시 확인할
          수 있어요.
        </p>
        <Link className="button primary" to="/login?next=%2Fmessages">
          다시 로그인
        </Link>
      </div>
    );
  return (
    <div
      ref={page}
      className={"page messaging-page" + (id ? " has-conversation" : "")}
      style={
        height
          ? ({ "--messaging-page-height": `${height}px` } as CSSProperties)
          : undefined
      }
    >
      <div className="messaging-heading">
        <div>
          <span className="eyebrow">BETTER TOGETHER</span>
          <h1>여행을 잇는 대화</h1>
          <p>닉네임으로 회원을 찾고, 수락한 뒤 편하게 이야기해요.</p>
        </div>
        <button className="button primary" onClick={() => setWrite(true)}>
          <Plus size={18} />새 대화
        </button>
      </div>
      <div className="messaging-workspace">
        <aside className="conversation-list" aria-label="대화 목록">
          <div
            className="conversation-tabs"
            role="group"
            aria-label="대화 구분"
          >
            {tabs.map((item) => (
              <button
                key={item.value}
                type="button"
                className={tab === item.value ? "active" : ""}
                aria-pressed={tab === item.value}
                onClick={() => setTab(item.value)}
              >
                {item.title}
                <span>{count(item.value)}</span>
              </button>
            ))}
          </div>
          <div className="conversation-list-scroll">
            {rooms.isPending ? (
              <Loading text="대화 목록을 불러오고 있어요" />
            ) : rooms.error ? (
              <ErrorState
                error={rooms.error}
                retry={() => void rooms.refetch()}
              />
            ) : !listed.length ? (
              <div className="conversation-list-empty">
                <MessageCircle size={30} aria-hidden="true" />
                <h2>
                  {tab === "INCOMING"
                    ? "받은 요청이 없어요"
                    : tab === "OUTGOING"
                      ? "보낸 요청이 없어요"
                      : "아직 대화가 없어요"}
                </h2>
                <p>
                  {tab === "INCOMING"
                    ? "다른 회원이 첫 인사를 보내면 여기에 표시돼요."
                    : tab === "OUTGOING"
                      ? "새 대화에서 닉네임을 찾아 첫 인사를 보내 보세요."
                      : "새 대화를 요청하거나 받은 요청을 수락해 보세요."}
                </p>
                <button
                  className="button secondary"
                  onClick={() => setWrite(true)}
                >
                  새 대화 요청하기
                </button>
              </div>
            ) : (
              <ul>
                {listed.map((room) => (
                  <li key={room.id}>
                    <button
                      className={
                        "conversation-row" + (room.id === id ? " active" : "")
                      }
                      data-room-id={room.id}
                      aria-current={room.id === id ? "true" : undefined}
                      onClick={() => select(room)}
                    >
                      <span className="recipient-avatar" aria-hidden="true">
                        {room.tripId ? (
                          <MessageCircle size={20} />
                        ) : (
                          roomTitle(room, member).slice(0, 1)
                        )}
                      </span>
                      <span className="conversation-row-copy">
                        <strong>{roomTitle(room, member)}</strong>
                        {!room.tripId && (
                          <small>회원 {otherMemberCode(room, member)}</small>
                        )}
                        <span>
                          {room.status === "PENDING"
                            ? tab === "INCOMING"
                              ? "첫 인사가 도착했어요"
                              : "상대방의 수락을 기다리고 있어요"
                            : room.status === "CLOSED"
                              ? "종료된 대화 · 기록 보기"
                              : room.tripId
                                ? "여행 단체 대화"
                                : "개인 대화"}
                        </span>
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </aside>
        {id ? (
          <ConversationPanel
            key={id}
            id={id}
            draft={drafts[id] || ""}
            updateDraft={updateDraft}
            requestIdFor={requestIdFor}
            acknowledge={acknowledge}
            greetings={greetings[id] || []}
            onUseGreeting={useGreeting}
            onDiscardGreeting={discardGreeting}
            onBack={() => setParams({})}
            onChanged={changed}
            onAccessEnded={accessEnded}
          />
        ) : (
          <section className="conversation-placeholder">
            <MessageCircle size={42} aria-hidden="true" />
            <h2>이야기를 시작해 볼까요?</h2>
            <p>
              목록에서 대화를 선택하거나
              <br />
              새로운 회원에게 첫 인사를 보내세요.
            </p>
          </section>
        )}
      </div>
      {write && (
        <RecipientRequestModal
          initialRecipient={recipient}
          onClose={close}
          onRequested={requested}
        />
      )}
    </div>
  );
}
