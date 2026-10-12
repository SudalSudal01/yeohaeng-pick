package kr.yeohaengpick.community;

import kr.yeohaengpick.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 댓글 작성자만 내용을 관리합니다. 게시글 작성 권한은 댓글 관리 권한으로 확장되지 않습니다. */
@Service
public class CommunityCommentService {

  private final CommunityMapper community;

  public CommunityCommentService(CommunityMapper community) {
    this.community = community;
  }

  /**
   * 생성·게시글 삭제·운영진 숨김과 같은 부모 행을 먼저 잠가 처리 순서를 보장합니다.
   * 댓글 버전은 게시글과 독립적이므로 다른 댓글 등록이나 글 수정만으로 초안이 충돌하지 않습니다.
   */
  private void requireOwner(
    String post,
    String id,
    String member,
    int version
  ) {
    var parent = community.lockPost(post);
    if (
      parent == null ||
      Boolean.TRUE.equals(parent.get("hidden")) ||
      parent.get("deletedAt") != null
    ) throw new DomainException(404, "게시글이 삭제되었거나 공개되지 않아요.");
    var comment = community.commentById(post, id);
    if (
      comment == null || comment.get("deletedAt") != null
    ) throw new DomainException(404, "댓글이 삭제되었거나 찾을 수 없어요.");
    if (!member.equals(comment.get("authorId"))) throw new DomainException(
      403,
      "댓글 작성자만 관리할 수 있어요."
    );
    if (
      ((Number) comment.get("version")).intValue() != version
    ) throw conflict();
  }

  private DomainException conflict() {
    return new DomainException(
      409,
      "다른 곳에서 댓글이 변경됐어요. 최신 댓글을 확인한 뒤 다시 진행해 주세요."
    );
  }

  @Transactional
  public void edit(
    String post,
    String id,
    String member,
    int version,
    String body
  ) {
    requireOwner(post, id, member, version);
    if (
      community.editComment(post, id, member, version, body.trim()) != 1
    ) throw conflict();
  }

  @Transactional
  public void delete(String post, String id, String member, int version) {
    requireOwner(post, id, member, version);
    if (
      community.deleteComment(post, id, member, version) != 1
    ) throw conflict();
  }
}
