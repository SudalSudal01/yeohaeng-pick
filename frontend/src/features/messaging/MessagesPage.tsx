import { useCallback, useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  MessageCircle,
  Plus,
  Send,
  ShieldBan,
  Check,
  Flag,
} from "lucide-react";
import { api, accessEnded } from "../../shared/api";
import type { Room } from "../../shared/types";
import { useAuth, useToast } from "../../shared/providers";
import { Empty, Loading, ErrorState, Modal } from "../../shared/ui";

/** 요청함에서 수락된 대화만 일반 대화가 됩니다. 대화방 선택은 URL에 남겨 새로고침에도 유지합니다. */
export default function MessagesPage() {
  const [params, setParams] = useSearchParams();
  const { user } = useAuth();
  const toast = useToast();
  const client = useQueryClient();
  const id = params.get("room") || "";
  const [write, setWrite] = useState(!!params.get("recipient"));
  const [email, setEmail] = useState("");
  const [first, setFirst] = useState("");
  const [body, setBody] = useState("");
  const [requestTab, setRequestTab] = useState(false);
  const [report, setReport] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const close = useCallback(() => {
    setWrite(false);
    setParams((p) => {
      p.delete("recipient");
      p.delete("name");
      return p;
    });
  }, [setParams]);
  const closeReport = useCallback(() => setReport(null), []);
  const rooms = useQuery({
    queryKey: ["rooms"],
    queryFn: () => api<Room[]>("/messages"),
    refetchInterval: 10000,
  });
  const room = useQuery({
    queryKey: ["room", id],
    queryFn: () => api<Room>("/messages/" + id),
    enabled: !!id,
    refetchInterval: (query) => (accessEnded(query.state.error) ? false : 5000),
    retry: false,
    gcTime: 0,
  });
  const listed = rooms.data?.filter(
    (r) =>
      !(r.id === id && accessEnded(room.error)) &&
      (requestTab ? r.status === "PENDING" : r.status !== "DECLINED"),
  );
  const current = room.data;
  const ended = accessEnded(room.error);
  useEffect(() => {
    if (!ended) return;
    // 접근이 끝난 단체 대화의 작성 중 문구·신고 양식을 다른 방으로 넘기지 않습니다.
    setBody("");
    setReport(null);
    void client.invalidateQueries({ queryKey: ["rooms"] });
  }, [ended, id, client]);
  async function refresh() {
    await client.invalidateQueries({ queryKey: ["rooms"] });
    await client.invalidateQueries({ queryKey: ["room", id] });
  }
  async function request() {
    setBusy(true);
    try {
      const r = await api<Room>("/messages", {
        method: "POST",
        body: { email, recipientId: params.get("recipient"), body: first },
      });
      close();
      setParams({ room: r.id });
      await client.invalidateQueries({ queryKey: ["rooms"] });
      toast("대화 요청을 보냈어요. 수락 후 자유롭게 이야기할 수 있어요.");
    } catch (e) {
      toast((e as Error).message, true);
    } finally {
      setBusy(false);
    }
  }
  async function send(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      await api("/messages/" + id, { method: "POST", body: { body } });
      setBody("");
      await refresh();
    } catch (e) {
      toast((e as Error).message, true);
      // 전송 직전 참여가 종료되면 이전 메시지 대신 권한 종료 화면을 다시 확인합니다.
      if (accessEnded(e)) await refresh();
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="page">
      <div className="page-heading heading-row">
        <div>
          <span className="eyebrow">BETTER TOGETHER</span>
          <h1>여행을 잇는 대화</h1>
          <p>처음 연락은 요청함으로, 수락한 뒤에는 편하게 이야기해요.</p>
        </div>
        <button className="button primary" onClick={() => setWrite(true)}>
          <Plus size={17} />새 대화
        </button>
      </div>
      <div className="messages-layout">
        <aside className="rooms-panel">
          <div className="room-tabs">
            <button
              className={!requestTab ? "active" : ""}
              onClick={() => setRequestTab(false)}
            >
              대화
            </button>
            <button
              className={requestTab ? "active" : ""}
              onClick={() => setRequestTab(true)}
            >
              요청함{" "}
              <span>
                {rooms.data?.filter(
                  (r) => r.status === "PENDING" && r.recipientId === user?.id,
                ).length || 0}
              </span>
            </button>
          </div>
          {rooms.isPending ? (
            <Loading />
          ) : rooms.error ? (
            <ErrorState error={rooms.error} />
          ) : !listed?.length ? (
            <div className="rooms-empty">
              <MessageCircle size={28} />
              <p>아직 도착한 대화가 없어요.</p>
            </div>
          ) : (
            listed.map((r) => (
              <button
                className={"room-row " + (id === r.id ? "active" : "")}
                key={r.id}
                onClick={() => setParams({ room: r.id })}
              >
                <span className="avatar">
                  {r.tripId ? (
                    <MessageCircle size={20} />
                  ) : (
                    (
                      (r.requesterId === user?.id
                        ? r.recipientName
                        : r.requesterName) || "여행"
                    ).slice(0, 1)
                  )}
                </span>
                <div>
                  <strong>
                    {r.tripId
                      ? "함께하는 여행"
                      : r.requesterId === user?.id
                        ? r.recipientName
                        : r.requesterName}
                  </strong>
                  <span>
                    {r.status === "PENDING"
                      ? "대화 요청 · 수락 대기"
                      : "일반 대화"}
                  </span>
                </div>
              </button>
            ))
          )}
        </aside>
        <section className="chat-panel">
          {!id ? (
            <Empty
              title="이야기를 시작해 볼까요?"
              text="대화를 선택하거나 새로운 동행에게 대화를 요청해 보세요."
            />
          ) : room.isPending ? (
            <Loading />
          ) : room.error ? (
            accessEnded(room.error) ? (
              <div className="access-ended" role="status">
                <h2>이 대화에 접근할 수 없어요</h2>
                <p>
                  여행 참여가 종료됐거나 대화방이 더 이상 존재하지 않아요. 여행
                  단체 대화는 현재 참여 중인 동행만 이용할 수 있어요.
                </p>
                <Link className="button secondary" to="/trips">
                  내 여행 확인하기
                </Link>
                <button
                  className="text-button"
                  onClick={() => {
                    setParams({});
                    void client.invalidateQueries({ queryKey: ["rooms"] });
                  }}
                >
                  다른 대화 보기
                </button>
              </div>
            ) : (
              <ErrorState error={room.error} />
            )
          ) : (
            current && (
              <>
                <div className="chat-header">
                  <strong>
                    {current.tripId ? "여행 단체 대화" : "우리의 여행 이야기"}
                  </strong>
                  {!current.tripId && (
                    <button
                      className="text-button danger-text"
                      onClick={async () => {
                        try {
                          const other =
                            current.requesterId === user?.id
                              ? current.recipientId
                              : current.requesterId;
                          await api("/messages/blocks/" + other, {
                            method: "PUT",
                            body: { enabled: true },
                          });
                          toast("해당 사용자의 메시지와 초대를 차단했어요.");
                          await refresh();
                        } catch (e) {
                          toast((e as Error).message, true);
                        }
                      }}
                    >
                      <ShieldBan size={16} />
                      사용자 차단
                    </button>
                  )}
                </div>
                {current.status === "PENDING" && (
                  <div className="request-notice">
                    <p>
                      {current.recipientId === user?.id
                        ? "새로운 대화 요청이에요. 수락할지 선택해 주세요."
                        : "상대방의 수락을 기다리고 있어요."}
                    </p>
                    {current.recipientId === user?.id && (
                      <div className="row">
                        <button
                          className="button secondary small-button"
                          onClick={async () => {
                            await api("/messages/" + id + "/respond", {
                              method: "POST",
                              body: { accept: false },
                            });
                            await refresh();
                          }}
                        >
                          거절
                        </button>
                        <button
                          className="button primary small-button"
                          onClick={async () => {
                            await api("/messages/" + id + "/respond", {
                              method: "POST",
                              body: { accept: true },
                            });
                            await refresh();
                          }}
                        >
                          <Check size={15} />
                          수락
                        </button>
                      </div>
                    )}
                  </div>
                )}
                <div className="chat-messages" aria-live="polite">
                  {current.messages?.map((m) => (
                    <div
                      className={
                        "message " + (m.senderId === user?.id ? "own" : "")
                      }
                      key={m.id}
                    >
                      <span className="message-author">{m.nickname}</span>
                      <div className="message-bubble">{m.body}</div>
                      <div className="message-foot">
                        <time>
                          {new Date(m.createdAt).toLocaleTimeString("ko-KR", {
                            hour: "2-digit",
                            minute: "2-digit",
                          })}
                        </time>
                        {m.senderId !== user?.id && (
                          <button
                            className="icon-button"
                            aria-label="메시지 신고"
                            onClick={() => setReport(m.id)}
                          >
                            <Flag size={12} />
                          </button>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
                <form className="message-composer" onSubmit={send}>
                  <input
                    aria-label="메시지"
                    value={body}
                    onChange={(e) => setBody(e.target.value)}
                    placeholder={
                      current.status === "ACTIVE"
                        ? "여행 이야기를 나눠보세요"
                        : "수락 후 메시지를 보낼 수 있어요"
                    }
                    disabled={current.status !== "ACTIVE"}
                    maxLength={2000}
                    required
                  />
                  <button
                    className="button primary"
                    disabled={busy || current.status !== "ACTIVE"}
                    aria-label="메시지 보내기"
                  >
                    <Send size={18} />
                  </button>
                </form>
              </>
            )
          )}
        </section>
      </div>
      {write && (
        <Modal title="새로운 대화 요청" onClose={close}>
          <p className="muted">
            {params.get("name")
              ? params.get("name") + "님에게"
              : "연락할 회원에게"}{" "}
            첫 메시지를 보내 주세요. 상대방이 수락하면 대화를 이어갈 수 있어요.
          </p>
          {!params.get("recipient") && (
            <label>
              회원 이메일
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="friend@example.com"
              />
            </label>
          )}
          <label>
            첫 인사
            <textarea
              rows={4}
              value={first}
              onChange={(e) => setFirst(e.target.value)}
              maxLength={2000}
              placeholder="안녕하세요! 여행 스타일이 잘 맞을 것 같아 연락드려요."
            />
          </label>
          <button
            className="button primary full"
            disabled={
              busy ||
              !first.trim() ||
              (!params.get("recipient") && !email.includes("@"))
            }
            onClick={request}
          >
            대화 요청 보내기
          </button>
        </Modal>
      )}
      {report && (
        <Modal title="메시지 신고" onClose={closeReport}>
          <p className="muted">신고한 메시지는 운영자가 확인할 수 있어요.</p>
          <label>
            신고 사유
            <textarea
              rows={4}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              maxLength={1000}
            />
          </label>
          <button
            className="button primary full"
            disabled={!reason.trim()}
            onClick={async () => {
              try {
                await api("/reports", {
                  method: "POST",
                  body: { type: "MESSAGE", targetId: report, reason },
                });
                toast("신고를 접수했어요.");
                setReport(null);
                setReason("");
              } catch (e) {
                toast((e as Error).message, true);
              }
            }}
          >
            신고 접수
          </button>
        </Modal>
      )}
    </div>
  );
}
