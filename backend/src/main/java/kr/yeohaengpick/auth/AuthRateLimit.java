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
      req.getMethod().equals("POST") &&
      (path.equals("/api/auth/login") ||
        path.equals("/api/auth/register") ||
        path.endsWith("/send"))
    ) {
      long now = System.currentTimeMillis();
      String key = req.getRemoteAddr() + path;
      attempts.entrySet().removeIf(e -> now - e.getValue().start > 60000);
      Window window = attempts.compute(key, (k, w) ->
        w == null ? new Window(now, 1) : new Window(w.start, w.count + 1)
      );
      // 발송은 분당 3번, 로그인은 분당 30번. 회원가입은 개발/통합 테스트를 고려해 분당 20번입니다.
      int limit = path.endsWith("/send")
        ? 3
        : path.endsWith("/register")
          ? 20
          : 30;
      if (window.count > limit) {
        res.setStatus(429);
        res.setHeader("Retry-After", "60");
        res.setContentType("application/json;charset=UTF-8");
        res
          .getWriter()
          .write("{\"message\":\"요청이 많아요. 1분 후 다시 시도해 주세요.\"}");
        return;
      }
    }
    chain.doFilter(req, res);
  }
}
