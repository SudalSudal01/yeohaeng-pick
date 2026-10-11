import { TrainFront, ArrowUpRight } from "lucide-react";
import type { Trip } from "../../shared/types";

/** 예매처의 좌석·결제 결과를 읽을 수 없으므로 공식 예매와 사용자의 결과 확인을 분리합니다. */
export default function TransportPanel({ trip }: { trip: Trip }) {
  return (
    <div className="trip-note">
      <h3>
        <TrainFront size={18} /> 여행지까지 어떻게 갈까요?
      </h3>
      <p>
        {trip.origin} → {trip.regions.join(" → ")}
      </p>
      <p className="small muted">
        출발·도착역과 날짜는 예매처에서 선택해 주세요. 현재 요금·잔여 좌석은
        조회되지 않았어요.
      </p>
      <div className="transport-links">
        {[
          { name: "코레일 · 열차", url: "https://www.korail.com/" },
          { name: "SRT · 열차", url: "https://etk.srail.kr/" },
          { name: "고속버스", url: "https://www.kobus.co.kr/" },
          { name: "시외버스", url: "https://txbus.t-money.co.kr/" },
        ].map((l) => (
          <a href={l.url} target="_blank" rel="noreferrer" key={l.name}>
            {l.name}
            <ArrowUpRight size={14} />
          </a>
        ))}
      </div>
      <p className="small muted">
        예약 결과는 ‘예약’에 기록하고, 실제 열차·버스 시간에 맞춰 일정을 확인해
        주세요.
      </p>
    </div>
  );
}
