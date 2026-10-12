-- 여행 투표는 비공개 공동 의사결정 기록입니다. 방문 일정 version이나 공개 공유 데이터에 섞지 않습니다.
CREATE TABLE trip_polls (
  id VARCHAR(36) PRIMARY KEY,
  trip_id VARCHAR(36) NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
  created_by VARCHAR(36) NOT NULL REFERENCES members(id),
  title VARCHAR(120) NOT NULL,
  selection_type VARCHAR(10) NOT NULL CHECK (selection_type IN ('SINGLE','MULTIPLE')),
  status VARCHAR(10) DEFAULT 'OPEN' NOT NULL CHECK (status IN ('OPEN','CLOSED')),
  closes_at TIMESTAMP,
  closed_at TIMESTAMP,
  version INTEGER DEFAULT 0 NOT NULL CHECK (version >= 0),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CHECK (
    (status = 'OPEN' AND closed_at IS NULL) OR
    (status = 'CLOSED' AND closed_at IS NOT NULL)
  )
);

CREATE TABLE trip_poll_options (
  poll_id VARCHAR(36) NOT NULL REFERENCES trip_polls(id) ON DELETE CASCADE,
  id VARCHAR(36) NOT NULL,
  label VARCHAR(100) NOT NULL,
  sort_order INTEGER NOT NULL CHECK (sort_order BETWEEN 0 AND 9),
  PRIMARY KEY (poll_id, id),
  UNIQUE (poll_id, sort_order)
);

CREATE TABLE trip_poll_votes (
  poll_id VARCHAR(36) NOT NULL,
  option_id VARCHAR(36) NOT NULL,
  member_id VARCHAR(36) NOT NULL REFERENCES members(id),
  selected_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  PRIMARY KEY (poll_id, member_id, option_id),
  FOREIGN KEY (poll_id, option_id) REFERENCES trip_poll_options(poll_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_trip_polls_trip ON trip_polls(trip_id, created_at);
CREATE INDEX idx_poll_votes_member ON trip_poll_votes(member_id);

COMMENT ON TABLE trip_polls IS '현재 여행 참여자가 함께 결정하는 비공개 투표. 공개 공유·일정 가져오기·방문 일정 복원 대상에서 제외';
COMMENT ON COLUMN trip_polls.id IS '서버가 발급한 UUID 투표 식별자';
COMMENT ON COLUMN trip_polls.trip_id IS '투표가 속한 비공개 여행 ID. 여행 삭제 시 후보·표와 함께 제거';
COMMENT ON COLUMN trip_polls.created_by IS '투표를 만든 회원 ID. 참여 종료 뒤에도 투표는 남고 현재 여행 소유자가 관리 가능';
COMMENT ON COLUMN trip_polls.title IS '참여자에게 보여 주는 투표 질문, 최대 120자';
COMMENT ON COLUMN trip_polls.selection_type IS '선택 방식 SINGLE=한 후보, MULTIPLE=여러 후보. 빈 선택은 본인 투표 취소';
COMMENT ON COLUMN trip_polls.status IS '저장 상태 OPEN=진행, CLOSED=수동 마감. 기한 종료 EXPIRED는 closes_at과 애플리케이션의 한국 현지 시각으로 계산';
COMMENT ON COLUMN trip_polls.closes_at IS '선택한 자동 마감 한국 현지 시각. NULL이면 자동 마감 없이 수동으로만 마감';
COMMENT ON COLUMN trip_polls.closed_at IS '수동 마감 처리 시각. NULL이면 수동 마감하지 않았으며 기한 종료 여부는 closes_at으로 판단';
COMMENT ON COLUMN trip_polls.version IS '마감·삭제 충돌을 확인하는 투표 생명주기 버전. 개별 참여자의 표 변경으로는 증가하지 않음';
COMMENT ON COLUMN trip_polls.created_at IS '투표를 만든 DB 현지 시각. 타임존 오프셋은 별도 저장하지 않음';

COMMENT ON TABLE trip_poll_options IS '투표에 표시하는 사용자 입력 후보. 후보 자체가 가격·예약·영업 가능성을 확정하지 않음';
COMMENT ON COLUMN trip_poll_options.poll_id IS '후보가 속한 투표 ID. 투표 삭제 시 함께 제거';
COMMENT ON COLUMN trip_poll_options.id IS '서버가 발급한 UUID 후보 식별자. poll_id와 함께 표의 참조 범위를 제한';
COMMENT ON COLUMN trip_poll_options.label IS '참여자에게 보여 주는 후보 이름, 최대 100자';
COMMENT ON COLUMN trip_poll_options.sort_order IS '투표 안의 표시 순서, 0부터 9까지. 생성 후 순서를 변경하지 않음';

COMMENT ON TABLE trip_poll_votes IS '현재 여행 참여자의 후보 선택. API는 후보별 합계와 본인 선택만 제공하고 다른 회원의 개별 선택은 공개하지 않음';
COMMENT ON COLUMN trip_poll_votes.poll_id IS '선택한 투표 ID. option_id와 함께 같은 투표의 실제 후보만 참조';
COMMENT ON COLUMN trip_poll_votes.option_id IS '선택한 후보 ID. SINGLE은 서비스에서 한 개, MULTIPLE은 여러 행을 허용';
COMMENT ON COLUMN trip_poll_votes.member_id IS '선택한 회원 ID. 여행 참여 종료·회원 탈퇴 시 삭제되어 재참여 후 과거 표가 부활하지 않음';
COMMENT ON COLUMN trip_poll_votes.selected_at IS '현재 선택을 저장한 DB 현지 시각. 재투표는 이전 선택 전체를 지우고 새 시각으로 저장';
