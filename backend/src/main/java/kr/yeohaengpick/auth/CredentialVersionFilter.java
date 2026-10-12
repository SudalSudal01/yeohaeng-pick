package kr.yeohaengpick.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Objects;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 비밀번호 변경·탈퇴 후 이전 브라우저 세션을 다음 요청에서 무효화합니다. 비밀번호 원문은 세션에 저장하지 않습니다. */
public class CredentialVersionFilter extends OncePerRequestFilter {

  private final MemberMapper members;
  private final AccountLifecycle lifecycle;

  public CredentialVersionFilter(
    MemberMapper mapper,
    AccountLifecycle lifecycle
  ) {
    members = mapper;
    this.lifecycle = lifecycle;
  }

  @Override
  protected void doFilterInternal(
    HttpServletRequest request,
    HttpServletResponse response,
    FilterChain chain
  ) throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (
      auth != null &&
      auth.isAuthenticated() &&
      !auth.getName().equals("anonymousUser")
    ) {
      // 탈퇴는 이미 시작한 로그인 회원의 요청이 모두 커밋된 다음 실행합니다.
      // 검증도 잠금 안에서 다시 하므로 기다리던 오래된 세션이 새 자료를 만들 수 없습니다.
      var lock = lifecycle.requestLock(
        request.getMethod().equals("DELETE") &&
          request.getRequestURI().equals("/api/auth/account")
      );
      lock.lock();
      try {
        var member = members.byId(auth.getName());
        var session = request.getSession(false);
        if (
          !MemberMapper.active(member) ||
          session == null ||
          !Objects.equals(
            session.getAttribute("credentialVersion"),
            member.get("credentialVersion")
          )
        ) {
          if (session != null) session.invalidate();
          SecurityContextHolder.clearContext();
          response.setStatus(401);
          response.setContentType("application/json;charset=UTF-8");
          response
            .getWriter()
            .write(
              "{\"message\":\"인증 정보가 변경됐어요. 다시 로그인해 주세요.\"}"
            );
          return;
        }
        chain.doFilter(request, response);
      } finally {
        lock.unlock();
      }
      return;
    }
    chain.doFilter(request, response);
  }
}
