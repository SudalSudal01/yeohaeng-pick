package kr.yeohaengpick.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Objects;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 비밀번호가 바뀌면 이전 브라우저의 세션도 다음 요청에서 무효화합니다. 비밀번호 원문은 세션에 저장하지 않습니다. */
public class CredentialVersionFilter extends OncePerRequestFilter {

  private final MemberMapper members;

  public CredentialVersionFilter(MemberMapper mapper) {
    members = mapper;
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
      var member = members.byId(auth.getName());
      var session = request.getSession(false);
      if (
        member == null ||
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
    }
    chain.doFilter(request, response);
  }
}
