import { useState } from "react";
import { ArrowUpRight } from "lucide-react";
import { bookingLabels } from "../../shared/api";
import type { Trip, Item } from "../../shared/types";
import { Modal } from "../../shared/ui";

/** 예약 상태·고정 여부를 한 번에 저장해 일정의 예약 보호 규칙을 명확하게 유지합니다. */
export function BookingModal({
  trip,
  item,
  close,
  save,
}: {
  trip: Trip;
  item: Item;
  close: () => void;
  save: (body: unknown) => Promise<void>;
}) {
  const [status, setStatus] = useState(item.bookingStatus);
  const [reason, setReason] = useState(item.bookingReason);
  const [fixed, setFixed] = useState(item.fixed);
  const [busy, setBusy] = useState(false);
  return (
    <Modal title={item.placeName + " 예약 관리"} onClose={close}>
      <a
        className="button secondary full"
        href={item.placeUrl}
        target="_blank"
        rel="noreferrer"
      >
        공식 연결 정보·예약 안내 보기
        <ArrowUpRight size={17} />
      </a>
      <p className="small muted">
        예약 서비스가 연결된 경우 해당 사이트에서 예매해 주세요. 장소 상세
        페이지에 예약 기능이 없을 수도 있어요.
      </p>
      <label>
        현재 예약 상태
        <select value={status} onChange={(e) => setStatus(e.target.value)}>
          {Object.entries(bookingLabels)
            .filter(([k]) => k !== "PARTNER_CONFIRMED")
            .map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
        </select>
      </label>
      <label>
        확인 메모
        <input
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          maxLength={150}
          placeholder="예: 매진으로 예매하지 못했어요"
        />
      </label>
      <label className="task-row">
        <input
          type="checkbox"
          checked={status === "USER_CONFIRMED" || fixed}
          disabled={status === "USER_CONFIRMED"}
          onChange={(e) => setFixed(e.target.checked)}
        />
        <span>이 장소와 시간을 고정하기</span>
      </label>
      <p className="small muted">
        예약 완료로 입력하면 일정이 고정돼요. 예약 실패나 취소 후 변경하려면
        고정을 해제해 주세요.
      </p>
      <button
        className="button primary full"
        disabled={busy}
        onClick={async () => {
          setBusy(true);
          await save({
            itemId: item.id,
            status,
            reason,
            fixed,
            version: trip.version,
          });
          setBusy(false);
        }}
      >
        상태 저장하기
      </button>
    </Modal>
  );
}
