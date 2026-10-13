import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import {
  ArrowRight,
  MapPin,
  CalendarDays,
  Sparkles,
  GitCompareArrows,
  Users,
  Check,
  ChevronRight,
  Leaf,
  Palette,
  Coffee,
  Mountain,
} from "lucide-react";
import { useConfig } from "../../shared/providers";

// 검증한 Unsplash 원본 URL을 사용합니다. 사진의 출처는 푸터의 사진 안내에서 확인할 수 있습니다.
export const cityImages: Record<string, string> = {
  서울: "https://images.unsplash.com/photo-1678649653438-3b0d6f793f23?auto=format&fit=crop&w=1000&q=85",
  부산: "https://images.unsplash.com/photo-1597230842173-5588b5da029a?auto=format&fit=crop&w=1200&q=85",
  제주: "https://media.assettype.com/outlooktraveller/2024-03/1c93d885-9257-4852-a73c-9456bcbebf09/lauren_seo_Prq2iUSSp2U_unsplash.jpg?auto=format&fit=crop&w=1000&q=85",
  강릉: "https://images.unsplash.com/photo-1621044332832-717d5d986ab7?auto=format&fit=crop&w=1000&q=85",
};
export default function HomePage() {
  const navigate = useNavigate();
  const [region, setRegion] = useState("서울");
  const config = useConfig();
  return (
    <>
      <section className="hero">
        <img
          className="hero-image"
          src={cityImages["부산"]}
          alt="부산 광안리 해변과 광안대교"
          fetchPriority="high"
        />
        <div className="hero-shade" />
        <div className="hero-content">
          <span className="eyebrow light">
            <span className="little-line" /> EVERY TRIP, YOUR PICK
          </span>
          <h1>
            어디로 떠나든,
            <br />
            우리다운 여행.
          </h1>
          <p>
            취향은 담고, 고민은 덜고.
            <br />
            함께 고르는 여행의 시작, 여행픽.
          </p>
          <Link className="button cream" to="/plan">
            내 여행 만들기
            <ArrowRight size={19} />
          </Link>
        </div>
        <span className="hero-location">
          <MapPin size={14} /> 부산, 광안리
        </span>
        <span className="hero-counter">
          01 <span>/ 나에게 맞는 여행을 발견해요</span>
        </span>
      </section>
      <form
        className="quick-plan"
        onSubmit={(e) => {
          e.preventDefault();
          navigate("/plan?region=" + encodeURIComponent(region));
        }}
      >
        <div className="quick-intro">
          <span className="green-icon">
            <Sparkles size={22} />
          </span>
          <div>
            <strong>다음 여행, 어디로 갈까요?</strong>
            <span>몇 번의 선택으로 시작하는 나만의 코스</span>
          </div>
        </div>
        <label className="quick-select">
          <MapPin size={19} />
          <span className="sr-only">여행 지역</span>
          <select
            value={region}
            disabled={!config.data}
            onChange={(e) => setRegion(e.target.value)}
          >
            {!config.data && (
              <option value="서울">
                {config.error ? "지역 확인 필요" : "지역 불러오는 중"}
              </option>
            )}
            {config.data?.regions.map((c) => (
              <option key={c}>{c}</option>
            ))}
          </select>
        </label>
        <button
          className="button primary"
          type="submit"
          disabled={!config.data}
        >
          여행 계획하기
          <ArrowRight size={18} />
        </button>
      </form>
      <section className="home-section">
        <div className="section-heading">
          <div>
            <span className="eyebrow">FIND YOUR NEXT MOMENT</span>
            <h2>지금, 마음이 향하는 곳</h2>
            <p>가까운 도시부터 낯선 풍경까지. 이번 여행의 첫 번째 픽.</p>
          </div>
          <Link className="text-button" to="/explore">
            여행지 둘러보기
            <ArrowRight size={17} />
          </Link>
        </div>
        <div className="destination-grid">
          {[
            {
              name: "서울",
              en: "SEOUL",
              desc: "익숙한 도시의 새로운 발견",
              tag: "골목 산책 · 전시",
              image: cityImages["서울"],
            },
            {
              name: "부산",
              en: "BUSAN",
              desc: "바다를 따라 천천히",
              tag: "해변 · 로컬 맛집",
              image: cityImages["부산"],
            },
            {
              name: "제주",
              en: "JEJU",
              desc: "잠시, 일상에서 멀어지기",
              tag: "자연 · 드라이브",
              image: cityImages["제주"],
            },
            {
              name: "강릉",
              en: "GANGNEUNG",
              desc: "커피 한 잔과 파도 소리",
              tag: "카페 · 여유",
              image: cityImages["강릉"],
            },
          ].map((c, i) => (
            <Link
              key={c.name}
              className={"destination-card city-" + i}
              to={"/explore?region=" + encodeURIComponent(c.name)}
            >
              <img src={c.image} alt={c.name + " 여행 풍경"} loading="lazy" />
              <div className="destination-overlay" />
              <span className="destination-tag">{c.tag}</span>
              <div className="destination-copy">
                <span>{c.en}</span>
                <h3>{c.name}</h3>
                <p>{c.desc}</p>
              </div>
              <span className="destination-arrow">
                <ArrowRight size={20} />
              </span>
            </Link>
          ))}
        </div>
      </section>
      <section className="home-section">
        <div className="section-heading">
          <div>
            <span className="eyebrow">A LITTLE MORE YOU</span>
            <h2>어떤 순간을 좋아하세요?</h2>
          </div>
          <span className="muted small">오늘의 기분으로 골라보세요</span>
        </div>
        <div className="theme-grid">
          {[
            { name: "자연", desc: "풍경 속에서 쉬어가기", icon: Mountain },
            { name: "전시", desc: "취향을 채우는 시간", icon: Palette },
            { name: "카페", desc: "느긋한 한 잔의 여유", icon: Coffee },
            { name: "맛집", desc: "여행을 기억하는 맛", icon: Leaf },
          ].map((t) => (
            <Link
              className="theme-card"
              key={t.name}
              to={"/explore?category=" + encodeURIComponent(t.name)}
            >
              <span className="theme-icon">
                <t.icon size={25} />
              </span>
              <div>
                <strong>{t.name}</strong>
                <p>{t.desc}</p>
              </div>
              <ChevronRight size={17} />
            </Link>
          ))}
        </div>
      </section>
      <section className="why-section">
        <div className="why-copy">
          <span className="eyebrow">LESS WORRY, MORE JOURNEY</span>
          <h2>
            좋은 여행에는
            <br />
            좋은 선택이 필요하니까.
          </h2>
          <p>
            예약부터 동선까지 연결해서 생각해요.
            <br />
            계획이 바뀌어도, 함께 답을 찾아갈 수 있도록.
          </p>
          <Link className="text-button" to="/plan">
            여행픽과 시작하기
            <ArrowRight size={18} />
          </Link>
        </div>
        <div className="why-features">
          {[
            {
              icon: Sparkles,
              title: "나를 이해하는 세 가지 코스",
              text: "저장한 취향과 이번 여행의 조건을 바탕으로 서로 다른 선택지를 비교해요.",
            },
            {
              icon: GitCompareArrows,
              title: "바꾸기 전에, 달라지는 것까지",
              text: "장소를 바꾸면 비용과 이동, 다음 일정이 어떻게 달라지는지 미리 확인해요.",
            },
            {
              icon: Users,
              title: "함께 고르면 더 우리답게",
              text: "동행을 초대하고 취향을 합쳐요. 일정도 준비물도 함께 수정할 수 있어요.",
            },
          ].map((f) => (
            <div className="why-feature" key={f.title}>
              <span className="green-icon">
                <f.icon size={23} />
              </span>
              <div>
                <h3>{f.title}</h3>
                <p>{f.text}</p>
              </div>
              <Check size={18} />
            </div>
          ))}
        </div>
      </section>
      <section className="community-banner">
        <div>
          <span className="eyebrow">TRAVEL TOGETHER</span>
          <h2>여행 이야기도, 함께할 사람도.</h2>
          <p>다녀온 순간을 나누고, 다음 여행의 동행을 만나보세요.</p>
        </div>
        <Link className="button primary" to="/community">
          커뮤니티 둘러보기
          <ArrowRight size={18} />
        </Link>
      </section>
      {config.data?.sampleData && (
        <p className="source-note">
          <CalendarDays size={14} /> 현재 로컬 체험 환경이에요. 예시 장소와 추정
          비용으로 계획 흐름을 확인할 수 있어요.
        </p>
      )}
    </>
  );
}
