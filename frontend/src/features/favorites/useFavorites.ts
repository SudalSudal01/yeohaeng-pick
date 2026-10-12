import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../../shared/api";
import { useAuth } from "../../shared/providers";
import type { Favorite } from "../../shared/types";

/** 회원 ID로 캐시도 분리합니다. 실패를 빈 찜 목록으로 바꾸지 않아 잘못된 해제/저장 상태를 표시하지 않습니다. */
export function useFavorites() {
  const { user } = useAuth();
  const client = useQueryClient();
  const key = ["favorites", user?.id];
  const query = useQuery({
    queryKey: key,
    queryFn: () => api<Favorite[]>("/favorites"),
    enabled: !!user,
    retry: false,
  });
  return {
    ...query,
    refresh: () => client.invalidateQueries({ queryKey: key }),
  };
}
