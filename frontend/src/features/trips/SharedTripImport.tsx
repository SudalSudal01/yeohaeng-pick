import {
  useCallback,
  useRef,
  useState,
  type FormEvent,
  type RefObject,
} from "react";
import { useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { Copy, Loader2 } from "lucide-react";
import { api, ApiError, calendarDateLabel } from "../../shared/api";
import { useAuth, useToast } from "../../shared/providers";
import { ErrorState, Modal } from "../../shared/ui";
import type { Conditions, SharedTrip } from "../../shared/types";
import { importedEndDate, localToday, sharedTripDays } from "./sharedTripDates";

export type ImportAttempt = {
  token: string;
  snapshot: string;
  requestId: string;
  conditions: Conditions;
};

/** 원본의 비공개 여행 조건은 사용하지 않습니다. 사용자 조건과 공개 스냅샷만 서버에 전달합니다. */
export default function SharedTripImport({
  trip,
  token,
  onClose,
  attempt,
}: {
  trip: SharedTrip;
  token: string;
  onClose: () => void;
  attempt: RefObject<ImportAttempt | null>;
}) {
  const { user } = useAuth();
  const toast = useToast();
  const navigate = useNavigate();
  const client = useQueryClient();
  // 백그라운드 재조회로 배경 일정이 바뀌어도 사용자가 양식을 열 때 본 공개본을 기준으로 요청합니다.
  const viewed = useRef(trip);
  const [title, setTitle] = useState(
    () =>
      attempt.current?.conditions.title ??
      `${trip.title.slice(0, 92)} · 내 여행`,
  );
  const [startDate, setStartDate] = useState(
    () => attempt.current?.conditions.startDate ?? localToday(),
  );
  const [origin, setOrigin] = useState(
    () => attempt.current?.conditions.origin ?? "",
  );
  const [partySize, setPartySize] = useState(() =>
    String(attempt.current?.conditions.partySize ?? 2),
  );
  const [budget, setBudget] = useState(() =>
    String(attempt.current?.conditions.budget ?? 300000),
  );
  const [transport, setTransport] = useState<Conditions["transport"]>(
    attempt.current?.conditions.transport ?? trip.transport,
  );
  const [preferences, setPreferences] = useState(
    attempt.current?.conditions.preferences ?? user?.preferences ?? [],
  );
  const [pending, setPending] = useState(false);
  const [uncertain, setUncertain] = useState(attempt.current !== null);
  const [error, setError] = useState<unknown>(null);
  const [blocked, setBlocked] = useState(false);
  const busy = useRef(false);
  // 응답을 잃은 재시도도 같은 요청 키/본문을 사용합니다. 완료 여부가 불분명한 동안 입력을 잠가 중복 생성을 막습니다.
  const [review, setReview] = useState(false);
  const close = useCallback(() => {
    if (!busy.current) onClose();
  }, [onClose]);
  const days =
    uncertain && attempt.current
      ? sharedTripDays(
          attempt.current.conditions.startDate,
          attempt.current.conditions.endDate,
        )
      : sharedTripDays(viewed.current.startDate, viewed.current.endDate);
  const endDate = importedEndDate(startDate, days);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy.current || blocked) return;
    busy.current = true;
    setPending(true);
    setError(null);
    attempt.current ??= {
      token,
      snapshot: viewed.current.snapshot,
      requestId: crypto.randomUUID(),
      conditions: {
        title: title.trim(),
        regions: viewed.current.regions,
        startDate,
        endDate,
        origin: origin.trim(),
        partySize: Number(partySize),
        budget: Number(budget),
        transport,
        preferences,
        lodging: "",
        lodgingIds: [],
      },
    };
    try {
      const result = await api<{ id: string }>("/trips/import", {
        method: "POST",
        body: attempt.current,
      });
      await client.invalidateQueries({ queryKey: ["trips"] });
      toast(
        "내 여행으로 가져왔어요. 날짜에 맞게 이동과 운영시간을 확인해 주세요.",
      );
      navigate("/trips/" + result.id);
    } catch (e) {
      setError(e);
      const ambiguous =
        !(e instanceof ApiError) || e.status === 0 || e.status >= 500;
      setUncertain(ambiguous);
      if (!ambiguous) attempt.current = null;
      if (e instanceof ApiError && e.status === 409) {
        await client.invalidateQueries({ queryKey: ["share", token] });
        const latest = client.getQueryData<SharedTrip>(["share", token]);
        if (latest) viewed.current = latest;
        setReview(true);
      }
      if (e instanceof ApiError && [401, 403, 404, 410].includes(e.status))
        setBlocked(true);
    } finally {
      busy.current = false;
      setPending(false);
    }
  }
  return (
    <Modal
      title="내 여행으로 가져오기"
      onClose={close}
      className="trip-import-modal"
    >
      <p className="muted">
        장소·방문 순서·시간을 {days}일 일정으로 복사해요. 원본과 별도로 수정할
        수 있어요.
      </p>
      <form className="trip-import-form" onSubmit={submit}>
        <fieldset disabled={pending || uncertain || blocked}>
          <legend className="sr-only">새 여행 조건</legend>
          <label>
            여행 이름
            <input
              required
              maxLength={100}
              value={title}
              onChange={(e) => setTitle(e.target.value)}
            />
          </label>
          <label>
            새 여행 시작일
            <input
              required
              type="date"
              max="9999-12-17"
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
              aria-describedby="import-dates"
            />
          </label>
          <p id="import-dates" className="muted">
            {endDate
              ? `${calendarDateLabel(startDate)} ~ ${calendarDateLabel(endDate)}`
              : "시작일을 선택해 주세요."}{" "}
            · {days === 1 ? "당일치기" : `${days - 1}박 ${days}일`}
            <br />
            {viewed.current.regions.join(" → ")} · 원본의 지역과 일수를
            유지해요.
          </p>
          <label>
            내 출발지
            <input
              required
              maxLength={150}
              value={origin}
              onChange={(e) => setOrigin(e.target.value)}
              placeholder="예: 서울역, 우리 동네"
              autoComplete="off"
            />
          </label>
          <div className="trip-import-grid">
            <label>
              함께 가는 인원
              <input
                required
                type="number"
                min={1}
                max={20}
                value={partySize}
                onChange={(e) => setPartySize(e.target.value)}
              />
            </label>
            <label>
              전체 여행 예산 (원)
              <input
                required
                type="number"
                min={10000}
                max={100000000}
                step={1}
                value={budget}
                onChange={(e) => setBudget(e.target.value)}
              />
            </label>
          </div>
          <label>
            주로 이용할 이동수단
            <select
              value={transport}
              onChange={(e) =>
                setTransport(e.target.value as Conditions["transport"])
              }
            >
              <option value="TRANSIT">대중교통</option>
              <option value="CAR">자동차</option>
              <option value="WALK">도보</option>
            </select>
          </label>
          <div>
            <span className="trip-import-label">이번 여행 취향</span>
            <div
              className="trip-import-preferences"
              role="group"
              aria-label="이번 여행 취향"
            >
              {["자연", "관광", "맛집", "카페", "전시", "체험"].map((value) => (
                <button
                  type="button"
                  className={
                    "button " +
                    (preferences.includes(value)
                      ? "secondary selected"
                      : "secondary")
                  }
                  aria-pressed={preferences.includes(value)}
                  key={value}
                  onClick={() =>
                    setPreferences((current) =>
                      current.includes(value)
                        ? current.filter((p) => p !== value)
                        : [...current, value],
                    )
                  }
                >
                  {value}
                </button>
              ))}
            </div>
          </div>
        </fieldset>
        <div className="trip-import-explanation">
          <strong>새 날짜에 맞게 다시 확인해 주세요</strong>
          <p>
            예약·숙소·경비·준비물·동행·방문 메모·고정 조건·조회한 이동 경로는
            가져오지 않아요. 표시된 활동비는 참고 추정이며, 새 날짜의 운영시간과
            이동 가능 여부를 보장하지 않아요.
          </p>
        </div>
        {error != null && <ErrorState error={error} />}
        {uncertain && (
          <p className="muted" role="status">
            응답을 받지 못해 완료 여부를 확인하고 있어요. 입력한 조건을 유지한
            채 다시 누르면 같은 요청의 결과를 확인해요.
          </p>
        )}
        {error instanceof ApiError && error.status === 409 && (
          <p className="muted">
            아래의 최신 일정을 확인해 주세요. 입력한 내 여행 조건은 유지했어요.
          </p>
        )}
        {review && (
          <details className="trip-import-review" open>
            <summary>변경된 최신 방문 일정 확인</summary>
            <ul>
              {viewed.current.items.map((item, n) => (
                <li key={n}>
                  DAY {item.dayIndex + 1} · {item.startTime} · {item.placeName}
                </li>
              ))}
            </ul>
          </details>
        )}
        {blocked && (
          <p className="muted">
            창을 닫고 로그인 상태와 공유 링크를 다시 확인해 주세요.
          </p>
        )}
        <div className="trip-import-actions">
          <button
            className="button primary"
            disabled={pending || blocked}
            type="submit"
          >
            {pending ? (
              <Loader2 size={18} className="spin" />
            ) : (
              <Copy size={18} />
            )}{" "}
            {pending
              ? "가져오는 중…"
              : uncertain
                ? "가져오기 결과 다시 확인"
                : "이 조건으로 가져오기"}
          </button>
          <button
            type="button"
            className="button secondary"
            disabled={pending}
            onClick={close}
          >
            닫기
          </button>
        </div>
      </form>
    </Modal>
  );
}
