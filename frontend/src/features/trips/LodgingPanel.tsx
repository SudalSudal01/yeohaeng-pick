import { useState } from "react";
import { Hotel, ArrowUpRight, Check, Loader2 } from "lucide-react";
import type { Trip } from "../../shared/types";
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
        객실 한 개 기준 예시 요금이에요. 인원별 객실 수와 날짜별 실제 가격은
        예약 사이트에서 확인해 주세요.
      </p>
    </div>
  );
}
