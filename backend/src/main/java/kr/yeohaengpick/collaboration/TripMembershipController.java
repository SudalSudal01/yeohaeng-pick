package kr.yeohaengpick.collaboration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.yeohaengpick.auth.AuthService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 클라이언트의 역할 표시와 무관하게 서비스가 실제 소유권·현재 참여·버전을 다시 검사합니다. */
@RestController
@RequestMapping("/api/trips/{id}")
public class TripMembershipController {

  private final TripMembershipService memberships;
  private final AuthService auth;

  public TripMembershipController(
    TripMembershipService memberships,
    AuthService auth
  ) {
    this.memberships = memberships;
    this.auth = auth;
  }

  /** 버전 누락을 초기 버전 0으로 처리하면 오래된 화면의 참여 변경을 구분할 수 없어 필수값으로 받습니다. */
  public record Change(@NotNull @Min(0) Integer version) {}

  @PostMapping("/leave")
  void leave(
    @PathVariable String id,
    @Valid @RequestBody Change request,
    Authentication a
  ) {
    memberships.leave(id, request.version(), auth.id(a));
  }

  @PostMapping("/members/{member}/remove")
  Object remove(
    @PathVariable String id,
    @PathVariable String member,
    @Valid @RequestBody Change request,
    Authentication a
  ) {
    return memberships.remove(id, request.version(), member, auth.id(a));
  }
}
