import { useCallback, useRef, useState, type FormEvent } from "react";
import { CalendarClock, Check, Loader2 } from "lucide-react";
import { api, ApiError, dateLabel, money } from "../../shared/api";
import { Modal, ErrorState } from "../../shared/ui";
import { useToast } from "../../shared/providers";
import type { Item, Trip } from "../../shared/types";

/** 원문과 별개의 사용자 확인 양식입니다. 공급자 내용을 시간/금액으로 자동 변환하거나 예약으로 표시하지 않습니다. */
export default function VisitConditionsModal({
  trip,
  item,
  close,
  refresh,
}: {
  trip: Trip;
  item: Item;
  close: () => void;
  refresh: () => Promise<void>;
}) {
  // 15초 동기화 중에도 편집 초깃값과 버전을 고정해 동행의 변경 위에 저장하지 않습니다.
  const [version] = useState(trip.version);
  const [hasWindow, setHasWindow] = useState(!!item.visitStart);
  const [start, setStart] = useState(item.visitStart ?? "09:00");
  const [end, setEnd] = useState(item.visitEnd ?? "21:00");
  const [cost, setCost] = useState(
    item.visitCost == null ? "" : String(item.visitCost),
  );
  const [note, setNote] = useState(item.visitNote ?? "");
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [error, setError] = useState<Error | null>(null);
  const toast = useToast();
  const safeClose = useCallback(() => {
    if (!busyRef.current) close();
  }, [close]);
  const stale =
    trip.version !== version ||
    (error instanceof ApiError && error.status === 409);
  const clock = (v: string) => {
    const [h, m] = v.split(":").map(Number);
    return h * 60 + m;
  };
  const outside =
    hasWindow &&
    start &&
    end &&
    (clock(item.startTime) < clock(start) ||
      clock(item.startTime) + item.duration > clock(end));
  const date = new Date(String(trip.startDate).slice(0, 10) + "T00:00:00");
  date.setDate(date.getDate() + item.dayIndex);
  const visitDate = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
  async function save(e: FormEvent) {
    e.preventDefault();
    if (busyRef.current || stale) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      await api(`/trips/${trip.id}/items/${item.id}/conditions`, {
        method: "PUT",
        body: {
          version,
          startTime: hasWindow ? start : null,
          endTime: hasWindow ? end : null,
          cost: cost === "" ? null : Number(cost),
          note,
        },
      });
      await refresh();
      toast(
        outside
          ? "조건을 저장했어요. 전체 일정 비교에서 방문 시간을 맞춰 주세요."
          : "방문 조건과 참고 예산을 저장했어요.",
      );
      close();
    } catch (e) {
      setError(e as Error);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }
  return (
    <Modal
      title="방문 조건 설정"
      onClose={safeClose}
      className="visit-conditions-modal"
    >
      <div className="visit-conditions-target">
        <span className="badge">
          {dateLabel(visitDate)} · {item.dayIndex + 1}일차
        </span>
        <h3>{item.placeName}</h3>
        <p>
          현재 {item.startTime} 시작 · {item.duration}분 머무르기
        </p>
      </div>
      <p className="muted">
        방문 날짜의 휴무·입장 마감·할인 조건을 확인한 뒤 입력해 주세요. 같은
        날의 이 방문에 적용하며 동행과 함께 사용해요.
      </p>
      <form onSubmit={save}>
        <fieldset disabled={busy || stale}>
          <legend>
            <CalendarClock size={18} /> 방문 가능 시간
          </legend>
          <label className="task-row">
            <input
              type="checkbox"
              checked={hasWindow}
              onChange={(e) => setHasWindow(e.target.checked)}
            />
            <span>확인한 시간 범위 안에 방문하기</span>
          </label>
          {hasWindow && (
            <div className="visit-conditions-times">
              <label>
                방문 가능 시작
                <input
                  type="time"
                  required
                  min="06:00"
                  max="22:00"
                  value={start}
                  onChange={(e) => setStart(e.target.value)}
                />
              </label>
              <label>
                방문 완료 마감
                <input
                  type="time"
                  required
                  min="06:00"
                  max="22:00"
                  value={end}
                  onChange={(e) => setEnd(e.target.value)}
                />
              </label>
            </div>
          )}
          <p className="small muted">
            06:00~22:00 안에서 체류 {item.duration}분을 모두 마칠 수 있는 연속
            범위로 정해 주세요. 입장 마감 이후에도 관람할 수 있다면 직접 이용
            정책을 확인해 주세요.
          </p>
          <label>
            1인당 예상 비용 · 선택
            <input
              type="number"
              min="0"
              max="500000"
              step="1"
              value={cost}
              onChange={(e) => setCost(e.target.value)}
              placeholder="미확인이면 비워 두세요"
            />
          </label>
          <p className="small muted">
            0원도 직접 입력할 수 있어요. {trip.partySize}명 기준{" "}
            {cost === ""
              ? "미입력 시 기존 추천 참고값을 사용해요."
              : `총 ${money(Number(cost) * trip.partySize)}을 참고 예산에 반영해요.`}{" "}
            실제 결제·지출 기록과는 별개예요.
          </p>
          <label>
            확인 메모 · 선택
            <textarea
              rows={3}
              maxLength={500}
              value={note}
              onChange={(e) => setNote(e.target.value)}
              placeholder="예: 일요일 운영시간·성인 요금 확인. 문의처 또는 확인 날짜"
            />
          </label>
          <p className="small muted">
            휴게 시간이 있다면 방문할 한 구간을 정해 주세요. 다른 장소로 바꾸면
            조건을 초기화하고, 같은 장소의 방문일정 복원에서는 최신 조건을
            유지해요.
          </p>
        </fieldset>
        {outside && (
          <div className="notice danger" role="status">
            현재 방문이 입력한 시간 범위를 벗어나요. 조건 저장 후 ‘전체 일정
            비교’에서 시간을 조정해 주세요. 고정 방문은 먼저 고정 여부를
            확인해야 해요.
          </div>
        )}
        {error && <ErrorState error={error} />}
        {stale && (
          <div className="notice danger" role="status">
            일정이 변경되어 저장할 수 없어요. 최신 일정을 확인하고 다시 입력해
            주세요.
          </div>
        )}
        <div className="visit-conditions-actions">
          {stale ? (
            <button
              type="button"
              className="button secondary full"
              onClick={async () => {
                await refresh();
                close();
              }}
            >
              최신 일정 불러오기
            </button>
          ) : (
            <>
              <button
                type="submit"
                className="button primary full"
                disabled={busy}
              >
                {busy ? (
                  <Loader2 size={17} className="spin" />
                ) : (
                  <Check size={17} />
                )}
                방문 조건 저장하기
              </button>
              <button
                type="button"
                className="text-button"
                disabled={busy}
                onClick={() => {
                  setHasWindow(false);
                  setCost("");
                  setNote("");
                  setError(null);
                }}
              >
                입력값 비우기 · 저장 후 반영
              </button>
            </>
          )}
        </div>
      </form>
    </Modal>
  );
}
