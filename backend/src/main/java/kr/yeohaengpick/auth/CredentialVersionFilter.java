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
        // 이 헤더는 인증 수단이 아닙니다. 다른 탭에서 로그인 쿠키가 바뀌었을 때 옛 화면의 입력이
        // 새 회원 데이터에 저장되지 않도록, 서버가 인증한 주체와 호출 화면의 기대 주체만 대조합니다.
        String expectedMember = request.getHeader("X-Travel-Member");
        if (expectedMember != null && !expectedMember.equals(auth.getName())) {
          response.setStatus(409);
          response.setHeader("X-Travel-Account-Changed", "true");
          response.setContentType("application/json;charset=UTF-8");
          response
            .getWriter()
            .write(
              "{\"message\":\"로그인 계정이 바뀌었어요. 현재 화면을 다시 확인해 주세요.\",\"code\":\"ACCOUNT_CHANGED\"}"
            );
          return;
        }
        var member = members.byId(auth.getName());
        var session = request.getSession(false);
        Object credentialVersion = null;
        if (session != null) try {
          credentialVersion = session.getAttribute("credentialVersion");
        } catch (IllegalStateException expired) {
          session = null;
        }
        if (
          !MemberMapper.active(member) ||
          session == null ||
          !Objects.equals(credentialVersion, member.get("credentialVersion"))
        ) {
          if (session != null) try {
            session.invalidate();
          } catch (IllegalStateException alreadyExpired) {
            /* 다른 인증 요청이 먼저 종료한 세션입니다. */
          }
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
