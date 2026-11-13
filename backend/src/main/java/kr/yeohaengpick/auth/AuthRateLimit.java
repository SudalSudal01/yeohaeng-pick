package kr.yeohaengpick.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 단일 서버용 인증 요청 제한. 프록시의 임의 X-Forwarded-For는 신뢰하지 않습니다. 다중 인스턴스에서는 Redis로 교체합니다. */
@Component
public class AuthRateLimit extends OncePerRequestFilter {

  private static final java.util.Set<String> MAIL_PATHS = java.util.Set.of(
    "/api/auth/verify/send",
    "/api/auth/reset/send"
  );

  private record Window(long start, int count) {}

  private final ConcurrentHashMap<String, Window> attempts =
    new ConcurrentHashMap<>();

  @Override
  protected void doFilterInternal(
    HttpServletRequest req,
    HttpServletResponse res,
    FilterChain chain
  ) throws ServletException, IOException {
    String path = req.getRequestURI();
    if (
      (req.getMethod().equals("POST") || req.getMethod().equals("DELETE")) &&
      (path.equals("/api/auth/account") ||
        path.equals("/api/auth/login") ||
        path.equals("/api/auth/register") ||
        MAIL_PATHS.contains(path))
    ) {
      long now = System.currentTimeMillis();
      String key = req.getRemoteAddr() + path;
      attempts.entrySet().removeIf(e -> now - e.getValue().start > 60000);
      // 존재하지 않는 임의 /send 경로로 키를 만들지 않고, 실제 인증 경로의 주소별 항목도 제한합니다.
      // 단일 프로세스용 한도이며 새 주소가 몰려 저장 한도에 이르면 발급 대신 잠시 기다리게 합니다.
      if (attempts.size() >= 10000 && !attempts.containsKey(key)) {
        limited(res);
        return;
      }
      Window window = attempts.compute(key, (k, w) ->
        w == null ? new Window(now, 1) : new Window(w.start, w.count + 1)
      );
      // 발송은 분당 3번, 탈퇴 비밀번호 시도는 10번, 로그인은 30번입니다. 회원가입은 개발/통합 테스트를 고려해 20번입니다.
      int limit = path.equals("/api/auth/account")
        ? 10
        : path.endsWith("/send")
          ? 3
          : path.endsWith("/register")
            ? 20
            : 30;
      if (window.count > limit) {
        limited(res);
        return;
      }
    }
    chain.doFilter(req, res);
  }

  private void limited(HttpServletResponse response) throws IOException {
    response.setStatus(429);
    response.setHeader("Retry-After", "60");
    response.setContentType("application/json;charset=UTF-8");
    response
      .getWriter()
      .write("{\"message\":\"요청이 많아요. 1분 후 다시 시도해 주세요.\"}");
  }
}
