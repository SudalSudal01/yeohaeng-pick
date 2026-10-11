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

  public CommunityController(
    CommunityMapper c,
    AuthService a,
    MessageService m
  ) {
    community = c;
    auth = a;
    messages = m;
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
    return community.posts();
  }

  @GetMapping("/community/posts/{id}")
  Object post(@PathVariable String id) {
    var p = community.post(id);
    if (
      p == null || Boolean.TRUE.equals(p.get("hidden"))
    ) throw new DomainException(404, "게시글을 찾을 수 없어요.");
    p.put("comments", community.comments(id));
    return p;
  }

  @PostMapping("/community/posts")
  Object create(@Valid @RequestBody Post p, Authentication a) {
    if (
      !List.of("후기", "추천", "동행", "코스", "의견").contains(p.category)
    ) throw DomainException.bad("게시판을 선택해 주세요.");
    if (
      p.category.equals("동행") &&
      (p.recruitmentDate == null ||
        p.capacity == null ||
        p.capacity < 1 ||
        p.capacity > 20)
    ) throw DomainException.bad(
      "동행 모집 날짜와 인원(1~20명)을 입력해 주세요."
    );
    var data = new HashMap<String, Object>();
    String id = UUID.randomUUID().toString();
    data.put("id", id);
    data.put("authorId", auth.id(a));
    data.put("category", p.category);
    data.put("region", p.region);
    data.put("title", p.title.trim());
    data.put("body", p.body.trim());
    data.put("recruitmentDate", p.recruitmentDate);
    data.put("capacity", p.capacity);
    community.insert(data);
    return post(id);
  }

  public record Comment(@NotBlank @Size(max = 2000) String body) {}

  @PostMapping("/community/posts/{id}/comments")
  Object comment(
    @PathVariable String id,
    @Valid @RequestBody Comment c,
    Authentication a
  ) {
    post(id);
    community.comment(
      UUID.randomUUID().toString(),
      id,
      auth.id(a),
      c.body.trim()
    );
    return post(id);
  }

  public record Status(
    @Pattern(regexp = "OPEN|CLOSED|CANCELLED") String status
  ) {}

  @PutMapping("/community/posts/{id}/status")
  Object status(
    @PathVariable String id,
    @Valid @RequestBody Status s,
    Authentication a
  ) {
    if (
      community.status(id, auth.id(a), s.status) != 1
    ) throw new DomainException(403, "작성자만 모집 상태를 변경할 수 있어요.");
    return post(id);
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
    if (r.hidePost && report.get("targetType").equals("POST")) community.hide(
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
