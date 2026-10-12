import { useState } from "react";
import { Heart, Loader2 } from "lucide-react";
import { Link, useLocation } from "react-router-dom";
import { api } from "../../shared/api";
import { useAuth, useToast } from "../../shared/providers";
import type { Place } from "../../shared/types";
import { useFavorites } from "./useFavorites";

/** 검색 조건과 ID만 전달합니다. 모호한 통신 실패 뒤에도 다시 조회해 서버의 실제 저장 상태와 맞춥니다. */
export default function FavoriteButton({
  place,
  search,
}: {
  place: Place;
  search?: { region: string; query: string; category: string };
}) {
  const { user } = useAuth();
  const location = useLocation();
  const favorites = useFavorites();
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  if (!user)
    return (
      <Link
        className="button secondary favorite-button"
        to={
          "/login?next=" +
          encodeURIComponent(location.pathname + location.search)
        }
      >
        <Heart size={17} />
        로그인하고 찜
      </Link>
    );
  const saved = favorites.data?.some((f) => f.place.id === place.id);
  async function toggle() {
    setBusy(true);
    try {
      if (saved)
        await api("/favorites/" + encodeURIComponent(place.id), {
          method: "DELETE",
        });
      else if (search)
        await api("/favorites", {
          method: "POST",
          body: { placeId: place.id, ...search },
        });
      else return;
      toast(saved ? "찜을 해제했어요." : "내 찜에 보관했어요.");
    } catch (e) {
      toast((e as Error).message, true);
    } finally {
      await favorites.refresh();
      setBusy(false);
    }
  }
  return (
    <button
      type="button"
      className={
        "button secondary favorite-button " + (saved ? "is-saved" : "")
      }
      aria-label={place.name + (saved ? " 찜 해제" : " 찜하기")}
      aria-pressed={!!saved}
      disabled={busy || favorites.isPending || !!favorites.error}
      onClick={toggle}
    >
      {busy ? (
        <Loader2 size={17} className="spin" />
      ) : (
        <Heart size={17} fill={saved ? "currentColor" : "none"} />
      )}
      {favorites.error
        ? "찜 상태 확인 필요"
        : favorites.isPending
          ? "찜 확인 중"
          : saved
            ? "찜 해제"
            : "찜하기"}
    </button>
  );
}
