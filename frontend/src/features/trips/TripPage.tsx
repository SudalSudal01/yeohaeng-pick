import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useParams, useNavigate } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  MapPin,
  CalendarDays,
  Users,
  Share2,
  ArrowLeft,
  ArrowRight,
  GitCompareArrows,
  LockKeyhole,
  Clock,
  TrainFront,
  ArrowUpRight,
  Plus,
  History,
  MessageCircle,
  CloudRain,
  Undo2,
  Wallet,
  Mail,
  BedDouble,
} from "lucide-react";
import {
  api,
  accessEnded,
  money,
  activityPrice,
  bookingLabels,
  dateLabel,
  calendarDateLabel,
} from "../../shared/api";
import type { Trip, Item, SelectedRoute } from "../../shared/types";
import { useAuth, useToast } from "../../shared/providers";
import { Loading, ErrorState, Modal } from "../../shared/ui";
import RouteMap from "./RouteMap";
import LodgingPanel from "./LodgingPanel";
import TransportPanel from "./TransportPanel";
import { scheduleTime } from "./bookingScheduleTime";
import AppliedRouteTimings from "./AppliedRouteTimings";
import OvernightPanel from "./OvernightPanel";
import ExternalBookings from "./ExternalBookings";
import BookingSchedulePanel from "./BookingSchedulePanel";
import WholeSchedulePanel from "./WholeSchedulePanel";
import TourismInfoButton from "../catalog/TourismInfoButton";
import VisitConditionsModal from "./VisitConditionsModal";
import TripMembersPanel from "./TripMembersPanel";
import { BookingModal } from "./TripDialogs";
import PlaceComparisonModal from "./PlaceComparisonModal";
import { lodgingBudget, activityBudget } from "./tripBudget";
import { overnightConnection } from "./transportSchedule";
import { isVisitChangeProtected } from "./visitComparisonCandidates";

