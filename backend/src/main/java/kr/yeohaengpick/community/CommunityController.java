package kr.yeohaengpick.community;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import kr.yeohaengpick.auth.AuthService;
import kr.yeohaengpick.common.*;
import kr.yeohaengpick.messaging.MessageService;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** 후기와 동행 모집을 공통 게시물로 관리하되, 모집글에는 날짜·인원·모집 상태를 추가합니다. */
@RestController
@RequestMapping("/api")
public class CommunityController {

  private final CommunityMapper community;
  private final AuthService auth;
  private final MessageService messages;
  private final CommunityPostService posts;

  public CommunityController(
    CommunityMapper c,
    AuthService a,
    MessageService m,
    CommunityPostService posts
  ) {
    community = c;
    auth = a;
    messages = m;
    this.posts = posts;
  }

  public record Post(
    @NotBlank @Size(max = 30) String category,
    @NotBlank @Size(max = 50) String region,
    @NotBlank @Size(max = 120) String title,
    @NotBlank @Size(max = 10000) String body,
    LocalDate recruitmentDate,
    Integer capacity
  ) {}

  @GetMapping("/community/posts")
  Object posts() {
    return posts.list();
  }

  @GetMapping("/community/posts/{id}")
  Object post(@PathVariable String id) {
    return posts.detail(id);
  }

  @PostMapping("/community/posts")
  Object create(@Valid @RequestBody Post p, Authentication a) {
    return posts.create(p, auth.id(a));
  }

  public record Edit(
    @NotNull @Min(0) Integer version,
    @NotNull @Valid Post content
  ) {}

  public record Version(@NotNull @Min(0) Integer version) {}

  @PutMapping("/community/posts/{id}")
  Object edit(
    @PathVariable String id,
    @Valid @RequestBody Edit r,
    Authentication a
  ) {
    return posts.edit(id, r.content, r.version, auth.id(a));
  }

  @DeleteMapping("/community/posts/{id}")
  void delete(
    @PathVariable String id,
    @Valid @RequestBody Version r,
    Authentication a
  ) {
    posts.delete(id, r.version, auth.id(a));
  }

  @GetMapping("/community/trash")
  Object trash(Authentication a) {
    return posts.trash(auth.id(a));
  }

  @PostMapping("/community/posts/{id}/restore")
  Object restore(
    @PathVariable String id,
    @Valid @RequestBody Version r,
    Authentication a
  ) {
    return posts.restore(id, r.version, auth.id(a));
  }

  public record Comment(@NotBlank @Size(max = 2000) String body) {}

  @PostMapping("/community/posts/{id}/comments")
  Object comment(
    @PathVariable String id,
    @Valid @RequestBody Comment c,
    Authentication a
  ) {
    return posts.comment(id, c.body, auth.id(a));
  }

  public record Status(
    @NotNull @Pattern(regexp = "OPEN|CLOSED|CANCELLED") String status,
    @NotNull @Min(0) Integer version
  ) {}

  @PutMapping("/community/posts/{id}/status")
  Object status(
    @PathVariable String id,
    @Valid @RequestBody Status s,
    Authentication a
  ) {
    return posts.status(id, s.status, s.version, auth.id(a));
  }

  public record Report(
    @Pattern(regexp = "POST|MESSAGE|USER") String type,
    @NotBlank String targetId,
    @NotBlank @Size(max = 1000) String reason
  ) {}

  @PostMapping("/reports")
  Object report(@Valid @RequestBody Report r, Authentication a) {
    String member = auth.id(a);
    if (r.type.equals("MESSAGE")) messages.requireMessageAccess(
      r.targetId,
      member
    );
    community.report(
      UUID.randomUUID().toString(),
      member,
      r.type,
      r.targetId,
      r.reason
    );
    return Map.of("message", "신고를 접수했어요.");
  }

  @GetMapping("/admin/reports")
  Object reports() {
    return community
      .reports()
      .stream()
      .map(r -> {
        // 신고된 메시지 하나만 열람합니다. 관리자에게 전체 개인 대화 목록을 제공하지 않습니다.
        if (r.get("targetType").equals("MESSAGE")) r.put(
          "reportedMessage",
          messages.reportedMessage((String) r.get("targetId"))
        );
        return r;
      })
      .toList();
  }

  public record Resolution(
    @NotBlank @Size(max = 1000) String resolution,
    boolean hidePost
  ) {}

  @PostMapping("/admin/reports/{id}/resolve")
  @Transactional
  Object resolve(
    @PathVariable String id,
    @Valid @RequestBody Resolution r,
    Authentication a
  ) {
    var report = community.reportById(id);
    if (report == null) throw new DomainException(
      404,
      "신고를 찾을 수 없어요."
    );
    if (r.hidePost && report.get("targetType").equals("POST")) posts.hide(
      (String) report.get("targetId"),
      true
    );
    community.resolve(id, r.resolution);
    community.audit(
      UUID.randomUUID().toString(),
      auth.id(a),
      "신고 " + id + " 처리: " + r.resolution
    );
    return Map.of("message", "처리 결과를 저장했어요.");
  }
}
