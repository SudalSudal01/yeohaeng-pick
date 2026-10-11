import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import {
  Plus,
  MapPin,
  CalendarDays,
  Users,
  ArrowRight,
  Mail,
  Check,
  X,
} from "lucide-react";
import { api, dateLabel, money } from "../../shared/api";
import type { Trip } from "../../shared/types";
import { useToast } from "../../shared/providers";
import { Empty, Loading, ErrorState } from "../../shared/ui";
import { cityImages } from "../home/HomePage";

/** 내 여행과 받은 초대를 함께 배치해 초대 수락 이후 공동 편집으로 자연스럽게 이어집니다. */
export default function TripsPage() {
  const trips = useQuery({
    queryKey: ["trips"],
    queryFn: () => api<Trip[]>("/trips"),
  });
  const invites = useQuery({
    queryKey: ["invitations"],
    queryFn: () =>
      api<{ id: string; title: string; nickname: string; tripId: string }[]>(
        "/invitations",
      ),
    refetchInterval: 15000,
  });
  const client = useQueryClient();
  const toast = useToast();
  async function respond(id: string, accept: boolean) {
    try {
      await api("/invitations/" + id + "/respond", {
        method: "POST",
        body: { accept },
      });
      await client.invalidateQueries({ queryKey: ["invitations"] });
      await client.invalidateQueries({ queryKey: ["trips"] });
      toast(
        accept
          ? "함께 여행을 준비할 수 있어요. 취향도 이번 여행에 반영했어요."
          : "초대를 거절했어요.",
      );
    } catch (e) {
      toast((e as Error).message, true);
    }
  }
  return (
    <div className="page">
      <div className="page-heading heading-row">
        <div>
          <span className="eyebrow">YOUR TRAVEL COLLECTION</span>
          <h1>차곡차곡, 나의 여행</h1>
          <p>계획 중인 설렘부터 다녀온 추억까지.</p>
        </div>
        <Link className="button primary" to="/plan">
          <Plus size={18} />새 여행 만들기
        </Link>
      </div>
      {invites.data?.map((i) => (
        <div className="invitation" key={i.id}>
          <Mail size={23} />
          <div>
            <strong>{i.nickname}님의 여행 초대</strong>
            <p>{i.title} · 수락하면 일정 편집에 참여하고 취향을 합쳐요.</p>
          </div>
          <button
            className="button secondary"
            onClick={() => respond(i.id, false)}
          >
            <X size={16} />
            거절
          </button>
          <button
            className="button primary"
            onClick={() => respond(i.id, true)}
          >
            <Check size={16} />
            수락
          </button>
        </div>
      ))}
      {trips.isPending ? (
        <Loading />
      ) : trips.error ? (
        <ErrorState error={trips.error} />
      ) : !trips.data?.length ? (
        <Empty
          title="첫 여행을 담아 볼까요?"
          text="취향을 고르면 세 가지 코스를 준비해 드려요."
          action="나의 첫 여행 만들기"
          to="/plan"
        />
      ) : (
        <div className="trip-grid">
          {trips.data.map((t) => (
            <Link to={"/trips/" + t.id} className="trip-card" key={t.id}>
              <div className="trip-cover">
                <img
                  src={cityImages[t.regions[0]] || cityImages["서울"]}
                  alt={t.regions[0] + " 여행 풍경"}
                />
                <span className="badge">나의 여행</span>
              </div>
              <div className="trip-card-body">
                <span className="muted small">
                  <MapPin size={14} />
                  {t.regions.join(" → ")}
                </span>
                <h2>{t.title}</h2>
                <p>
                  <CalendarDays size={16} />
                  {dateLabel(String(t.startDate).slice(0, 10))} ~{" "}
                  {dateLabel(String(t.endDate).slice(0, 10))}
                </p>
                <div className="trip-card-foot">
                  <span>
                    <Users size={16} />
                    {t.partySize}명 · 예산 {money(t.budget)}
                  </span>
                  <ArrowRight size={20} />
                </div>
              </div>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
