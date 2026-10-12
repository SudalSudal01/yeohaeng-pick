import { useEffect, useState } from "react";
import {
  Routes,
  Route,
  Link,
  NavLink,
  useLocation,
  useNavigate,
} from "react-router-dom";
import {
  MapPin,
  Search,
  ArrowUpRight,
  Home,
  Compass,
  MessageCircle,
  UserRound,
  Menu,
  X,
  LogOut,
} from "lucide-react";
import { useAuth, useToast } from "../shared/providers";
import { RequireAuth, Empty } from "../shared/ui";
import HomePage from "../features/home/HomePage";
import AuthPage from "../features/auth/AuthPage";
import AccountPage from "../features/auth/AccountPage";
import ExplorePage from "../features/catalog/ExplorePage";
import PlanPage from "../features/planner/PlanPage";
import TripsPage from "../features/trips/TripsPage";
import TripPage from "../features/trips/TripPage";
import SharedTripPage from "../features/trips/SharedTripPage";
import CommunityRoute from "../features/community/CommunityRoute";
import MessagesPage from "../features/messaging/MessagesPage";
import ProfilePage from "../features/profile/ProfilePage";
import AdminPage from "../features/admin/AdminPage";

/** 공통 내비게이션. 현재 위치를 강조하고 모바일에서는 엄지로 접근하기 쉬운 하단 메뉴를 제공합니다. */
export default function App() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const toast = useToast();
  const [menu, setMenu] = useState(false);
  useEffect(() => {
    window.scrollTo(0, 0);
    setMenu(false);
    document.title =
      location.pathname === "/"
        ? "여행픽 · 우리다운 여행의 시작"
        : "여행픽 · 함께 고르는 여행";
  }, [location.pathname]);
  const links = [
    { to: "/explore", label: "여행지 탐색" },
    { to: "/trips", label: "내 여행" },
    { to: "/community", label: "커뮤니티" },
  ];
  return (
    <>
      <a className="skip-link" href="#main">
        본문으로 건너뛰기
      </a>
      <header className="site-header">
        <div className="header-inner">
          <Link className="brand" to="/" aria-label="여행픽 홈">
            <span className="brand-symbol">
              <MapPin size={23} strokeWidth={2.5} />
              <span />
            </span>
            여행<span>픽</span>
            <i>travelpick</i>
          </Link>
          <nav className="desktop-nav" aria-label="주 메뉴">
            {links.map((l) => (
              <NavLink key={l.to} to={l.to}>
                {l.label}
              </NavLink>
            ))}
          </nav>
          <div className="header-actions">
            <Link
              className="icon-button search-link"
              to="/explore"
              aria-label="여행지 검색"
            >
              <Search size={21} />
            </Link>
            {user ? (
              <>
                <Link
                  to="/messages"
                  className="icon-button"
                  aria-label="메시지"
                >
                  <MessageCircle size={21} />
                </Link>
                <Link
                  to="/profile"
                  className="avatar header-avatar"
                  aria-label="마이페이지"
                >
                  {user.nickname.slice(0, 1)}
                </Link>
                <button
                  className="icon-button logout-button"
                  aria-label="로그아웃"
                  onClick={async () => {
                    try {
                      await logout();
                      navigate("/");
                      toast("로그아웃했어요.");
                    } catch (e) {
                      toast((e as Error).message, true);
                    }
                  }}
                >
                  <LogOut size={17} />
                </button>
              </>
            ) : (
              <Link className="login-link" to="/login">
                {/* 좁은 화면에서는 짧은 진입 문구를 사용합니다. 회원가입 링크는 로그인 화면과 펼친 메뉴에서도 제공됩니다. */}
                <span className="login-full-label">로그인 / 회원가입</span>
                <span className="login-mobile-label">로그인</span>
                <ArrowUpRight size={14} />
              </Link>
            )}
            <button
              className="icon-button mobile-menu-button"
              aria-label={menu ? "메뉴 닫기" : "메뉴 열기"}
              aria-expanded={menu}
              onClick={() => setMenu(!menu)}
            >
              {menu ? <X /> : <Menu />}
            </button>
          </div>
        </div>
        {menu && (
          <nav className="mobile-dropdown" aria-label="모바일 추가 메뉴">
            {links.map((l) => (
              <NavLink key={l.to} to={l.to}>
                {l.label}
              </NavLink>
            ))}
            <NavLink to="/plan">새 여행 만들기</NavLink>
            {!user && <NavLink to="/login">로그인 / 회원가입</NavLink>}
          </nav>
        )}
      </header>
      <main
        id="main"
        className={location.pathname === "/" ? "home-main" : "app-main"}
      >
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/explore" element={<ExplorePage />} />
          <Route path="/login" element={<AuthPage />} />
          <Route path="/register" element={<AuthPage register />} />
          <Route
            path="/account/forgot"
            element={<AccountPage mode="forgot" />}
          />
          <Route
            path="/account/verify"
            element={<AccountPage mode="verify" />}
          />
          <Route path="/account/reset" element={<AccountPage mode="reset" />} />
          <Route
            path="/account/withdrawn"
            element={
              <Empty
                title="회원 탈퇴가 완료됐어요"
                text="계정 정보와 공개 작성물을 정리했어요. 함께 남긴 여행과 대화는 탈퇴한 회원으로 표시돼요. 다시 가입해도 이전 기록은 복원되지 않아요."
                action="홈으로 돌아가기"
                to="/"
              />
            }
          />
          <Route
            path="/plan"
            element={
              <RequireAuth>
                <PlanPage />
              </RequireAuth>
            }
          />
          <Route
            path="/trips"
            element={
              <RequireAuth>
                <TripsPage />
              </RequireAuth>
            }
          />
          <Route
            path="/trips/:id"
            element={
              <RequireAuth>
                <TripPage />
              </RequireAuth>
            }
          />
          <Route path="/community" element={<CommunityRoute />} />
          <Route path="/community/:id" element={<CommunityRoute detail />} />
          <Route
            path="/messages"
            element={
              <RequireAuth>
                <MessagesPage />
              </RequireAuth>
            }
          />
          <Route
            path="/profile"
            element={
              <RequireAuth>
                <ProfilePage />
              </RequireAuth>
            }
          />
          <Route
            path="/admin"
            element={
              <RequireAuth>
                <AdminPage />
              </RequireAuth>
            }
          />
          <Route path="/share/:token" element={<SharedTripPage />} />
          <Route
            path="*"
            element={
              <Empty
                title="길을 조금 벗어났네요"
                text="페이지를 찾을 수 없어요. 여행픽 홈에서 다시 시작해 주세요."
                action="홈으로 돌아가기"
                to="/"
              />
            }
          />
        </Routes>
      </main>
      <footer className="site-footer">
        <div>
          <Link className="brand" to="/">
            <MapPin size={22} />
            여행픽
          </Link>
          <p>함께 고르고, 우리답게 떠나는 여행.</p>
        </div>
        <div>
          <span>© {new Date().getFullYear()} 여행픽</span>
          <a href="https://unsplash.com" target="_blank" rel="noreferrer">
            여행 사진 · Unsplash
          </a>
        </div>
      </footer>
      <nav className="bottom-nav" aria-label="모바일 주 메뉴">
        {[
          { to: "/", name: "홈", icon: Home },
          { to: "/trips", name: "내 여행", icon: Compass },
          { to: "/community", name: "커뮤니티", icon: UsersIcon },
          { to: "/messages", name: "메시지", icon: MessageCircle },
          { to: "/profile", name: "마이", icon: UserRound },
        ].map((l) => (
          <NavLink end={l.to === "/"} to={l.to} key={l.to}>
            <l.icon size={21} />
            <span>{l.name}</span>
          </NavLink>
        ))}
      </nav>
    </>
  );
}
function UsersIcon({ size }: { size: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
    >
      <circle cx="9" cy="8" r="3" />
      <path d="M3 20v-2a6 6 0 0 1 12 0v2M16 5a3 3 0 0 1 0 6M18 14a5 5 0 0 1 3 4v2" />
    </svg>
  );
}
