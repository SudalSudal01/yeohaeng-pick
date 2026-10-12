import { useCallback, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { ArrowRightLeft, Loader2 } from "lucide-react";
import { api, ApiError } from "../../shared/api";
import type { Trip } from "../../shared/types";
import { useToast } from "../../shared/providers";
import { ErrorState, Modal } from "../../shared/ui";

/** 소유권 이전은 참여 해제와 별개의 작업입니다. 확정 전에 대상·현재 버전·관리 권한 변화를 한 화면에서 확인합니다. */
export default function TripOwnershipPanel({
  trip,
  userId,
  refresh,
}: {
  trip: Trip;
  userId: string;
  refresh: () => Promise<void>;
}) {
  const owner = trip.ownerId === userId;
  const candidates = trip.members.filter((m) => m.id !== trip.ownerId);
  const [opened, setOpened] = useState<{
    version: number;
    members: Trip["members"];
  } | null>(null);
  const [target, setTarget] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [conflicted, setConflicted] = useState(false);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const client = useQueryClient();
  const toast = useToast();
  const close = useCallback(() => {
    if (!busyRef.current) setOpened(null);
  }, []);
  const recipient = opened?.members.find((m) => m.id === target);
  const stale =
    !!opened &&
    (!owner ||
      trip.version !== opened.version ||
      conflicted ||
      (!!target && !trip.members.some((m) => m.id === target)));
  function open() {
    setOpened({ version: trip.version, members: candidates });
    setTarget("");
    setConfirmed(false);
    setError(null);
    setConflicted(false);
  }
  async function transfer(e: React.FormEvent) {
    e.preventDefault();
    if (!opened || !recipient || !confirmed || stale || busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      const saved = await api<Trip>(`/trips/${trip.id}/ownership/transfer`, {
        method: "POST",
        body: { version: opened.version, memberId: recipient.id },
      });
      // 서버가 확정한 소유자를 즉시 반영합니다. 이전 소유자 도구가 다음 폴링까지 남지 않게 합니다.
      client.setQueryData(["trip", trip.id], saved);
      setOpened(null);
      await refresh();
      toast(
        `${recipient.nickname}님에게 소유권을 넘겼어요. 나는 동행으로 계속 참여해요.`,
      );
    } catch (e) {
      setError(e as Error);
      if (e instanceof ApiError && [403, 404, 409].includes(e.status)) {
        setConflicted(true);
        await refresh();
      }
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }
  return (
    <section className="trip-ownership-panel" aria-label="여행 소유권 관리">
      <h3>여행 소유권</h3>
      <p className="field-help">
        {owner
          ? "소유자는 동행 초대·참여 해제·공개 링크·여행 삭제를 관리해요. 이미 참여 중인 동행에게 소유권을 넘길 수 있어요."
          : `${trip.members.find((m) => m.id === trip.ownerId)?.nickname ?? "현재 소유자"}님이 여행을 관리해요. 나는 동행으로 일정과 준비물을 함께 편집할 수 있어요.`}
      </p>
      {owner &&
        (candidates.length ? (
          <button className="button secondary" onClick={open}>
            <ArrowRightLeft size={17} />
            소유권 넘기기
          </button>
        ) : (
          <p className="ownership-empty">
            초대를 수락한 동행이 생기면 소유권을 넘길 수 있어요.
          </p>
        ))}
      {!!trip.ownershipHistory?.length && (
        <details className="ownership-history">
          <summary>
            소유권 변경 기록{" "}
            <span className="badge subtle">
              최근 {trip.ownershipHistory.length}건
            </span>
          </summary>
          <ol>
            {trip.ownershipHistory.map((h) => (
              <li key={h.id}>
                <strong>
                  {h.previousOwnerName} → {h.newOwnerName}
                </strong>
                <time dateTime={h.createdAt}>
                  {new Date(h.createdAt).toLocaleString("ko-KR")}
                </time>
                <p>
                  여행 버전 {h.version} · 대기 초대 {h.revokedInvitations}건
                  종료
                </p>
              </li>
            ))}
          </ol>
          <p className="field-help">
            최근 20건을 보여줘요. 방문 일정 복원은 소유권을 되돌리지 않아요.
          </p>
        </details>
      )}
      {opened && (
        <Modal
          title="여행 소유권을 넘길까요?"
          onClose={close}
          className="membership-modal ownership-modal"
        >
          <div className="membership-target">
            <span className="badge">소유자 변경</span>
            <h3>{trip.title}</h3>
            <p>확정하면 선택한 동행이 즉시 새 소유자가 돼요.</p>
          </div>
          <form onSubmit={transfer}>
            <label>
              새 소유자
              <select
                value={target}
                onChange={(e) => {
                  setTarget(e.target.value);
                  setConfirmed(false);
                }}
                required
                disabled={busy || stale}
                aria-describedby="ownership-select-help"
              >
                <option value="">동행을 선택해요</option>
                {opened.members.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.nickname}
                  </option>
                ))}
              </select>
            </label>
            <p id="ownership-select-help" className="field-help">
              {recipient && (
                <strong className="ownership-recipient">
                  선택한 동행: {recipient.nickname}
                </strong>
              )}
              이미 초대를 수락한 현재 참여자만 선택할 수 있어요.
            </p>
            <ul className="membership-effects ownership-effects">
              <li>
                새 소유자가 동행 초대·참여 해제·공개 링크·여행 삭제를 관리해요.
              </li>
              <li>
                나는 일반 동행으로 남아 일정·예약 기록·준비물·경비·단체 대화를
                계속 이용해요. 새 소유자는 내 참여를 해제할 수 있어요.
              </li>
              <li>
                대기 중인 초대는 모두 종료돼요. 필요한 초대는 새 소유자가 다시
                보내야 해요.
              </li>
              <li>
                기존 일정·예약·결제 기록·대화·공개 링크는 유지돼요. 외부
                예약·결제의 명의나 계약을 이전하는 기능은 아니에요.
              </li>
              <li>
                내가 혼자 취소하거나 일정 복원으로 되돌릴 수 없어요. 다시
                소유자가 되려면 현재 소유자가 넘겨줘야 해요.
              </li>
            </ul>
            <label className="ownership-confirm">
              <input
                type="checkbox"
                checked={confirmed}
                onChange={(e) => setConfirmed(e.target.checked)}
                disabled={busy || stale || !recipient}
                required
              />
              <span>
                {recipient
                  ? `${recipient.nickname}님에게 관리 권한을 넘기고 동행으로 남는 것을 확인했어요.`
                  : "동행을 선택한 뒤 변경되는 권한을 확인해 주세요."}
              </span>
            </label>
            {error && <ErrorState error={error} />}
            {stale && (
              <div className="notice danger" role="status">
                여행이나 소유자가 변경됐어요. 최신 정보를 확인한 뒤 대상을 다시
                선택해 주세요.
              </div>
            )}
            <div className="membership-actions">
              {stale ? (
                <button
                  type="button"
                  className="button secondary"
                  disabled={busy}
                  onClick={async () => {
                    await refresh();
                    close();
                  }}
                >
                  최신 참여자 확인하기
                </button>
              ) : (
                <button
                  className="button primary"
                  disabled={busy || !recipient || !confirmed}
                >
                  {busy ? (
                    <Loader2 size={17} className="spin" />
                  ) : (
                    <ArrowRightLeft size={17} />
                  )}
                  확인 후 소유권 넘기기
                </button>
              )}
              <button
                type="button"
                className="button secondary"
                onClick={close}
                disabled={busy}
              >
                취소
              </button>
            </div>
          </form>
        </Modal>
      )}
    </section>
  );
}
