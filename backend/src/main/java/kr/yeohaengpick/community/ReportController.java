package kr.yeohaengpick.community;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import kr.yeohaengpick.auth.AuthService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 일반 회원은 접수만 가능하고, /admin 경로는 서버 보안 설정에서 관리자만 허용합니다. */
@RestController
@RequestMapping("/api")
public class ReportController {

  private final ReportService reports;
  private final AuthService auth;

  public ReportController(ReportService reports, AuthService auth) {
    this.reports = reports;
    this.auth = auth;
  }

  public record Report(
    @NotNull @Pattern(regexp = "POST|MESSAGE|USER") String type,
    @NotBlank @Size(max = 36) String targetId,
    @NotBlank @Size(max = 1000) String reason
  ) {}

  @PostMapping("/reports")
  Object report(@Valid @RequestBody Report r, Authentication a) {
    reports.create(r.type, r.targetId, r.reason, auth.id(a));
    return Map.of("message", "신고를 접수했어요.");
  }

  @GetMapping("/admin/reports")
  Object list() {
    return reports.list();
  }

  public record Resolution(
    @NotBlank @Size(max = 1000) String resolution,
    boolean hidePost
  ) {}

  @PostMapping("/admin/reports/{id}/resolve")
  Object resolve(
    @PathVariable String id,
    @Valid @RequestBody Resolution r,
    Authentication a
  ) {
    reports.resolve(id, r.resolution, r.hidePost, auth.id(a));
    return Map.of("message", "처리 결과를 저장했어요.");
  }
}
