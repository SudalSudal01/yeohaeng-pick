import { useCallback, useState } from "react";
import { CalendarDays, Save } from "lucide-react";
import type { Lodging, Trip } from "../../shared/types";
import { api } from "../../shared/api";
import { ErrorState, Modal } from "../../shared/ui";
import { useToast } from "../../shared/providers";
import { tripDayDate } from "./transportSchedule";

/** 숙박 조건은 직접 확인한 값만 저장합니다. 예약/결제 상태와 방문 시각을 바꾸지 않습니다. */
export default function LodgingStayModal({
  trip,
  hotel,
  refresh,
  close,
}: {
  trip: Trip;
  hotel: Lodging;
  refresh: () => Promise<void>;
  close: () => void;
}) {
  // 미정 숙소는 해당 지역에서 머물 수 있는 날짜를 초깃값으로 제안할 뿐 저장하지 않습니다.
  const lastDay = Math.round(
    (Date.parse(trip.endDate.slice(0, 10)) -
      Date.parse(trip.startDate.slice(0, 10))) /
      86400000,
  );
  const days = [
    ...new Set(
      trip.items
        .filter((i) => i.region === hotel.region && i.dayIndex < lastDay)
        .map((i) => i.dayIndex),
    ),
  ];
  const [checkInDate, setIn] = useState(
    hotel.checkInDate?.slice(0, 10) ??
      tripDayDate(trip.startDate, days[0] ?? 0),
  );
  const [checkOutDate, setOut] = useState(
    hotel.checkOutDate?.slice(0, 10) ??
      tripDayDate(trip.startDate, days.length ? days.at(-1)! + 1 : lastDay),
  );
  const [guests, setGuests] = useState(
    String(hotel.guestCount ?? trip.partySize),
  );
  const [rooms, setRooms] = useState(String(hotel.roomCount ?? 1));
  const [amount, setAmount] = useState(
    hotel.totalAmount == null ? "" : String(hotel.totalAmount),
  );
  const [inTime, setInTime] = useState(hotel.checkInTime ?? "");
  const [outTime, setOutTime] = useState(hotel.checkOutTime ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const toast = useToast();
  const onClose = useCallback(() => {
    if (!busy) close();
  }, [busy, close]);
  const nights = Math.round(
    (Date.parse(checkOutDate) - Date.parse(checkInDate)) / 86400000,
  );
  async function save(reset = false) {
    setBusy(true);
    setError(null);
    try {
      await api(
        `/trips/${trip.id}/lodgings/${encodeURIComponent(hotel.placeId)}/stay`,
        {
          method: "PUT",
          body: {
            version: trip.version,
            checkInDate: reset ? null : checkInDate,
            checkOutDate: reset ? null : checkOutDate,
            guestCount: reset ? null : Number(guests),
            roomCount: reset ? null : Number(rooms),
            totalAmount: reset || amount.trim() === "" ? null : Number(amount),
            checkInTime: reset ? null : inTime || null,
            checkOutTime: reset ? null : outTime || null,
          },
        },
      );
      await refresh();
      toast(
        reset
          ? "숙박 조건을 초기화했어요. 예약 상태는 유지돼요."
          : "숙박 조건을 저장했어요. 예산과 경로 비교에 반영돼요.",
      );
      close();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal
      title="숙박 조건 정리하기"
      onClose={onClose}
      className="lodging-stay-modal"
    >
      <p className="muted">
        {hotel.name} · 예약처에서 확인한 조건을 함께 정리해요. 입력만으로 객실이
        예약되거나 결제되지 않아요.
      </p>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (nights > 0) void save();
        }}
      >
        <div className="lodging-stay-grid">
          <label>
            체크인 날짜
            <input
              type="date"
              required
              min={trip.startDate.slice(0, 10)}
              max={tripDayDate(trip.endDate, -1)}
              value={checkInDate}
              disabled={busy}
              onChange={(e) => setIn(e.target.value)}
              onInput={(e) => setIn(e.currentTarget.value)}
            />
          </label>
          <label>
            체크아웃 날짜
            <input
              type="date"
              required
              min={
                checkInDate
                  ? tripDayDate(checkInDate, 1)
                  : trip.startDate.slice(0, 10)
              }
              max={trip.endDate.slice(0, 10)}
              value={checkOutDate}
              disabled={busy}
              onChange={(e) => setOut(e.target.value)}
              onInput={(e) => setOut(e.currentTarget.value)}
            />
          </label>
          <label>
            숙박 인원
            <input
              type="number"
              required
              min="1"
              max={trip.partySize}
              step="1"
              value={guests}
              disabled={busy}
              onChange={(e) => setGuests(e.target.value)}
            />
          </label>
          <label>
            객실 수
            <input
              type="number"
              required
              min="1"
              max="20"
              step="1"
              value={rooms}
              disabled={busy}
              onChange={(e) => setRooms(e.target.value)}
            />
          </label>
          <label>
            체크인 가능 시각 (선택)
            <input
              type="time"
              value={inTime}
              disabled={busy}
              onChange={(e) => setInTime(e.target.value)}
              onInput={(e) => setInTime(e.currentTarget.value)}
            />
          </label>
          <label>
            체크아웃 마감 시각 (선택)
            <input
              type="time"
              value={outTime}
              disabled={busy}
              onChange={(e) => setOutTime(e.target.value)}
              onInput={(e) => setOutTime(e.currentTarget.value)}
            />
          </label>
        </div>
        <p className="small muted">
          시각을 모르면 비워 두세요. 입력한 날짜의 시각을 기준으로 숙소
          도착·출발을 검사해요. 객실 정원·이른 체크인·늦은 체크아웃은 예약처에서
          확인해 주세요.
        </p>
        <label>
          총 숙박비 (원 · 선택)
          <input
            type="number"
            min="0"
            max="100000000"
            step="1"
            inputMode="numeric"
            placeholder="미확인이면 비워 두세요"
            value={amount}
            disabled={busy}
            onChange={(e) => setAmount(e.target.value)}
          />
        </label>
        <p className="small muted">
          전체 기간·전체 객실의 세금 포함 합계를 입력해 주세요. 0원과 미입력은
          구분하며, 이 금액은 경비의 결제 완료 기록에 자동 추가되지 않아요.
        </p>
        <div className="notice lodging-stay-review" role="status">
          <CalendarDays size={18} />
          <span>
            {nights > 0
              ? `${nights}박 · ${guests || "?"}명 · ${rooms || "?"}객실`
              : "체크아웃을 체크인 다음 날 이후로 선택해 주세요."}
            <br />
            예약 확인 상태와 방문 시각은 유지돼요.
          </span>
        </div>
        {!!error && <ErrorState error={error} />}
        <div className="lodging-stay-actions">
          <button className="button primary" disabled={busy || !(nights > 0)}>
            <Save size={17} />
            {busy ? "저장 중…" : "숙박 조건 저장"}
          </button>
          {hotel.checkInDate && (
            <button
              type="button"
              className="button secondary"
              disabled={busy}
              onClick={() => void save(true)}
            >
              조건 초기화
            </button>
          )}
        </div>
      </form>
    </Modal>
  );
}
