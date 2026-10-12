import { useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import {
  ArrowRight,
  ArrowLeft,
  Check,
  MapPin,
  CalendarDays,
  Users,
  Wallet,
  TrainFront,
  Car,
  Footprints,
  Sparkles,
  Clock,
  CheckCircle2,
  Loader2,
} from "lucide-react";
import { api, money } from "../../shared/api";
import { useAuth, useConfig, useToast } from "../../shared/providers";
import type { Conditions, Course } from "../../shared/types";
import { ErrorState } from "../../shared/ui";
import LodgingPicker from "./LodgingPicker";
import FavoritePicker from "../favorites/FavoritePicker";

/** 단계별 입력을 유지하고, 생성 중 중복 요청을 막습니다. AI 대체 실행은 engine 값으로 정확히 표시합니다. */
export default function PlanPage() {
  const [params] = useSearchParams();
  const { user } = useAuth();
  const navigate = useNavigate();
  const config = useConfig();
  const toast = useToast();
  const today = new Date().toLocaleDateString("en-CA");
  // 날짜 필드는 input에서도 상태를 갱신해 change가 늦는 브라우저에서 단계 이동 시 값을 잃지 않습니다.
  const [form, setForm] = useState<Conditions>({
    title: "우리의 새로운 여행",
    regions: [params.get("region") || "서울"],
    startDate: today,
    endDate: today,
    origin: "서울역",
    partySize: 2,
    budget: 300000,
    transport: "TRANSIT",
    preferences: user?.preferences || [],
    lodging: "",
    lodgingIds: [],
    favoriteIds: params.get("favorite") ? [params.get("favorite")!] : [],
  });
  const [step, setStep] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error>();
  const [courses, setCourses] = useState<Course[]>([]);
  const [selected, setSelected] = useState(1);
  const set = <K extends keyof Conditions>(key: K, value: Conditions[K]) =>
    setForm((f) => ({ ...f, [key]: value }));
  // 지역을 제거하면 이전 지역의 숙소 선택도 지웁니다. 오래된 선택이 새 여행에 섞이지 않습니다.
  const toggle = (key: "regions" | "preferences", value: string) => {
    setForm((f) => ({
      ...f,
      [key]: f[key].includes(value)
        ? f[key].filter((v) => v !== value)
        : [...f[key], value],
      ...(key === "regions" ? { lodgingIds: [], favoriteIds: [] } : {}),
    }));
  };
  async function generate() {
    if (!form.regions.length) return toast("여행할 지역을 골라 주세요.", true);
    if (form.endDate < form.startDate)
      return toast("돌아오는 날짜를 확인해 주세요.", true);
    setBusy(true);
    setError(undefined);
    try {
      const result = await api<Course[]>("/recommendations", {
        method: "POST",
        body: form,
      });
      setCourses(result);
      setStep(3);
    } catch (e) {
      setError(e as Error);
    } finally {
      setBusy(false);
    }
  }
  async function save() {
    setBusy(true);
    try {
      const trip = await api<{ id: string }>("/trips", {
        method: "POST",
        body: { conditions: form, items: courses[selected].items },
      });
      toast("여행을 저장했어요. 이제 함께 다듬어 볼까요?");
      navigate("/trips/" + trip.id);
    } catch (e) {
      setError(e as Error);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="page plan-page">
      <div className="page-heading center">
        <span className="eyebrow">LET'S MAKE IT YOURS</span>
        <h1>
          {step === 3
            ? "우리에게 맞는 여행을 골라요"
            : "어떤 여행을 꿈꾸고 있나요?"}
        </h1>
        <p>
          {step === 3
            ? "세 가지 선택지를 비교하고, 마음에 드는 코스부터 다듬어 보세요."
            : "몇 가지 선택만 해 주세요. 여행픽이 계획을 함께 준비할게요."}
        </p>
      </div>
      <ol className="stepper">
        {["어디로, 언제", "누구와, 어떻게", "우리의 취향", "코스 고르기"].map(
          (s, i) => (
            <li
              key={s}
              className={i === step ? "current" : i < step ? "complete" : ""}
            >
              <span>{i < step ? <Check size={15} /> : i + 1}</span>
              {s}
            </li>
          ),
        )}
      </ol>
      {step < 3 ? (
        <div className="plan-card">
          <div className="plan-number">0{step + 1}</div>
          {step === 0 ? (
            <>
              <h2>이번 여행의 시작을 정해요</h2>
              <p className="muted">
                여러 지역을 선택하면 선택한 순서로 여행해요. 지역을 바꾸면
                숙소와 찜 선택을 다시 정해 주세요.
              </p>
              <label>
                여행 이름
                <input
                  maxLength={100}
                  value={form.title}
                  onChange={(e) => set("title", e.target.value)}
                  placeholder="예: 우리 둘의 가을 여행"
                />
              </label>
              <label>어디로 떠날까요?</label>
              <div className="region-choices">
                {["서울", "부산", "제주", "강릉"].map((c) => (
                  <button
                    key={c}
                    className={
                      "region-choice " +
                      (form.regions.includes(c) ? "selected" : "")
                    }
                    onClick={() => toggle("regions", c)}
                  >
                    <MapPin size={20} />
                    <strong>{c}</strong>
                    {form.regions.includes(c) && (
                      <span>{form.regions.indexOf(c) + 1}</span>
                    )}
                  </button>
                ))}
              </div>
              <div className="form-grid">
                <label>
                  <span>
                    <CalendarDays size={16} /> 출발일
                  </span>
                  <input
                    type="date"
                    value={form.startDate}
                    onInput={(e) => {
                      const value = e.currentTarget.value;
                      setForm((f) => ({
                        ...f,
                        startDate: value,
                        endDate: value > f.endDate ? value : f.endDate,
                      }));
                    }}
                    onChange={(e) => {
                      set("startDate", e.target.value);
                      if (e.target.value > form.endDate)
                        set("endDate", e.target.value);
                    }}
                  />
                </label>
                <label>
                  돌아오는 날
                  <input
                    type="date"
                    min={form.startDate}
                    value={form.endDate}
                    onInput={(e) => set("endDate", e.currentTarget.value)}
                    onChange={(e) => set("endDate", e.target.value)}
                  />
                </label>
              </div>
              <label>
                어디에서 출발하나요?
                <input
                  value={form.origin}
                  onChange={(e) => set("origin", e.target.value)}
                  placeholder="주소 또는 출발역을 입력해 주세요"
                  maxLength={150}
                />
              </label>
            </>
          ) : step === 1 ? (
            <>
              <h2>우리에게 편한 방식을 골라요</h2>
              <p className="muted">
                예산은 동행 전체의 여행 예산을 기준으로 입력해 주세요.
              </p>
              <div className="form-grid">
                <label>
                  <span>
                    <Users size={16} /> 여행 인원
                  </span>
                  <input
                    type="number"
                    min={1}
                    max={20}
                    value={form.partySize}
                    onChange={(e) => set("partySize", Number(e.target.value))}
                  />
                </label>
                <label>
                  <span>
                    <Wallet size={16} /> 전체 예산
                  </span>
                  <div className="input-suffix">
                    <input
                      type="number"
                      min={10000}
                      step={10000}
                      value={form.budget}
                      onChange={(e) => set("budget", Number(e.target.value))}
                    />
                    <span>원</span>
                  </div>
                </label>
              </div>
              <label>주로 어떻게 이동하나요?</label>
              <div className="transport-choices">
                {[
                  {
                    value: "TRANSIT",
                    label: "대중교통",
                    icon: TrainFront,
                    desc: "기차 · 버스 · 지하철",
                  },
                  {
                    value: "CAR",
                    label: "자동차",
                    icon: Car,
                    desc: "자차 · 렌터카",
                  },
                  {
                    value: "WALK",
                    label: "도보 중심",
                    icon: Footprints,
                    desc: "가까운 곳을 천천히",
                  },
                ].map((t) => (
                  <button
                    className={form.transport === t.value ? "selected" : ""}
                    onClick={() =>
                      set("transport", t.value as Conditions["transport"])
                    }
                    key={t.value}
                  >
                    <t.icon size={26} />
                    <strong>{t.label}</strong>
                    <span>{t.desc}</span>
                  </button>
                ))}
              </div>
              <LodgingPicker
                regions={form.regions}
                selected={form.lodgingIds}
                onChange={(ids) => set("lodgingIds", ids)}
              />
              <label>
                직접 남길 숙소 메모가 있나요? <small>선택</small>
                <input
                  value={form.lodging}
                  maxLength={150}
                  onChange={(e) => set("lodging", e.target.value)}
                  placeholder="숙소 이름 또는 주소 · 미정이면 비워두세요"
                />
              </label>
              <div className="notice">
                검색된 숙소를 선택하면 동선의 기준으로 사용해요. 직접 입력한
                메모와 출발지 왕복 교통은 별도로 확인해 주세요.
              </div>
            </>
          ) : (
            <>
              <h2>좋아하는 순간을 담아 주세요</h2>
              <p className="muted">
                여러 개를 골라도 좋아요. 이번 여행에만 적용돼요.
              </p>
              <div className="preference-choices">
                {["자연", "관광", "맛집", "카페", "전시", "체험"].map((c) => (
                  <button
                    key={c}
                    className={
                      "preference-choice " +
                      (form.preferences.includes(c) ? "selected" : "")
                    }
                    onClick={() => toggle("preferences", c)}
                  >
                    {c}
                    {form.preferences.includes(c) && <Check size={17} />}
                  </button>
                ))}
              </div>
              <div className="plan-summary">
                <h3>이런 여행을 준비할게요</h3>
                <p>
                  {form.regions.join(" → ")} · {form.startDate} ~ {form.endDate}
                </p>
                <p>
                  {form.partySize}명 · 전체 예산 {money(form.budget)}
                </p>
                <p>
                  {form.preferences.length
                    ? form.preferences.join(" · ")
                    : "다양한 경험을 균형 있게"}
                </p>
              </div>
              <FavoritePicker
                regions={form.regions}
                selected={form.favoriteIds || []}
                onChange={(ids) => set("favoriteIds", ids)}
              />
              <div className="notice">
                {config.data?.aiConfigured
                  ? "AI가 후보를 평가하고 일정 엔진이 동선과 예산을 검증해요."
                  : "AI 키가 설정되지 않아 규칙 기반 추천으로 체험해요."}
                {config.data?.sampleData
                  ? " 장소 정보는 로컬 예시 데이터예요."
                  : ""}
              </div>
            </>
          )}
          {error && <ErrorState error={error} />}
          <div className="plan-actions">
            {step > 0 ? (
              <button
                className="button secondary"
                onClick={() => setStep(step - 1)}
                disabled={busy}
              >
                <ArrowLeft size={17} />
                이전
              </button>
            ) : (
              <span />
            )}
            <button
              className="button primary"
              disabled={
                busy ||
                !form.title.trim() ||
                !form.regions.length ||
                !form.origin.trim() ||
                !form.startDate ||
                !form.endDate
              }
              onClick={() => (step === 2 ? generate() : setStep(step + 1))}
            >
              {busy ? (
                <Loader2 className="spin" size={18} />
              ) : step === 2 ? (
                <Sparkles size={18} />
              ) : null}
              {busy
                ? "여행을 구성하고 있어요"
                : step === 2
                  ? "추천 코스 보기"
                  : "다음"}
              {!busy && <ArrowRight size={17} />}
            </button>
          </div>
        </div>
      ) : (
        <>
          <div className="recommendation-meta">
            <span className="badge">
              <MapPin size={14} />
              {form.regions.join(" → ")}
            </span>
            <span>
              {form.partySize}명 · {form.startDate} ~ {form.endDate}
            </span>
            <button className="text-button" onClick={() => setStep(0)}>
              조건 다시 선택
            </button>
          </div>
          <div className="course-grid">
            {courses.map((c, i) => (
              <button
                className={"course-card " + (selected === i ? "selected" : "")}
                key={c.variant}
                onClick={() => setSelected(i)}
                aria-pressed={selected === i}
              >
                <div className="course-top">
                  <span className="badge">OPTION 0{i + 1}</span>
                  {selected === i ? (
                    <CheckCircle2 size={23} />
                  ) : (
                    <span className="radio-circle" />
                  )}
                </div>
                <h2>{c.title}</h2>
                <p>{c.subtitle}</p>
                <div className="course-price">
                  <strong>{money(c.estimatedCost)}</strong>
                  <span>전체 인원 활동비 추정</span>
                  {c.lodgings?.length > 0 && (
                    <span>
                      숙소 1객실 추정 · {money(c.lodgingEstimate)} 별도
                    </span>
                  )}
                </div>
                <div className="course-stat">
                  <Clock size={16} />
                  일정 내 이동 약 {c.travelMinutes}분
                </div>
                {!!form.favoriteIds?.length && (
                  <p className="course-favorites">
                    선택한 찜 {form.favoriteIds.length}곳 중{" "}
                    {
                      c.items.filter((s) =>
                        form.favoriteIds?.includes(s.place.id),
                      ).length
                    }
                    곳 반영
                  </p>
                )}
                <div className="course-route">
                  {c.items.slice(0, 5).map((s) => (
                    <div key={s.id}>
                      <span className="route-dot" />
                      <div>
                        <span>
                          {s.place.category} · DAY {s.dayIndex + 1}
                        </span>
                        <strong>{s.place.name}</strong>
                      </div>
                    </div>
                  ))}
                  {c.items.length > 5 && <p>외 {c.items.length - 5}개 장소</p>}
                </div>
                <span className="course-engine">
                  {c.engine === "GEMINI"
                    ? "AI 취향 평가 반영"
                    : "규칙 기반 추천"}{" "}
                  ·{" "}
                  {c.items.some((s) => s.place.source === "SAMPLE")
                    ? "예시 장소"
                    : "조회된 장소"}
                </span>
              </button>
            ))}
          </div>
          <div className="notice course-warnings">
            <div>
              {courses[selected]?.warnings.map((w) => (
                <p key={w}>{w}</p>
              ))}
            </div>
          </div>
          {error && <ErrorState error={error} />}
          <div className="save-plan">
            <div>
              <strong>{courses[selected]?.title}</strong>
              <p>저장한 뒤에도 장소 비교와 일정 수정이 가능해요.</p>
            </div>
            <button className="button primary" disabled={busy} onClick={save}>
              {busy ? (
                <Loader2 className="spin" size={18} />
              ) : (
                <Check size={18} />
              )}
              이 코스로 시작하기
            </button>
          </div>
        </>
      )}
    </div>
  );
}
