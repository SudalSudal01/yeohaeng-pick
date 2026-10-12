package kr.yeohaengpick.community;

import java.util.*;
import kr.yeohaengpick.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공개 글의 생명주기를 담당합니다. 작성자 삭제와 운영진 숨김을 분리하고 댓글·신고·개인 대화를 보존합니다. */
@Service
public class CommunityPostService {

  private final CommunityMapper posts;

  public CommunityPostService(CommunityMapper posts) {
    this.posts = posts;
  }

  public Object trash(String member) {
    return posts.trash(member);
  }

  public Map<String, Object> detail(String id) {
    var p = visible(posts.post(id));
    p.put("comments", posts.comments(id));
    return normalize(p);
  }

  /** SQL DATE의 시간대 변환으로 모집 날짜가 전날로 보이지 않도록 달력 날짜 계약을 유지합니다. */
  private Map<String, Object> normalize(Map<String, Object> source) {
    var p = new HashMap<>(source);
    p.computeIfPresent("recruitmentDate", (k, v) -> v.toString());
    return p;
  }

  private Map<String, Object> visible(Map<String, Object> p) {
    if (
      p == null ||
      Boolean.TRUE.equals(p.get("hidden")) ||
      p.get("deletedAt") != null
    ) throw new DomainException(404, "게시글이 삭제되었거나 공개되지 않아요.");
    return p;
  }

  private Map<String, Object> owned(String id, String member) {
    var p = posts.lockPost(id);
    if (p == null) throw new DomainException(404, "게시글을 찾을 수 없어요.");
    if (!member.equals(p.get("authorId"))) throw new DomainException(
      403,
      "작성자만 이 글을 관리할 수 있어요."
    );
    return p;
  }

  private void version(Map<String, Object> p, int version) {
    if (version < 0) throw DomainException.bad("글 버전을 확인해 주세요.");
    if (((Number) p.get("version")).intValue() != version) throw conflict();
  }

  private DomainException conflict() {
    return new DomainException(
      409,
      "다른 곳에서 글이 변경됐어요. 최신 글을 확인한 뒤 다시 진행해 주세요."
    );
  }

  private Map<String, Object> content(CommunityController.Post p) {
    if (
      !List.of("후기", "추천", "동행", "코스", "의견").contains(p.category())
    ) throw DomainException.bad("게시판을 선택해 주세요.");
    if (
      p.category().equals("동행") &&
      (p.recruitmentDate() == null ||
        p.capacity() == null ||
        p.capacity() < 1 ||
        p.capacity() > 20)
    ) throw DomainException.bad(
      "동행 모집 날짜와 인원(1~20명)을 입력해 주세요."
    );
    var data = new HashMap<String, Object>();
    data.put("category", p.category());
    data.put("region", p.region().trim());
    data.put("title", p.title().trim());
    data.put("body", p.body().trim());
    // 일반 글로 바꿔 보낸 숨은 모집 조건은 저장하지 않습니다. 게시 후 종류 변경도 별도 검사합니다.
    data.put(
      "recruitmentDate",
      p.category().equals("동행") ? p.recruitmentDate() : null
    );
    data.put("capacity", p.category().equals("동행") ? p.capacity() : null);
    return data;
  }

  @Transactional
  public Object create(CommunityController.Post p, String member) {
    var data = content(p);
    String id = UUID.randomUUID().toString();
    data.put("id", id);
    data.put("authorId", member);
    posts.insert(data);
    return detail(id);
  }

  @Transactional
  public Object edit(
    String id,
    CommunityController.Post p,
    int version,
    String member
  ) {
    var current = visible(owned(id, member));
    version(current, version);
    // 이미 달린 댓글과 모집 대화의 맥락을 유지합니다. 다른 종류의 이야기는 새 글로 등록합니다.
    if (
      !current.get("category").equals(p.category())
    ) throw DomainException.bad(
      "등록한 이야기 종류는 바꿀 수 없어요. 새 글로 나눠 주세요."
    );
    var data = content(p);
    data.put("id", id);
    data.put("member", member);
    data.put("version", version);
    if (posts.edit(data) != 1) throw conflict();
    return detail(id);
  }

  @Transactional
  public void delete(String id, int version, String member) {
    var current = visible(owned(id, member));
    version(current, version);
    if (posts.deletePost(id, member, version) != 1) throw conflict();
    // 실제 행은 지우지 않습니다. 공개 상세·댓글 추가는 막고 작성자 휴지통에서 복원할 수 있습니다.
  }

  @Transactional
  public Object restore(String id, int version, String member) {
    var current = owned(id, member);
    version(current, version);
    if (Boolean.TRUE.equals(current.get("hidden"))) throw new DomainException(
      409,
      "운영진이 숨긴 글은 복원할 수 없어요."
    );
    if (current.get("deletedAt") == null) throw new DomainException(
      409,
      "이미 공개 중인 글이에요."
    );
    if (posts.restorePost(id, member, version) != 1) throw conflict();
    return detail(id);
  }

  @Transactional
  public Object status(String id, String status, int version, String member) {
    var current = visible(owned(id, member));
    version(current, version);
    if (!current.get("category").equals("동행")) throw DomainException.bad(
      "동행 모집글만 모집 상태를 변경할 수 있어요."
    );
    if (posts.status(id, member, status, version) != 1) throw conflict();
    return detail(id);
  }

  @Transactional
  public Object comment(String id, String body, String member) {
    visible(posts.lockPost(id));
    posts.comment(UUID.randomUUID().toString(), id, member, body.trim());
    return detail(id);
  }

  /** 운영진 숨김도 같은 행을 먼저 잠급니다. 작성자의 복원이 운영 정책을 우회하지 못하게 합니다. */
  @Transactional
  public void hide(String id, boolean hidden) {
    if (posts.lockPost(id) != null) posts.hide(id, hidden);
  }
}
