package kr.yeohaengpick.auth;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/** 세션 + HttpOnly 쿠키 + CSRF를 적용합니다. 브라우저 로컬스토리지에 인증 토큰을 저장하지 않습니다. */
@Configuration
public class SecurityConfig {

  // 사용자 인증은 AuthService가 DB와 BCrypt로 처리합니다. 기본 임시 계정의 자동 생성을 막습니다.
  @Bean
  org.springframework.security.core.userdetails.UserDetailsService userDetailsService() {
    return username -> {
      throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
        "세션 로그인 API를 사용해 주세요."
      );
    };
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  HttpSessionSecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http, MemberMapper members)
    throws Exception {
    return http
      .addFilterAfter(
        new CredentialVersionFilter(members),
        org.springframework.security.web.context.SecurityContextHolderFilter.class
      )
      .csrf(c ->
        c
          .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
          .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
      )
      .securityContext(c ->
        c.securityContextRepository(securityContextRepository())
      )
      .authorizeHttpRequests(a ->
        a
          .requestMatchers(
            "/api/auth/**",
            "/api/config",
            "/api/catalog/**",
            "/api/share/**"
          )
          .permitAll()
          .requestMatchers(
            org.springframework.http.HttpMethod.GET,
            "/api/community/**"
          )
          .permitAll()
          .requestMatchers("/api/admin/**")
          .hasRole("ADMIN")
          .anyRequest()
          .authenticated()
      )
      .exceptionHandling(e ->
        e
          .authenticationEntryPoint((req, res, err) -> {
            res.setStatus(401);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"message\":\"로그인 후 이용해 주세요.\"}");
          })
          .accessDeniedHandler((req, res, err) -> {
            res.setStatus(403);
            res.setContentType("application/json;charset=UTF-8");
            res
              .getWriter()
              .write(
                "{\"message\":\"접근 권한이 없거나 보안 토큰이 만료됐어요. 새로고침 후 다시 시도해 주세요.\"}"
              );
          })
      )
      .logout(l ->
        l
          .logoutUrl("/api/auth/logout")
          .invalidateHttpSession(true)
          .deleteCookies("JSESSIONID")
          .logoutSuccessHandler((req, res, auth) -> res.setStatus(204))
      )
      .build();
  }
}
