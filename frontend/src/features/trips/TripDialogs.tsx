import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  MapPin,
  Check,
  LockKeyhole,
  Loader2,
  ArrowUpRight,
} from "lucide-react";
import { api, money, activityPrice, bookingLabels } from "../../shared/api";
import type { Trip, Item, Place, Comparison } from "../../shared/types";
import { useToast } from "../../shared/providers";
import { Loading, ErrorState, Modal } from "../../shared/ui";
import { lodgingBudget } from "./tripBudget";

/** 장소 비교와 예약 편집을 분리해 일정 화면의 상태와 각 대화상자의 입력 상태를 독립적으로 관리합니다. */
export function ComparisonModal({
  trip,
  item,
  rain,
  close,
  applied,
}: {
  trip: Trip;
  item: Item;
  rain: boolean;
  close: () => void;
  applied: () => void;
}) {
  const [chosen, setChosen] = useState("");
  const [busy, setBusy] = useState(false);
  const toast = useToast();
  const [version] = useState(trip.version);
  const alternatives = useQuery({
    queryKey: ["alternatives", trip.id, item.id, rain, version],
    queryFn: () =>
      api<Place[]>(`/trips/${trip.id}/alternatives/${item.id}?rain=${rain}`),
  });
  const comparison = useQuery({
    queryKey: ["comparison", trip.id, item.id, chosen, version],
    enabled: !!chosen,
    queryFn: () =>
      api<Comparison>("/trips/" + trip.id + "/preview", {
        method: "POST",
        body: { itemId: item.id, placeId: chosen, version },
      }),
    retry: false,
  });
  async function apply() {
    setBusy(true);
    try {
      await api("/trips/" + trip.id + "/apply", {
        method: "POST",
        body: { itemId: item.id, placeId: chosen, version },
      });
      toast("변경된 동선과 비용을 일정에 반영했어요.");
      applied();
    } catch (e) {
      toast((e as Error).message, true);
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal
      title={rain ? "비 오는 날, 실내 후보로 바꿔요" : "선택하기 전에 비교해요"}
      onClose={close}
    >
      <div className="comparison-current">
        <span>지금 선택한 장소</span>
        <strong>{item.placeName}</strong>
        <p>
          {activityPrice(item)} · {item.duration}분
        </p>
      </div>
      <h3>이런 후보는 어때요?</h3>
      {alternatives.isPending ? (
        <Loading />
      ) : alternatives.error ? (
        <ErrorState error={alternatives.error} />
      ) : !alternatives.data?.length ? (
        <p className="muted">현재 조건에 맞는 다른 후보가 없어요.</p>
      ) : (
        <div className="alternative-list">
          {alternatives.data.map((p) => (
            <button
              className={"alternative " + (chosen === p.id ? "selected" : "")}
              key={p.id}
              onClick={() => setChosen(p.id)}
            >
              <span className="green-icon">
                <MapPin size={21} />
              </span>
              <div>
                <strong>{p.name}</strong>
                <span>
                  {p.category} · {p.indoor ? "실내" : "야외"} ·{" "}
                  {activityPrice(p)}
                </span>
              </div>
              {chosen === p.id && <Check size={19} />}
            </button>
          ))}
        </div>
      )}
      {comparison.isFetching ? (
        <Loading text="전체 일정에 미치는 영향을 계산해요" />
      ) : comparison.error ? (
        <ErrorState error={comparison.error} />
      ) : (
        comparison.data && (
          <div className="comparison-result">
            <span className="eyebrow">WHAT CHANGES</span>
            <h3>{comparison.data.summary}</h3>
            <div className="comparison-stats">
              <div>
                <span>전체 인원 활동비 변화</span>
                <strong>
                  {comparison.data.costDelta > 0 ? "+" : ""}
                  {money(comparison.data.costDelta)}
                </strong>
              </div>
              <div>
                <span>일정 내 이동 변화 · 추정</span>
                <strong>
                  {comparison.data.travelDelta > 0 ? "+" : ""}
                  {comparison.data.travelDelta}분
                </strong>
              </div>
            </div>
            <p>
              {comparison.data.costDelta > 0
                ? "추가 비용이 생겨요. 장소의 실내 여부·체류 시간과 함께 비교해 주세요."
                : "활동비를 유지하거나 줄일 수 있어요."}
            </p>
            <p>
              변경 후 활동비 + 숙박비 참고 합계{" "}
              {money(comparison.data.estimatedCost + lodgingBudget(trip).total)}
              {comparison.data.estimatedCost + lodgingBudget(trip).total >
              trip.budget
                ? " · 설정한 예산을 초과해요."
                : " · 현재 합산 금액은 예산 이내예요."}{" "}
              숙박비는 저장한 총 숙박비와 예시 금액을 사용해요. 미확인
              숙박비·장소 비용, 교통비와 미선택 숙소 비용은 포함하지 않았어요.
            </p>
            {comparison.data.conflicts.map((c) => (
              <p className="field-error" key={c}>
                {c}
              </p>
            ))}
            <div className="changed-times">
              {comparison.data.items
                .filter((i) => i.dayIndex === item.dayIndex)
                .map((i) => (
                  <p key={i.id}>
                    <span>{i.startTime}</span>
                    {i.placeName}
                    {i.fixed && <LockKeyhole size={12} />}
                  </p>
                ))}
            </div>
          </div>
        )
      )}
      <button
        className="button primary full"
        disabled={!comparison.data?.canApply || busy || comparison.isFetching}
        onClick={apply}
      >
        {busy ? <Loader2 className="spin" size={17} /> : <Check size={17} />}
        변경 내용 적용하기
      </button>
    </Modal>
  );
}
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
