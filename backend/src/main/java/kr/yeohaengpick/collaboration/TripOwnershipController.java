package kr.yeohaengpick.collaboration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.yeohaengpick.auth.AuthService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 버전 누락은 0으로 처리하지 않습니다. 요청 권한·참여 상태는 서비스에서 서버 기준으로 다시 검사합니다. */
@RestController
@RequestMapping("/api/trips/{id}/ownership")
public class TripOwnershipController {

  private final TripOwnershipService ownership;
  private final AuthService auth;

  public TripOwnershipController(
    TripOwnershipService ownership,
    AuthService auth
  ) {
    this.ownership = ownership;
    this.auth = auth;
  }

  public record Transfer(
    @NotNull @Min(0) Integer version,
    @NotBlank @Size(max = 36) String memberId
  ) {}

  @PostMapping("/transfer")
  Object transfer(
    @PathVariable String id,
    @Valid @RequestBody Transfer r,
    Authentication a
  ) {
    return ownership.transfer(id, r.memberId(), r.version(), auth.id(a));
  }
}