/** 15초마다 서버의 공유 일정을 조회합니다. 비교 화면은 열린 시점 version을 사용해 동행의 수정을 덮지 않습니다. */
export default function TripPage() {
  const { id = "" } = useParams();
  const client = useQueryClient();
  const toast = useToast();
  const navigate = useNavigate();
  const { user } = useAuth();
  const query = useQuery({
    queryKey: ["trip", id],
    queryFn: () => api<Trip>("/trips/" + id),
    refetchInterval: (query) =>
      accessEnded(query.state.error) ? false : 15000,
    retry: false,
    gcTime: 0,
  });
  const [day, setDay] = useState(0);
  const items = useMemo(
    () => query.data?.items.filter((i) => i.dayIndex === day) ?? [],
    [query.data?.items, day],
  );
  const [selectedRoute, setSelectedRoute] = useState<SelectedRoute | null>(
    null,
  );
  // 날짜나 공동 편집 내용이 달라지면 이전 구간의 경로가 남지 않도록 초기화합니다.
  useEffect(() => setSelectedRoute(null), [id, day, query.data?.version]);
  const [tab, setTab] = useState("일정");
  const [compare, setCompare] = useState<Item | null>(null);
  const [booking, setBooking] = useState<Item | null>(null);
  const [visitConditions, setVisitConditions] = useState<Item | null>(null);
  const closeVisitConditions = useCallback(() => setVisitConditions(null), []);
  const [rain, setRain] = useState(false);
  const [invite, setInvite] = useState(false);
  const [share, setShare] = useState(false);
  const [shareLink, setShareLink] = useState("");
  const [email, setEmail] = useState("");
  useEffect(() => {
    if (query.data && query.data.ownerId !== user?.id) {
      // 다른 창에서 소유권을 넘겨도 이전 소유자의 초대/공유 창과 공유 토큰을 화면에 남기지 않습니다.
      setInvite(false);
      setShare(false);
      setShareLink("");
      setEmail("");
    }
  }, [query.data?.ownerId, user?.id]);
  const [task, setTask] = useState("");
  const [label, setLabel] = useState("");
  const [amount, setAmount] = useState("");
  const [busy, setBusy] = useState(false);
  const ended = accessEnded(query.error);
  useEffect(() => {
    if (!ended) return;
    // 화면 밖에 남은 비교 결과를 정리하고 목록도 현재 참여 기준으로 갱신합니다.
    client.removeQueries({
      predicate: (q) => q.queryKey[0] !== "trip" && q.queryKey.includes(id),
    });
    void client.invalidateQueries({ queryKey: ["trips"] });
    void client.invalidateQueries({ queryKey: ["rooms"] });
  }, [ended, client, id]);
  const closeCompare = useCallback(() => setCompare(null), []);
  const closeBooking = useCallback(() => setBooking(null), []);
  const closeInvite = useCallback(() => setInvite(false), []);
  const closeShare = useCallback(() => setShare(false), []);
  const refresh = async () => {
    await client.invalidateQueries({ queryKey: ["trip", id] });
    await client.invalidateQueries({ queryKey: ["trips"] });
  };
  async function action(
    path: string,
    body: unknown,
    message: string,
    method = "POST",
  ) {
    setBusy(true);
    try {
      await api("/trips/" + id + path, { method, body });
      await refresh();
      toast(message);
      return true;
    } catch (e) {
      toast((e as Error).message, true);
      await refresh();
      return false;
    } finally {
      setBusy(false);
    }
  }
  if (query.isPending) return <Loading />;
  if (ended)
    return (
      <div className="page access-ended" role="status">
        <h1>이 여행에 접근할 수 없어요</h1>
        <p>
          참여가 종료됐거나 여행이 더 이상 존재하지 않아요. 다시 함께 준비하려면
          소유자에게 새 초대를 받아 주세요.
        </p>
        <Link className="button primary" to="/trips">
          내 여행으로 돌아가기
        </Link>
      </div>
    );
  if (query.error || !query.data)
    return <ErrorState error={query.error} retry={() => query.refetch()} />;
  const trip = query.data;
  const days = [...new Set(trip.items.map((i) => i.dayIndex))];
  const activitySummary = activityBudget(trip);
  const activity = activitySummary.total;
  const stayBudget = lodgingBudget(trip);
  const stayEstimate = stayBudget.total;
  const plannedEstimate = activity + stayEstimate;
  const paid = trip.expenses.reduce((s, e) => s + e.amount, 0);
  async function sendInvite() {
    try {
      await api("/invitations", {
        method: "POST",
        body: { tripId: id, email },
      });
      toast("초대를 보냈어요. 상대방은 내 여행에서 수락할 수 있어요.");
      setInvite(false);
      setEmail("");
    } catch (e) {
      toast((e as Error).message, true);
    }
  }
  async function groupChat() {
    try {
      const room = await api<{ id: string }>("/messages/trip/" + id, {
        method: "POST",
      });
      navigate("/messages?room=" + room.id);
    } catch (e) {
      toast((e as Error).message, true);
    }
  }
  async function sharing(enabled: boolean) {
    try {
      const r = await api<{ token: string }>("/trips/" + id + "/share", {
        method: "POST",
        body: { enabled },
      });
      setShareLink(enabled ? location.origin + "/share/" + r.token : "");
      toast(
        enabled
          ? "새 읽기 전용 링크를 만들었어요. 이전 링크는 종료됐어요."
          : "공유 링크를 종료했어요.",
      );
    } catch (e) {
      toast((e as Error).message, true);
    }
  }
  return (
    <div className="page trip-page">
      <Link className="back-link" to="/trips">
        <ArrowLeft size={16} />내 여행
      </Link>
      <div className="trip-title-row">
        <div>
          <div className="trip-meta">
            <span className="badge">
              <MapPin size={14} />
              {trip.regions.join(" → ")}
            </span>
            <span>
              <CalendarDays size={15} />
              {/* 다른 해로 가져온 여행도 날짜를 혼동하지 않도록 시작 연도를 표시합니다. 연말을 넘기면 종료 연도도 표시합니다. */}
              {calendarDateLabel(trip.startDate)} ~{" "}
              {trip.startDate.slice(0, 4) === trip.endDate.slice(0, 4)
                ? dateLabel(String(trip.endDate).slice(0, 10))
                : calendarDateLabel(trip.endDate)}
            </span>
            <span>
              <Users size={15} />
              {trip.partySize}명
            </span>
          </div>
          <h1>{trip.title}</h1>
          <p className="muted">
            {trip.members.map((m) => m.nickname).join(", ")}님의 여행 · 공동
            변경 자동 반영
          </p>
        </div>
        <div className="row">
          {trip.ownerId === user?.id && (
            <>
              <button
                className="button secondary"
                onClick={() => setInvite(true)}
              >
                <Plus size={17} />
                동행 초대
              </button>
              <button
                className="button secondary"
                onClick={() => setShare(true)}
              >
                <Share2 size={17} />
                공유
              </button>
            </>
          )}
          <button
            className="icon-button outlined"
            onClick={groupChat}
            aria-label="여행 단체 대화 열기"
          >
            <MessageCircle size={20} />
          </button>
        </div>
      </div>
      <div className="trip-overview">
        <div>
          <span>전체 예산</span>
          <strong>{money(trip.budget)}</strong>
        </div>
        <div>
          <span>활동비 합계 · 추정</span>
          <strong className={activity > trip.budget ? "orange" : ""}>
            {money(activity)}
          </strong>
        </div>
        <div>
          <span>함께 고른 취향</span>
          <strong>{trip.preferences.join(" · ") || "다양한 경험"}</strong>
        </div>
        <div>
          <span>이동 방식</span>
          <strong>
            {trip.transport === "CAR"
              ? "자동차"
              : trip.transport === "WALK"
                ? "도보 중심"
                : "대중교통"}
          </strong>
        </div>
      </div>
      <div
        className={"notice " + (plannedEstimate > trip.budget ? "danger" : "")}
      >
        <Wallet size={18} />
        <div>
          <strong>활동비 + 숙박비 참고 합계 {money(plannedEstimate)}</strong>
          <p>
            {plannedEstimate > trip.budget
              ? `예산보다 ${money(plannedEstimate - trip.budget)} 높아요. 후보의 비용과 장점을 비교해 선택해 주세요. `
              : "현재 선택한 후보의 참고 금액이에요. "}
            {trip.lodgings.some((p) => p.totalAmount != null)
              ? `직접 입력한 총 숙박비 ${money(stayBudget.entered)} 포함이에요.`
              : "직접 입력한 숙박비는 아직 없어요."}
            {stayBudget.example > 0 &&
              ` 숙소 예시 금액 ${money(stayBudget.example)}를 더했어요.`}
            {stayBudget.unpriced > 0 &&
              ` 가격 미확인 숙소 ${stayBudget.unpriced}곳은 합계에서 제외했어요.`}{" "}
            왕복 및 지역 간 교통비, 미확인 장소 비용과 아직 선택하지 않은 숙소
            비용은 합계에서 제외돼요.
            {trip.items.some((i) => i.visitCost != null) &&
              ` 직접 입력한 활동비 ${money(activitySummary.entered)} 포함이에요.`}
            {activitySummary.unpriced > 0 &&
              ` 비용 미확인 장소 ${activitySummary.unpriced}곳이 있어요.`}
          </p>
        </div>
      </div>
      <div className="tabs">
        {["일정", "예약", "함께 준비", "변경 이력"].map((t) => (
          <button
            className={tab === t ? "active" : ""}
            onClick={() => setTab(t)}
            key={t}
          >
            {t}
          </button>
        ))}
      </div>
      {(tab === "일정" || tab === "예약") && (
        <BookingSchedulePanel
          trip={trip}
          refresh={refresh}
          onBookings={() => setTab("예약")}
        />
      )}
      {tab === "일정" ? (
        <>
          <WholeSchedulePanel trip={trip} refresh={refresh} />
          {/* 날짜 선택과 비교 도구를 나눕니다. 두 비교 링크는 한 그룹 안에서 일정한 간격을 유지합니다. */}
          <div className="trip-schedule-toolbar">
            <div className="day-tabs">
              {days.map((d) => (
                <button
                  className={day === d ? "active" : ""}
                  key={d}
                  onClick={() => setDay(d)}
                >
                  DAY {d + 1}
                  <span>
                    {trip.items.find((i) => i.dayIndex === d)?.region}
                  </span>
                </button>
              ))}
            </div>
            <nav className="trip-compare-links" aria-label="일정 비교 도구">
              <a className="text-button" href="#transport-routes">
                <TrainFront size={16} />
                <span>이동 경로 비교</span>
              </a>
              {overnightConnection(trip, day) && (
                <a className="text-button" href="#overnight-routes">
                  <BedDouble size={16} />
                  <span>숙소·휴식 비교</span>
                </a>
              )}
            </nav>
          </div>
          <div className="itinerary-layout">
            <div>
              <div className="itinerary-heading">
                <h2>
                  DAY {day + 1} <span>오늘의 여정</span>
                </h2>
                <button
                  className="text-button"
                  onClick={() => {
                    const outdoor = items.find(
                      (i) => !i.indoor && !isVisitChangeProtected(i),
                    );
                    if (outdoor) {
                      setRain(true);
                      setCompare(outdoor);
                    } else toast("바꿀 수 있는 야외 일정이 없어요.");
                  }}
                >
                  <CloudRain size={17} />
                  비가 온다면?
                </button>
              </div>
              <div className="timeline">
                {items.map((item, index) => (
                  <div className="timeline-entry" key={item.id}>
                    <div className="timeline-time">
                      {item.startTime}
                      <span className="timeline-node">{index + 1}</span>
                    </div>
                    <article className="itinerary-card">
                      <div className="itinerary-card-top">
                        <span className="badge subtle">
                          {item.category} · {item.indoor ? "실내" : "야외"}
                        </span>
                        {isVisitChangeProtected(item) && (
                          <span className="fixed-label">
                            <LockKeyhole size={13} />
                            {item.fixed ? "고정 일정" : "예약 완료 · 변경 보호"}
                          </span>
                        )}
                      </div>
                      <h3>{item.placeName}</h3>
                      {trip.routeTimings
                        ?.filter(
                          (t) => t.toItemId === item.id && !!t.originDeparture,
                        )
                        .map((t) => (
                          <p
                            className="small route-timing-applied"
                            key={t.toItemId}
                          >
                            {t.overnight ? (
                              `${t.overnight.lodgingName} · ${scheduleTime(t.overnight.departure)} 출발 · 조회 ${t.overnight.outbound.minutes}분 + 여유 ${t.bufferMinutes}분 반영`
                            ) : (
                              <>
                                {t.fromKind === "LODGING"
                                  ? `숙소 ${t.originDeparture} 출발`
                                  : `${scheduleTime(t.originDeparture!)} 이동 출발`}{" "}
                                · 조회 {t.minutes}분 + 여유 {t.bufferMinutes}분
                                반영
                              </>
                            )}
                          </p>
                        ))}
                      <p>
                        <Clock size={14} />
                        {item.duration}분 머무르기<span>·</span>
                        {activityPrice(item)}
                      </p>
                      <div className="itinerary-card-bottom">
                        <a
                          href={item.placeUrl}
                          target="_blank"
                          rel="noreferrer"
                          className="text-button"
                        >
                          상세·리뷰 보기
                          <ArrowUpRight size={15} />
                        </a>
                        <button
                          className="button small-button secondary"
                          onClick={() => {
                            setRain(false);
                            setCompare(item);
                          }}
                          disabled={isVisitChangeProtected(item)}
                          title={
                            isVisitChangeProtected(item)
                              ? "예약 완료 또는 고정된 일정은 상태를 먼저 정리해 주세요."
                              : undefined
                          }
                        >
                          <GitCompareArrows size={15} />
                          다른 후보 비교
                        </button>
                      </div>
                      <div className="tourism-entry-action">
                        <TourismInfoButton
                          target={{
                            name: item.placeName,
                            region: item.region,
                            url: item.placeUrl,
                          }}
                          onSetConditions={() => setVisitConditions(item)}
                        />
                        <button
                          className="text-button"
                          onClick={() => setVisitConditions(item)}
                          aria-label={item.placeName + " 방문 조건 설정"}
                        >
                          <CalendarDays size={17} />
                          방문 조건 설정
                        </button>
                      </div>
                      {(item.visitStart ||
                        item.visitCost != null ||
                        item.visitNote) && (
                        <div className="visit-conditions-summary">
                          <strong>직접 확인한 방문 조건</strong>
                          {item.visitStart && (
                            <p>
                              {item.visitStart}~{item.visitEnd} 안에 체류 완료
                            </p>
                          )}
                          {item.visitCost != null && (
                            <p>1인당 {money(item.visitCost)} · 예상 비용</p>
                          )}
                          {item.visitNote && (
                            <p className="visit-conditions-note">
                              {item.visitNote}
                            </p>
                          )}
                        </div>
                      )}
                    </article>
                    {index < items.length - 1 && (
                      <div className="travel-segment">
                        <TrainFront size={14} />
                        <span>
                          {(() => {
                            const timing = trip.routeTimings?.find(
                              (t) =>
                                t.toItemId === items[index + 1].id &&
                                t.fromId === item.id,
                            );
                            return timing
                              ? `조회 경로 ${timing.minutes}분 + 여유 ${timing.bufferMinutes}분 반영 · ${timing.mode === "CAR" ? "자동차" : timing.mode === "WALK" ? "도보" : "대중교통"}`
                              : "다음 장소까지 이동 · 시간은 추정치로 비교해요";
                          })()}
                        </span>
                      </div>
                    )}
                  </div>
                ))}
              </div>
              <div className="notice">
                {items.some((i) => i.source === "SAMPLE")
                  ? "로컬 예시 일정이에요. "
                  : ""}
                운영시간·휴무·현재 요금은 장소 상세에서 확인해 주세요. 숙박비와
                출발지 왕복 교통비는 활동비 합계에 포함되지 않아요.
              </div>
              <AppliedRouteTimings trip={trip} day={day} refresh={refresh} />
              <OvernightPanel
                key={`overnight:${trip.id}:${day}:${trip.version}`}
                trip={trip}
                day={day}
                refresh={refresh}
                onDayChange={setDay}
              />
              <TransportPanel
                key={`${trip.id}:${day}:${trip.version}`}
                trip={trip}
                day={day}
                selected={selectedRoute}
                onSelect={setSelectedRoute}
                refresh={refresh}
                onDayChange={setDay}
              />
              <LodgingPanel trip={trip} refresh={refresh} />
            </div>
            <aside>
              <RouteMap
                items={items}
                route={selectedRoute}
                onClear={() => setSelectedRoute(null)}
              />
              <div className="trip-note">
                <strong>출발과 숙박</strong>
                <p>
                  <MapPin size={15} />
                  {trip.origin}에서 출발
                </p>
                <p>
                  {trip.lodging ||
                    (trip.lodgings?.length
                      ? "선택한 숙소는 베이스캠프에서 확인해요."
                      : "숙소가 아직 정해지지 않았어요.")}
                </p>
                <Link
                  className="text-button"
                  to={
                    "/explore?region=" +
                    encodeURIComponent(trip.regions[0]) +
                    "&category=숙소"
                  }
                >
                  숙소 후보 보기
                  <ArrowRight size={15} />
                </Link>
              </div>
            </aside>
          </div>
        </>
      ) : tab === "예약" ? (
        <div className="panel">
          <div className="section-heading">
            <div>
              <h2>예약도 한곳에서 확인해요</h2>
              <p>외부 예매 사이트에서 돌아왔다면 결과를 직접 알려 주세요.</p>
            </div>
          </div>
          <div className="notice">
            링크 방문만으로 예약 완료로 처리하지 않아요. 직접 확인한 예약과
            제휴사에서 확인한 예약을 구분해요.
          </div>
          <ExternalBookings trip={trip} refresh={refresh} />
          {trip.items.map((i) => (
            <div className="booking-row" key={i.id}>
              <span className="green-icon">
                <CalendarDays size={21} />
              </span>
              <div>
                <strong>{i.placeName}</strong>
                <p>
                  DAY {i.dayIndex + 1} · {i.startTime} ·{" "}
                  {bookingLabels[i.bookingStatus]}
                </p>
                {i.bookingReason && (
                  <span className="small muted">{i.bookingReason}</span>
                )}
              </div>
              {i.fixed && <LockKeyhole size={17} />}
              <button
                className="button secondary"
                onClick={() => setBooking(i)}
              >
                상태 관리
              </button>
            </div>
          ))}
        </div>
      ) : tab === "함께 준비" ? (
        <div className="preparation-grid">
          <TripMembersPanel
            trip={trip}
            userId={user?.id ?? ""}
            invite={() => setInvite(true)}
            groupChat={groupChat}
            refresh={refresh}
            left={() => navigate("/trips", { replace: true })}
          />
          <section className="panel">
            <h2>같이 챙기는 준비물</h2>
            {trip.tasks.map((t) => (
              <label
                className={"task-row " + (t.done ? "done" : "")}
                key={t.id}
              >
                <input
                  type="checkbox"
                  checked={t.done}
                  onChange={() =>
                    action(
                      "/tasks",
                      { taskId: t.id, done: !t.done },
                      "준비 상태를 저장했어요.",
                    )
                  }
                />
                <span>{t.content}</span>
              </label>
            ))}
            <form
              className="inline-form"
              onSubmit={async (e) => {
                e.preventDefault();
                if (
                  await action(
                    "/tasks",
                    { content: task },
                    "준비물을 추가했어요.",
                  )
                )
                  setTask("");
              }}
            >
              <input
                aria-label="새 준비물"
                placeholder="함께 준비할 내용"
                value={task}
                onChange={(e) => setTask(e.target.value)}
                maxLength={200}
                required
              />
              <button className="button primary" disabled={busy}>
                <Plus size={18} />
                <span className="sr-only">추가</span>
              </button>
            </form>
          </section>
          <section className="panel expenses-panel">
            <div className="section-heading">
              <div>
                <h2>우리의 여행 경비</h2>
                <p>실제 결제한 비용을 기록해요. 송금 기능은 포함하지 않아요.</p>
              </div>
              <Wallet size={25} />
            </div>
            <div className="expense-total">
              <div>
                <span>기록한 총지출</span>
                <strong>{money(paid)}</strong>
              </div>
              <div>
                <span>1인당 균등 부담 · {trip.partySize}명 기준</span>
                <strong>{money(Math.round(paid / trip.partySize))}</strong>
              </div>
            </div>
            {trip.expenses.map((e) => (
              <div className="expense-row" key={e.id}>
                <div>
                  <strong>{e.label}</strong>
                  <span>{e.nickname}님 결제</span>
                </div>
                <strong>{money(e.amount)}</strong>
              </div>
            ))}
            <form
              className="expense-form"
              onSubmit={async (e) => {
                e.preventDefault();
                if (
                  await action(
                    "/expenses",
                    { label, amount: Number(amount) },
                    "지출을 기록했어요.",
                  )
                ) {
                  setLabel("");
                  setAmount("");
                }
              }}
            >
              <input
                aria-label="지출 내용"
                placeholder="예: 점심 식사"
                value={label}
                onChange={(e) => setLabel(e.target.value)}
                required
                maxLength={100}
              />
              <input
                aria-label="지출 금액"
                type="number"
                min={1}
                placeholder="금액 (원)"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                required
              />
              <button className="button primary" disabled={busy}>
                <Plus size={16} />
                기록
              </button>
            </form>
          </section>
        </div>
      ) : (
        <section className="panel">
          <h2>
            <History size={21} /> 함께 다듬은 기록
          </h2>
          <p className="small muted">
            방문 일정만 복원해요. 예약 기록과 반영한 경로 시간은 유지되며
            충돌하면 복원을 막아요. 조회값을 해제하려면 일정 탭의 ‘반영한 이동
            시간’을 확인해 주세요. 같은 장소의 최신 방문 조건은 유지하고, 장소가
            바뀌면 조건을 다시 확인해야 해요.
          </p>
          {!trip.history.length ? (
            <p className="muted">
              앞으로 바꾸는 일정과 예약 상태가 여기에 기록돼요.
            </p>
          ) : (
            trip.history.map((h) => (
              <div className="history-row" key={h.id}>
                <div>
                  <strong>{h.summary}</strong>
                  <p>
                    {h.nickname} · 변경 {h.version} ·{" "}
                    {new Date(h.createdAt).toLocaleString("ko-KR")}
                  </p>
                </div>
                <button
                  className="button secondary small-button"
                  disabled={busy}
                  onClick={() =>
                    action(
                      "/undo",
                      { historyId: h.id, version: trip.version },
                      "이전 일정을 복원했어요.",
                    )
                  }
                >
                  <Undo2 size={15} />
                  방문일정 복원
                </button>
              </div>
            ))
          )}
        </section>
      )}
      {compare && (
        <PlaceComparisonModal
          trip={trip}
          item={compare}
          rain={rain}
          close={closeCompare}
          applied={async () => {
            setCompare(null);
            await refresh();
          }}
        />
      )}
      {visitConditions && (
        <VisitConditionsModal
          trip={trip}
          item={visitConditions}
          close={closeVisitConditions}
          refresh={refresh}
        />
      )}
      {booking && (
        <BookingModal
          trip={trip}
          item={booking}
          close={closeBooking}
          save={async (body) => {
            if (
              await action("/booking", body, "예약 상태를 저장했어요.", "PUT")
            )
              setBooking(null);
          }}
        />
      )}
      {invite && (
        <Modal title="여행에 초대하기" onClose={closeInvite}>
          <p className="muted">
            가입한 동행의 이메일을 입력해 주세요. 수락 후 공동 편집과 취향
            합치기가 가능해요.
          </p>
          <label>
            동행 이메일
            <input
              type="email"
              placeholder="friend@example.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </label>
          <button
            className="button primary full"
            onClick={sendInvite}
            disabled={!email.includes("@")}
          >
            <Mail size={18} />
            초대 보내기
          </button>
        </Modal>
      )}
      {share && (
        <Modal title="읽기 전용 일정 공유" onClose={closeShare}>
          <p className="muted">
            장소·방문 시간·참고 활동비를 공개해요. 출발지·예약·숙소·경비·동행
            개인정보는 포함되지 않아요.
          </p>
          <p className="muted">
            링크를 가진 사람은 독립된 내 여행으로 가져올 수 있어요. 공유를
            종료해도 이미 가져간 복사본은 유지돼요.
          </p>
          <button className="button primary full" onClick={() => sharing(true)}>
            새 공유 링크 만들기
          </button>
          {shareLink && (
            <div className="share-result">
              <input aria-label="공유 링크" readOnly value={shareLink} />
              <button
                className="button secondary"
                onClick={async () => {
                  await navigator.clipboard.writeText(shareLink);
                  toast("링크를 복사했어요.");
                }}
              >
                복사
              </button>
            </div>
          )}
          <button
            className="text-button danger-text"
            onClick={() => sharing(false)}
          >
            기존 공유 링크 종료하기
          </button>
        </Modal>
      )}
    </div>
  );
}
