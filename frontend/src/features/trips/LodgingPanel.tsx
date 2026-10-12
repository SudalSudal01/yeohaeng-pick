import { useCallback, useState } from "react";
import { Hotel, ArrowUpRight, Check, Loader2 } from "lucide-react";
import type { Trip, Lodging } from "../../shared/types";
import LodgingStayModal from "./LodgingStayModal";
import { api, bookingLabels, money } from "../../shared/api";
import { useToast } from "../../shared/providers";

/** 숙소 예약은 사용자가 직접 확인합니다. 외부 링크 클릭은 예약 완료 이벤트가 아닙니다. */
export default function LodgingPanel({
  trip,
  refresh,
}: {
  trip: Trip;
  refresh: () => Promise<void>;
}) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  // 편집을 시작한 조건과 version을 보존합니다. 자동 갱신된 최신 version으로 오래된 입력을 덮어쓰지 않습니다.
  const [editing, setEditing] = useState<{ hotel: Lodging; trip: Trip } | null>(
    null,
  );
  const close = useCallback(() => setEditing(null), []);
  async function change(placeId: string, status: string) {
    setBusy(true);
    try {
      await api("/trips/" + trip.id + "/lodging-booking", {
        method: "PUT",
        body: {
          itemId: placeId,
          status,
          reason: "",
          fixed: false,
          version: trip.version,
        },
      });
      await refresh();
      toast("숙소 예약 상태를 저장했어요.");
    } catch (e) {
      toast((e as Error).message, true);
      await refresh();
    } finally {
      setBusy(false);
    }
  }
  if (!trip.lodgings?.length) return null;
  return (
    <div className="trip-note">
      <h3>
        <Hotel size={18} /> 여행의 베이스캠프
      </h3>
      {trip.lodgings.map((p) => (
        <div className="lodging-summary" key={p.placeId}>
          <span className="badge subtle">{p.region} · 숙소 기준 동선</span>
          <h3>{p.name}</h3>
          <p>
            {p.source === "SAMPLE"
              ? money(p.nightlyEstimate) + " / 1객실 1박 · 예시"
              : "실제 객실 요금·재고 확인 필요"}
          </p>
          <a
            className="text-button"
            href={p.placeUrl}
            target="_blank"
            rel="noreferrer"
          >
            위치·리뷰·예약 안내
            <ArrowUpRight size={14} />
          </a>
          <div className="lodging-stay-summary">
            {p.checkInDate ? (
              <>
                <p>
                  체크인 {p.checkInDate.slice(0, 10)}{" "}
                  {p.checkInTime ?? "· 시각 미정"}
                  <br />
                  체크아웃 {p.checkOutDate?.slice(0, 10)}{" "}
                  {p.checkOutTime ?? "· 시각 미정"}
                </p>
                <p>
                  {p.guestCount}명 · {p.roomCount}객실
                  <br />
                  {p.totalAmount == null
                    ? "총 숙박비 미확인"
                    : `총 숙박비 ${money(p.totalAmount)} · 직접 입력`}
                </p>
              </>
            ) : (
              <p className="small muted">
                숙박 날짜·인원·객실 수가 아직 미정이에요.
              </p>
            )}
            <button
              className="button secondary"
              disabled={
                busy ||
                trip.startDate.slice(0, 10) === trip.endDate.slice(0, 10)
              }
              onClick={() => setEditing({ hotel: p, trip })}
              aria-label={`${p.name} 숙박 조건 ${p.checkInDate ? "수정" : "입력"}`}
            >
              숙박 조건 {p.checkInDate ? "수정" : "입력"}
            </button>
          </div>
          <label>
            예약 확인 상태
            <select
              disabled={busy}
              value={p.bookingStatus}
              onChange={(e) => change(p.placeId, e.target.value)}
            >
              {Object.entries(bookingLabels)
                .filter(([k]) => k !== "PARTNER_CONFIRMED")
                .map(([k, v]) => (
                  <option key={k} value={k}>
                    {v}
                  </option>
                ))}
            </select>
          </label>
          {busy ? (
            <Loader2 className="spin" size={14} />
          ) : p.bookingStatus === "USER_CONFIRMED" ? (
            <p className="small green">
              <Check size={14} /> 직접 확인한 예약이에요.
            </p>
          ) : null}
        </div>
      ))}
      <p className="small muted">
        숙박 조건은 직접 확인한 내용이에요. 객실 재고·정원·날짜별 가격은
        예약처에서 확인해 주세요.
      </p>
      {editing && (
        <LodgingStayModal
          key={`${editing.hotel.placeId}:${editing.trip.version}`}
          trip={editing.trip}
          hotel={editing.hotel}
          refresh={refresh}
          close={close}
        />
      )}
    </div>
  );
}
