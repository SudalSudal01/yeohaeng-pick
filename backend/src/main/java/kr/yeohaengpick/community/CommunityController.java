package kr.yeohaengpick.community;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import kr.yeohaengpick.auth.AuthService;
import kr.yeohaengpick.common.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 후기와 동행 모집을 공통 게시물로 관리하되, 모집글에는 날짜·인원·모집 상태를 추가합니다. */
@RestController
@RequestMapping("/api")
public class CommunityController {

  private final AuthService auth;
  private final CommunityPostService posts;
  private final CommunityCommentService comments;

  public CommunityController(
    AuthService a,
    CommunityPostService posts,
    CommunityCommentService comments
  ) {
    auth = a;
    this.posts = posts;
    this.comments = comments;
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

  public record CommentEdit(
    @NotNull @Min(0) Integer version,
    @NotBlank @Size(max = 2000) String body
  ) {}

  @PutMapping("/community/posts/{id}/comments/{commentId}")
  void editComment(
    @PathVariable String id,
    @PathVariable String commentId,
    @Valid @RequestBody CommentEdit r,
    Authentication a
  ) {
    comments.edit(id, commentId, auth.id(a), r.version, r.body);
  }

  @DeleteMapping("/community/posts/{id}/comments/{commentId}")
  void deleteComment(
    @PathVariable String id,
    @PathVariable String commentId,
    @Valid @RequestBody Version r,
    Authentication a
  ) {
    comments.delete(id, commentId, auth.id(a), r.version);
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
}
