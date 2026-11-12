import { useCallback, useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Search, X } from "lucide-react";
import { api } from "../../shared/api";
import type { Room } from "../../shared/types";
import { Modal, ErrorState, Loading } from "../../shared/ui";
import { useToast } from "../../shared/providers";
import type { Recipient, RecipientPage } from "./messageState";

/** 이메일을 알 필요 없이 닉네임 검색 결과에서 상대를 직접 선택합니다.
 * 주소의 name은 신뢰하지 않고, 커뮤니티에서 온 recipient도 서버에서 현재 수신 가능 여부를 재조회합니다. */
export default function RecipientRequestModal({
  initialRecipient,
  onClose,
  onRequested,
}: {
  initialRecipient: string;
  onClose: () => void;
  onRequested: (room: Room, greeting: string) => void;
}) {
  const client = useQueryClient();
  const toast = useToast();
  const [direct, setDirect] = useState(Boolean(initialRecipient));
  const [query, setQuery] = useState("");
  const [debounced, setDebounced] = useState("");
  const [chosen, setChosen] = useState<Recipient | null>(null);
  const [first, setFirst] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const sending = useRef(false);
  const mounted = useRef(false);
  const searchRef = useRef<HTMLInputElement>(null);
  const firstRef = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(query.trim()), 300);
    return () => clearTimeout(timer);
  }, [query]);
  const lookup = useQuery({
    queryKey: ["message-recipient", initialRecipient],
    queryFn: () =>
      api<Recipient>(
        "/messages/recipients/" + encodeURIComponent(initialRecipient),
      ),
    enabled: direct && Boolean(initialRecipient),
    retry: false,
    staleTime: 0,
    gcTime: 0,
  });
  const selected = chosen || (direct ? lookup.data : undefined);
  const validQuery = debounced.length >= 2 && debounced.length <= 30;
  const search = useQuery({
    queryKey: ["message-recipient-search", debounced],
    queryFn: () =>
      api<RecipientPage>(
        "/messages/recipients?query=" + encodeURIComponent(debounced),
      ),
    enabled: !selected && !direct && validQuery,
    retry: false,
    staleTime: 0,
    gcTime: 0,
  });
  const select = (recipient: Recipient) => {
    setChosen(recipient);
    setError(undefined);
    requestAnimationFrame(() => firstRef.current?.focus());
  };
  const clear = () => {
    setChosen(null);
    setDirect(false);
    setError(undefined);
    requestAnimationFrame(() => searchRef.current?.focus());
  };
  const close = useCallback(() => onClose(), [onClose]);
  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (sending.current || !selected || !first.trim()) return;
    sending.current = true;
    setBusy(true);
    setError(undefined);
    try {
      const room = await api<Room>("/messages", {
        method: "POST",
        body: { recipientId: selected.id, body: first },
      });
      await client.invalidateQueries({ queryKey: ["rooms"] });
      if (!mounted.current) return;
      onRequested(room, first);
      toast(
        room.requestCreated === false
          ? "기존 대화를 열었어요. 첫 인사는 아직 전송되지 않아 초안으로 보관했어요."
          : "대화 요청을 보냈어요. 수락 후 이야기할 수 있어요.",
      );
    } catch (failure) {
      if (mounted.current) setError(failure);
    } finally {
      sending.current = false;
      if (mounted.current) setBusy(false);
    }
  }
  return (
    <Modal
      title="새로운 대화 요청"
      onClose={close}
      className="message-request-dialog"
    >
      <p className="field-help">
        닉네임으로 회원을 찾고 첫 인사를 보내세요. 상대방이 수락해야 대화를
        이어갈 수 있어요.
      </p>
      <form className="recipient-request-form" onSubmit={submit}>
        {selected ? (
          <div className="selected-recipient" role="status">
            <span className="recipient-avatar" aria-hidden="true">
              {selected.nickname.slice(0, 1)}
            </span>
            <div>
              <strong>{selected.nickname}</strong>
              <span>회원 {selected.id.replaceAll("-", "").slice(0, 8)}</span>
            </div>
            <button
              className="icon-button"
              type="button"
              onClick={clear}
              disabled={busy}
              aria-label="다른 회원 찾기"
            >
              <X size={18} />
            </button>
          </div>
        ) : direct ? (
          <div className="recipient-lookup">
            {lookup.isPending ? (
              <Loading text="상대 회원을 확인하고 있어요" />
            ) : (
              <>
                <ErrorState
                  error={
                    lookup.error ||
                    new Error("현재 대화를 요청할 수 없는 회원이에요.")
                  }
                  retry={() => void lookup.refetch()}
                />
                <button
                  className="button secondary"
                  type="button"
                  onClick={clear}
                >
                  다른 회원 찾기
                </button>
              </>
            )}
          </div>
        ) : (
          <section className="recipient-search" aria-label="대화 상대 찾기">
            <label htmlFor="recipient-query">회원 닉네임</label>
            <div className="recipient-search-field">
              <Search size={18} aria-hidden="true" />
              <input
                id="recipient-query"
                ref={searchRef}
                type="search"
                value={query}
                onChange={(event) => {
                  setQuery(event.target.value);
                  setError(undefined);
                }}
                maxLength={30}
                autoComplete="off"
                autoFocus
                placeholder="닉네임을 두 글자 이상 입력하세요"
                aria-describedby="recipient-search-help"
              />
            </div>
            <p id="recipient-search-help" className="field-help">
              닉네임 앞부분으로 검색해요. 요청을 받지 않거나 서로 차단한 회원은
              결과에 표시하지 않아요.
            </p>
            {query.trim().length < 2 ? (
              <p className="recipient-search-state">
                두 글자 이상 입력하면 회원을 찾을 수 있어요.
              </p>
            ) : query.trim() !== debounced || search.isPending ? (
              <Loading text="회원을 찾고 있어요" />
            ) : search.error ? (
              <ErrorState
                error={search.error}
                retry={() => void search.refetch()}
              />
            ) : (
              search.data && (
                <>
                  {!search.data.items.length ? (
                    <p className="recipient-search-state" role="status">
                      검색 결과가 없어요. 닉네임을 확인하거나 커뮤니티 작성자의
                      대화 버튼을 이용해 보세요.
                    </p>
                  ) : (
                    <ul
                      className="recipient-results"
                      aria-label="회원 검색 결과"
                    >
                      {search.data.items.map((recipient) => (
                        <li key={recipient.id}>
                          <button
                            type="button"
                            onClick={() => select(recipient)}
                          >
                            <span
                              className="recipient-avatar"
                              aria-hidden="true"
                            >
                              {recipient.nickname.slice(0, 1)}
                            </span>
                            <span>
                              <strong>{recipient.nickname}</strong>
                              <small>
                                회원{" "}
                                {recipient.id.replaceAll("-", "").slice(0, 8)}
                              </small>
                            </span>
                            <Check size={18} aria-hidden="true" />
                          </button>
                        </li>
                      ))}
                    </ul>
                  )}
                  {search.data.hasMore && (
                    <p className="field-help">
                      같은 닉네임이 많아요. 앞부분을 더 길게 입력해 주세요.
                    </p>
                  )}
                </>
              )
            )}
          </section>
        )}
        <label htmlFor="recipient-first-message">첫 인사</label>
        <textarea
          id="recipient-first-message"
          ref={firstRef}
          rows={3}
          value={first}
          onChange={(event) => setFirst(event.target.value)}
          disabled={busy}
          maxLength={2000}
          required
          placeholder="안녕하세요! 여행 스타일이 잘 맞을 것 같아 연락드려요."
        />
        {Boolean(error) && <ErrorState error={error} />}
        <p className="field-help">
          선택한 회원의 현재 수신 설정을 보내는 시점에 다시 확인해요.
        </p>
        <button
          className="button primary full"
          type="submit"
          disabled={busy || !selected || !first.trim()}
        >
          {busy ? "요청을 보내고 있어요…" : "대화 요청 보내기"}
        </button>
      </form>
    </Modal>
  );
}
