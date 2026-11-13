import { useEffect, useRef } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../../shared/providers";

/** 공개 커뮤니티의 비공개 양식에서 시작한 요청이 끝나기 전에 계정이 바뀌면 후속 이동·캐시 작업을 중단합니다. */
export function useCommunitySession() {
  const { user } = useAuth();
  const client = useQueryClient();
  const member = user?.id || "";
  // 지연 로딩 중에는 이전 화면이 잠시 마운트된 채 남을 수 있어 브라우저의 실제 이동도 함께 확인합니다.
  const pathname = window.location.pathname;
  const mounted = useRef(false);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, [member]);
  return () =>
    mounted.current &&
    !!member &&
    window.location.pathname === pathname &&
    client.getQueryData<{ id: string }>(["me"])?.id === member;
}
