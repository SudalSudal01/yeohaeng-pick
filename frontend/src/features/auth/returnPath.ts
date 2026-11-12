/** 로그인 후 이동은 같은 사이트의 경로만 허용합니다.
 * 라우터도 외부 이동을 막지만, 잘못된 next 때문에 로그인에 성공하고도
 * 영어 오류가 뜨며 로그인 화면에 남는 일을 피하도록 먼저 기본 경로로 정리합니다. */
export function returnPath(next: string | null, origin: string) {
  const fallback = "/trips";
  if (
    !next?.startsWith("/") ||
    next.startsWith("//") ||
    /[\\\u0000-\u001f\u007f]/u.test(next)
  )
    return fallback;
  try {
    const target = new URL(next, origin);
    const decodedPath = decodeURIComponent(target.pathname);
    if (
      target.origin !== origin ||
      decodedPath.startsWith("//") ||
      /[\\\u0000-\u001f\u007f]/u.test(decodedPath)
    )
      return fallback;
    return target.pathname + target.search + target.hash;
  } catch {
    return fallback;
  }
}
