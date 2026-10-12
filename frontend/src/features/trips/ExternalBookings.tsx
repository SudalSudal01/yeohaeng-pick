import { useCallback, useState, type FormEvent } from "react";
import { Plus, TrainFront, Loader2 } from "lucide-react";
import { Modal } from "../../shared/ui";
import { api, bookingLabels, money } from "../../shared/api";
import { useToast } from "../../shared/providers";
import type { Trip, ExternalBooking } from "../../shared/types";
import { scheduleTime } from "./bookingScheduleTime";

const categories: Record<string, string> = {
  TRAIN: "열차",
  BUS: "버스",
  FLIGHT: "항공",
  CAR: "렌터카",
  OTHER: "기타",
};
/** 실시간 예약 재고와 직접 기록한 결과를 구분합니다. 결제 증빙·예약 번호는 이 폼에서 받지 않습니다. */
export default function ExternalBookings({
  trip,
  refresh,
}: {
  trip: Trip;
  refresh: () => Promise<void>;
}) {
  const [editing, setEditing] = useState<ExternalBooking | null>();
  const close = useCallback(() => setEditing(undefined), []);
  return (
    <div className="external-bookings">
      <div className="section-heading">
        <div>
          <h3>교통편·외부 예매 기록</h3>
          <p className="small muted">매진·결제 실패·취소까지 함께 확인해요.</p>
        </div>
        <button
          className="button secondary small-button"
          onClick={() => setEditing(null)}
        >
          <Plus size={15} />
          기록 추가
        </button>
      </div>
      {trip.externalBookings?.map((b) => (
        <div className="booking-row" key={b.id}>
          <TrainFront size={20} />
          <div>
            <strong>{b.label}</strong>
            <p>
              {categories[b.category]} · {scheduleTime(b.departure)}
              {b.arrival
                ? ` → ${scheduleTime(b.arrival)}`
                : " · 도착 미입력"} ·{" "}
              {b.amount == null ? "요금 미입력" : money(b.amount)}
            </p>
            <span className="badge subtle">{bookingLabels[b.status]}</span>
            <span className="badge subtle">
              {b.status === "USER_CONFIRMED" && b.scheduleLinked && b.arrival
                ? "일정 시간 보호 중"
                : "일정 시간 미연결"}
            </span>
            {b.reason && <p>{b.reason}</p>}
          </div>
          <button className="text-button" onClick={() => setEditing(b)}>
            수정
          </button>
        </div>
      ))}
      {!trip.externalBookings?.length && (
        <p className="muted small">
          예매 후 결과를 기록해 주세요. 외부 사이트 방문만으로 완료 처리하지
          않아요.
        </p>
      )}
      <p className="small muted">
        예약 완료로 직접 확인하고 일정에 연결한 교통편만 시간을 보호해요.
        매진·실패·취소 기록은 일정을 막지 않아요. 렌터카 대여 기간 전체를 이동
        시간으로 연결하지 않도록 확인해 주세요.
      </p>
      {editing !== undefined && (
        <Editor
          trip={trip}
          booking={editing}
          close={close}
          saved={async () => {
            close();
            await refresh();
          }}
        />
      )}
    </div>
  );
}
function Editor({
  trip,
  booking,
  close,
  saved,
}: {
  trip: Trip;
  booking: ExternalBooking | null;
  close: () => void;
  saved: () => Promise<void>;
}) {
  const [label, setLabel] = useState(booking?.label || "");
  const [category, setCategory] = useState(booking?.category || "TRAIN");
  const [departure, setDeparture] = useState(
    booking?.departure || String(trip.startDate).slice(0, 10) + "T09:00",
  );
  const [arrival, setArrival] = useState(booking?.arrival || "");
  const [departurePlace, setDeparturePlace] = useState(
    booking?.departurePlace || "",
  );
  const [arrivalPlace, setArrivalPlace] = useState(booking?.arrivalPlace || "");
  const [beforeMinutes, setBeforeMinutes] = useState(
    String(booking?.beforeMinutes ?? 30),
  );
  const [afterMinutes, setAfterMinutes] = useState(
    String(booking?.afterMinutes ?? 30),
  );
  const [scheduleLinked, setScheduleLinked] = useState(
    booking?.scheduleLinked ?? true,
  );
  const [amount, setAmount] = useState(booking?.amount?.toString() || "");
  const [status, setStatus] = useState(booking?.status || "NEEDS_CONFIRMATION");
  const [reason, setReason] = useState(booking?.reason || "");
  const [busy, setBusy] = useState(false);
  const toast = useToast();
  async function save(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      await api("/trips/" + trip.id + "/external-bookings", {
        method: "POST",
        body: {
          id: booking?.id,
          label,
          category,
          departure,
          arrival: arrival || null,
          departurePlace,
          arrivalPlace,
          beforeMinutes: Number(beforeMinutes),
          afterMinutes: Number(afterMinutes),
          scheduleLinked,
          amount: amount === "" ? null : Number(amount),
          status,
          reason,
          version: trip.version,
        },
      });
      await saved();
      toast("외부 예매 결과를 저장했어요.");
    } catch (e) {
      toast((e as Error).message, true);
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal title="외부 예매 결과 기록" onClose={close}>
      <form onSubmit={save}>
        <label>
          예매 내용
          <input
            value={label}
            maxLength={100}
            placeholder="예: 서울 → 부산 KTX"
            onChange={(e) => setLabel(e.target.value)}
            required
          />
        </label>
        <div className="form-grid">
          <label>
            종류
            <select
              value={category}
              onChange={(e) => {
                setCategory(e.target.value);
                if (!booking) setScheduleLinked(e.target.value !== "CAR");
              }}
            >
              {Object.entries(categories).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </select>
          </label>
          <label>
            출발 일시
            <input
              type="datetime-local"
              value={departure}
              min={trip.startDate.slice(0, 10) + "T00:00"}
              max={trip.endDate.slice(0, 10) + "T23:59"}
              onInput={(e) => setDeparture(e.currentTarget.value)}
              onChange={(e) => setDeparture(e.target.value)}
              required
            />
          </label>
        </div>
        <label>
          도착 일시
          {scheduleLinked && status === "USER_CONFIRMED"
            ? " · 필수"
            : " · 선택"}
          <input
            type="datetime-local"
            value={arrival}
            min={departure}
            max={trip.endDate.slice(0, 10) + "T23:59"}
            onInput={(e) => setArrival(e.currentTarget.value)}
            onChange={(e) => setArrival(e.target.value)}
            required={scheduleLinked && status === "USER_CONFIRMED"}
          />
        </label>
        <p className="small muted">
          한국 현지 시각으로 입력해 주세요. 자정을 넘는 이동은 도착 날짜도 바꿔
          주세요.
        </p>
        <div className="form-grid">
          <label>
            출발 역·터미널 · 선택
            <input
              value={departurePlace}
              onChange={(e) => setDeparturePlace(e.target.value)}
              maxLength={100}
              placeholder="예: 서울역"
            />
          </label>
          <label>
            도착 역·터미널 · 선택
            <input
              value={arrivalPlace}
              onChange={(e) => setArrivalPlace(e.target.value)}
              maxLength={100}
              placeholder="예: 부산역"
            />
          </label>
        </div>
        <label>
          전체 결제 금액 · 선택
          <input
            type="number"
            min={0}
            max={100000000}
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            placeholder="모르면 비워 주세요"
          />
        </label>
        <label>
          확인한 결과
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
            maxLength={150}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="예: 원하는 시간대 좌석이 매진됐어요"
          />
        </label>
        <fieldset className="booking-schedule-settings">
          <legend>여행 일정과 연결</legend>
          <label className="task-row">
            <input
              type="checkbox"
              checked={scheduleLinked}
              onChange={(e) => setScheduleLinked(e.target.checked)}
            />
            <span>이동·여유 시간에 방문 일정을 넣지 않기</span>
          </label>
          <p className="small muted">
            예약 완료로 직접 확인한 기록에만 적용해요. 렌터카 대여 기록처럼 전체
            시간을 비울 필요가 없다면 꺼 주세요.
          </p>
          {scheduleLinked && (
            <div className="form-grid">
              <label>
                출발 전 이동·탑승 여유 (분)
                <input
                  type="number"
                  min={0}
                  max={360}
                  value={beforeMinutes}
                  onChange={(e) => setBeforeMinutes(e.target.value)}
                  required
                />
              </label>
              <label>
                도착 후 이동 여유 (분)
                <input
                  type="number"
                  min={0}
                  max={360}
                  value={afterMinutes}
                  onChange={(e) => setAfterMinutes(e.target.value)}
                  required
                />
              </label>
            </div>
          )}
          <p className="small muted">
            기본 30분은 추천값이 아닌 입력 초기값이에요. 이동 경로 비교에서
            역·터미널까지 걸리는 시간을 확인하고 탑승 여유를 더해 수정해 주세요.
          </p>
        </fieldset>
        <button className="button primary full" disabled={busy}>
          {busy && <Loader2 className="spin" size={17} />}결과 저장하기
        </button>
      </form>
    </Modal>
  );
}
