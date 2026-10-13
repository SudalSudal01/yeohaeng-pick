package kr.yeohaengpick.collaboration;

import kr.yeohaengpick.common.DomainException;
import kr.yeohaengpick.notification.NotificationService;
import kr.yeohaengpick.trip.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 여행 참여만 해제합니다. 회원 탈퇴·개인 차단·외부 예약 취소·경비 정산과 혼동하지 않습니다. */
@Service
public class TripMembershipService {

  private final TripService trips;
  private final TripMapper mapper;
  private final InvitationMapper invites;
  private final TripPollMapper polls;
  private final NotificationService notifications;

  public TripMembershipService(
    TripService trips,
    TripMapper mapper,
    InvitationMapper invites,
    TripPollMapper polls,
    NotificationService notifications
  ) {
    this.trips = trips;
    this.mapper = mapper;
    this.invites = invites;
    this.polls = polls;
    this.notifications = notifications;
  }

  /** 소유자는 여행을 남겨둔 채 나갈 수 없습니다. 소유권 이전은 별도 기능으로 다룹니다. */
  @Transactional
  public void leave(String id, int version, String member) {
    change(id, version, member, member, false);
  }

  @Transactional
  public Object remove(String id, int version, String target, String member) {
    change(id, version, target, member, true);
    return trips.detail(id, member);
  }

  private void change(
    String id,
    int version,
    String target,
    String member,
    boolean removal
  ) {
    var trip = trips.access(id, member);
    String owner = trip.get("ownerId").toString();
    if (removal && !owner.equals(member)) throw new DomainException(
      403,
      "여행 소유자만 동행의 참여를 해제할 수 있어요."
    );
    if (owner.equals(target)) throw new DomainException(
      409,
      "여행 소유자의 참여는 해제할 수 없어요."
    );
    if (mapper.access(id, target) == 0) throw new DomainException(
      404,
      "현재 참여 중인 동행을 찾을 수 없어요."
    );
    if (version < 0) throw DomainException.bad("일정 버전을 확인해 주세요.");
    if (mapper.bump(id, version) != 1) throw new DomainException(
      409,
      "여행이 변경됐어요. 최신 참여자를 확인한 뒤 다시 진행해 주세요."
    );
    // 투표는 현재 함께 결정하는 사람의 선택입니다. 표를 먼저 지워 새 초대로 돌아와도
    // 과거 선택이 자동으로 되살아나지 않게 하며, 질문과 다른 참여자의 표는 공동 기록으로 남깁니다.
    polls.clearMemberVotes(id, target);
    // 알림도 참여 관계에 딸린 개인 받은 편지함입니다. 참여 행을 지우기 전에 정리해
    // 이후 같은 여행에 다시 초대되어도 과거 알림이 미확인으로 되살아나지 않게 합니다.
    notifications.clearTripRecipient(id, target);
    mapper.removeMember(id, target);
    invites.revoke(id, target);
    // 인원은 실제 여행 인원, 참여자는 편집 계정입니다. 인원·취향·예약·경비·이력과 공개 링크는 유지합니다.
    // 단체 대화는 매 요청의 현재 trip_members를 검사하므로 메시지를 삭제하거나 대화방을 재생성하지 않습니다.
  }
}
