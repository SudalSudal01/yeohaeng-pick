-- Oracle 19c 전용. 공통 마이그레이션과 같은 알림 계약을 VARCHAR2와 NUMBER 호환 범위로 유지합니다.
CREATE TABLE member_notifications (
  id VARCHAR2(36 CHAR) PRIMARY KEY,
  recipient_id VARCHAR2(36 CHAR) NOT NULL REFERENCES members(id),
  actor_id VARCHAR2(36 CHAR) NOT NULL REFERENCES members(id),
  trip_id VARCHAR2(36 CHAR) NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
  poll_id VARCHAR2(36 CHAR) NOT NULL REFERENCES trip_polls(id) ON DELETE CASCADE,
  event_type VARCHAR2(30 CHAR) NOT NULL CHECK (event_type IN ('TRIP_POLL_CREATED','TRIP_POLL_CLOSED')),
  read_at TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  UNIQUE (recipient_id, event_type, poll_id)
);

CREATE INDEX idx_member_noti_list ON member_notifications(recipient_id, created_at, id);
CREATE INDEX idx_member_noti_unread ON member_notifications(recipient_id, read_at);
-- 부모 회원 익명화와 여행·투표 cascade 때 자식 테이블 전체 잠금·탐색을 피하도록
-- recipient를 포함한 유니크 인덱스 외 나머지 FK도 선두 컬럼 인덱스를 둡니다.
CREATE INDEX idx_member_noti_actor ON member_notifications(actor_id);
CREATE INDEX idx_member_noti_trip ON member_notifications(trip_id, recipient_id);
CREATE INDEX idx_member_noti_poll ON member_notifications(poll_id);

COMMENT ON TABLE member_notifications IS '로그인 회원이 앱 안에서 확인하는 비공개 알림. 현재는 동행 투표 생성·수동 마감만 저장하며 여행·투표 삭제 시 함께 제거';
COMMENT ON COLUMN member_notifications.id IS '서버가 발급한 UUID 알림 식별자. API 단건 읽음 처리에 사용';
COMMENT ON COLUMN member_notifications.recipient_id IS '알림을 받는 회원 ID. 개인정보인 내부 식별자로 응답에 노출하지 않으며 참여 종료·탈퇴 시 해당 수신 알림 삭제';
COMMENT ON COLUMN member_notifications.actor_id IS '알림을 발생시킨 회원 ID. 내부 참조만 저장하고 API에는 현재 닉네임만 제공하며 탈퇴 시 익명화된 닉네임으로 표시';
COMMENT ON COLUMN member_notifications.trip_id IS '알림이 속한 비공개 여행 ID. 현재 참여 권한을 조회 때 다시 확인하고 여행 삭제 시 알림도 삭제';
COMMENT ON COLUMN member_notifications.poll_id IS '알림의 대상 투표 ID. 투표 상세로 이동하는 식별자이며 투표 삭제 시 알림도 삭제';
COMMENT ON COLUMN member_notifications.event_type IS '알림 종류. TRIP_POLL_CREATED=투표 생성, TRIP_POLL_CLOSED=사용자에 의한 수동 마감';
COMMENT ON COLUMN member_notifications.read_at IS '수신자가 알림을 처음 읽은 DB 현지 시각. NULL이면 아직 읽지 않은 상태';
COMMENT ON COLUMN member_notifications.created_at IS '알림이 생성된 DB 현지 시각. 투표 작업과 같은 트랜잭션에서 저장';
