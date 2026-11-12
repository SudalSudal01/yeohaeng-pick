import { useLayoutEffect, useState, type RefObject } from "react";

/** 화면에 실제로 남은 높이에서 헤더·모바일 메뉴를 빼고 대화 영역을 배치합니다.
 * visualViewport의 키보드/주소창 높이 변화에도 전송줄을 잘라내지 않으며, 다른 페이지로 나가면 전용 스타일을 제거합니다. */
export function useMessageViewport(ref: RefObject<HTMLDivElement | null>) {
  const [height, setHeight] = useState<number>();
  useLayoutEffect(() => {
    const root = document.documentElement;
    root.classList.add("messaging-view");
    let frame = 0;
    const measure = () => {
      frame = 0;
      const viewport = window.visualViewport;
      const mobile = window.matchMedia("(max-width: 760px)").matches;
      const editing =
        mobile &&
        Boolean(ref.current?.contains(document.activeElement)) &&
        Boolean(document.activeElement?.matches("input,textarea"));
      root.classList.toggle("messaging-keyboard", editing);
      const nav = document.querySelector<HTMLElement>(".bottom-nav");
      const navHeight =
        nav && getComputedStyle(nav).display !== "none"
          ? nav.getBoundingClientRect().height
          : 0;
      const top = Math.max(
        ref.current?.getBoundingClientRect().top || 0,
        viewport?.offsetTop || 0,
      );
      const visualHeight = viewport?.height || window.innerHeight;
      root.style.setProperty("--messaging-visual-height", `${visualHeight}px`);
      setHeight(
        Math.max(
          150,
          Math.floor(
            visualHeight + (viewport?.offsetTop || 0) - top - navHeight - 8,
          ),
        ),
      );
    };
    const schedule = () => {
      if (!frame) frame = requestAnimationFrame(measure);
    };
    measure();
    window.addEventListener("resize", schedule);
    window.addEventListener("scroll", schedule, { passive: true });
    window.visualViewport?.addEventListener("resize", schedule);
    window.visualViewport?.addEventListener("scroll", schedule);
    document.addEventListener("focusin", schedule);
    document.addEventListener("focusout", schedule);
    return () => {
      cancelAnimationFrame(frame);
      root.classList.remove("messaging-view", "messaging-keyboard");
      root.style.removeProperty("--messaging-visual-height");
      window.removeEventListener("resize", schedule);
      window.removeEventListener("scroll", schedule);
      window.visualViewport?.removeEventListener("resize", schedule);
      window.visualViewport?.removeEventListener("scroll", schedule);
      document.removeEventListener("focusin", schedule);
      document.removeEventListener("focusout", schedule);
    };
  }, [ref]);
  return height;
}
