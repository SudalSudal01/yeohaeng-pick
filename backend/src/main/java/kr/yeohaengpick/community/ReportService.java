package kr.yeohaengpick.community;

import java.util.*;
import kr.yeohaengpick.common.*;
import kr.yeohaengpick.messaging.MessageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 접수 당시의 맥락과 운영 처리 기록을 보존합니다. 관리자 접근 권한은 보안 설정에서 별도로 검사합니다. */
@Service
public class ReportService {

  private final ReportMapper reports;
  private final CommunityMapper community;
  private final CommunityPostService posts;
  private final MessageService messages;
  private final JsonCodec json;

  public ReportService(
    ReportMapper reports,
    CommunityMapper community,
    CommunityPostService posts,
    MessageService messages,
    JsonCodec json
  ) {
    this.reports = reports;
    this.community = community;
    this.posts = posts;
    this.messages = messages;
    this.json = json;
  }

  @Transactional
  public void create(String type, String target, String reason, String member) {
    Map<String, Object> evidence;
    switch (type) {
      case "POST" -> {
        // 수정/삭제/숨김과 같은 행을 잠급니다. 접수 뒤 원문이 바뀌어도 이 JSON은 변경하지 않습니다.
        var post = community.lockPost(target);
        if (
          post == null ||
          Boolean.TRUE.equals(post.get("hidden")) ||
          post.get("deletedAt") != null
        ) throw new DomainException(404, "공개 중인 게시글을 찾을 수 없어요.");
        evidence = pick(
          community.post(target),
          "id",
          "authorId",
          "nickname",
          "category",
          "region",
          "title",
          "body",
          "version",
          "createdAt",
          "updatedAt"
        );
      }
      case "MESSAGE" -> evidence = messages.reportEvidence(target, member);
      case "USER" -> {
        var user = reports.publicMember(target);
        if (user == null) throw new DomainException(
          404,
          "신고할 회원을 찾을 수 없어요."
        );
        evidence = pick(user, "id", "nickname");
      }
      default -> throw DomainException.bad("신고 대상을 확인해 주세요.");
    }
    reports.insert(
      Map.of(
        "id",
        UUID.randomUUID().toString(),
        "member",
        member,
        "type",
        type,
        "target",
        target,
        "reason",
        reason.trim(),
        "evidence",
        json.write(evidence)
      )
    );
  }

  /** 값이 추가되더라도 신고 증거에 자동으로 섞이지 않도록 명시한 공개 필드만 복사합니다. */
  public static Map<String, Object> pick(
    Map<String, Object> source,
    String... keys
  ) {
    var result = new LinkedHashMap<String, Object>();
    for (String key : keys) {
      Object value = source.get(key);
      // JDBC 시각/날짜를 JSON의 시간대 변환 없이 당시 표시 값으로 보존합니다.
      if (
        value instanceof java.util.Date ||
        value instanceof java.time.temporal.TemporalAccessor
      ) value = value.toString();
      result.put(key, value);
    }
    return result;
  }

  public Object list() {
    return reports
      .list()
      .stream()
      .map(source -> {
        var result = new LinkedHashMap<>(source);
        String evidence = (String) result.get("evidence");
        // 기존 신고는 당시 내용을 알 수 없습니다. 현재 원문을 증거인 것처럼 소급해서 보여주지 않습니다.
        result.put(
          "evidence",
          evidence == null ? null : json.read(evidence, Map.class)
        );
        return result;
      })
      .toList();
  }

  @Transactional
  public void resolve(
    String id,
    String resolution,
    boolean hidePost,
    String admin
  ) {
    var report = reports.lock(id);
    if (report == null) throw new DomainException(
      404,
      "신고를 찾을 수 없어요."
    );
    if (!"OPEN".equals(report.get("status"))) throw new DomainException(
      409,
      "이미 처리된 신고예요. 최신 처리 결과를 확인해 주세요."
    );
    if (
      hidePost && !"POST".equals(report.get("targetType"))
    ) throw DomainException.bad("게시글 신고에서만 글을 숨길 수 있어요.");
    if (hidePost) posts.hide((String) report.get("targetId"), true);
    if (
      reports.resolve(id, resolution.trim(), admin) != 1
    ) throw new DomainException(409, "이미 처리된 신고예요.");
    // 자세한 사유는 reports에 보존합니다. 최대 200자인 감사 요약에 1,000자 사유를 중복 삽입하지 않습니다.
    reports.audit(
      UUID.randomUUID().toString(),
      admin,
      "신고 " + id + " 처리 / " + (hidePost ? "게시글 숨김" : "추가 숨김 없음")
    );
  }
}
