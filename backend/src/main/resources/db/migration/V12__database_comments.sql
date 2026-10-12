-- SQL Developer의 테이블/컬럼 설명에서 읽을 수 있는 한국어 DB 메타데이터입니다.
-- 기존 데이터와 적용된 V1~V11의 체크섬은 변경하지 않습니다. 시각은 현재 서비스/DB의 현지 시각 기준입니다.

COMMENT ON TABLE trip_ownership_history IS '여행 소유권 이전 이력. 방문 일정 복원과 분리하여 권한 변경 추적';
COMMENT ON COLUMN trip_ownership_history.id IS '서버가 발급한 UUID 식별자';
COMMENT ON COLUMN trip_ownership_history.trip_id IS '소유권을 이전한 여행 ID';
COMMENT ON COLUMN trip_ownership_history.previous_owner_id IS '이전 소유자 회원 ID. 이전 후 동행으로 유지';
COMMENT ON COLUMN trip_ownership_history.new_owner_id IS '이전 시 수락한 참여자인 새 소유자 회원 ID';
COMMENT ON COLUMN trip_ownership_history.version IS '소유권 이전 완료 후 여행 버전';
COMMENT ON COLUMN trip_ownership_history.revoked_invitations IS '이전 과정에서 종료한 대기 초대 수';
COMMENT ON COLUMN trip_ownership_history.created_at IS '행을 생성한 DB 현지 시각. 타임존 오프셋은 별도 저장하지 않음';

COMMENT ON TABLE members IS '회원 계정과 기본 여행 취향. 탈퇴 후 공동 기록의 외래키 연결용 비식별 행은 유지';
COMMENT ON COLUMN members.deleted_at IS '탈퇴 완료 시각. NULL이면 활동 계정이며 비NULL이면 로그인과 복구 차단';
COMMENT ON COLUMN members.id IS '서버가 발급한 UUID 식별자';
COMMENT ON COLUMN members.email IS '로그인 이메일. 소문자로 정규화하며 탈퇴 시 재로그인 불가능한 임의 식별자로 대체';
COMMENT ON COLUMN members.password_hash IS 'BCrypt 비밀번호 해시. 평문 비밀번호는 저장하지 않음';
COMMENT ON COLUMN members.nickname IS '화면에 공개되는 닉네임. 탈퇴 시 탈퇴 회원으로 대체';
COMMENT ON COLUMN members.role IS '서버 권한 USER 또는 ADMIN. 클라이언트가 직접 지정할 수 없음';
COMMENT ON COLUMN members.verified IS '이메일 인증 여부. Oracle 0=미인증, 1=인증';
COMMENT ON COLUMN members.preferences IS '기본 여행 취향 문자열 목록 JSON. 개별 여행에서는 변경 가능';
COMMENT ON COLUMN members.dm_scope IS '새 개인 대화 수신 범위 ALL=전체, COMPANIONS=동행, OFF=받지 않음';
COMMENT ON COLUMN members.created_at IS '행을 생성한 DB 현지 시각. 타임존 오프셋은 별도 저장하지 않음';
COMMENT ON COLUMN members.credential_version IS '비밀번호 변경/탈퇴 시 증가. 기존 로그인 세션 무효화 비교 기준';

COMMENT ON TABLE email_tokens IS '이메일 인증/비밀번호 재설정 일회용 토큰. 만료와 소모 여부는 서버에서 검사';
COMMENT ON COLUMN email_tokens.token_hash IS '메일 링크의 임의 토큰을 SHA-256으로 해시한 키. 원본 토큰은 저장하지 않음';
COMMENT ON COLUMN email_tokens.member_id IS '인증 또는 재설정 대상 회원 ID';
COMMENT ON COLUMN email_tokens.purpose IS '토큰 용도 VERIFY=이메일 인증, RESET=비밀번호 재설정';
COMMENT ON COLUMN email_tokens.expires_at IS '토큰 유효 기한. 현재 시각 이후인 토큰만 한 번 소모 가능';

COMMENT ON TABLE trips IS '회원이 저장한 비공개 여행 계획. 참여자 권한과 별도의 공개 링크로 접근 범위 구분';
COMMENT ON COLUMN trips.id IS '서버가 발급한 UUID 식별자';
COMMENT ON COLUMN trips.owner_id IS '현재 여행 소유자 회원 ID. 소유권 이전 시 변경';
COMMENT ON COLUMN trips.title IS '여행 제목';
COMMENT ON COLUMN trips.regions IS '방문 지역명 순서 목록 JSON';
COMMENT ON COLUMN trips.start_date IS '여행 시작 달력 날짜. 시간대 변환 대상 아님';
COMMENT ON COLUMN trips.end_date IS '여행 마지막 달력 날짜. 당일치기는 시작 날짜와 같음';
COMMENT ON COLUMN trips.origin IS '사용자가 입력한 출발 지역/장소. 공개 공유에서 제외';
COMMENT ON COLUMN trips.party_size IS '계획 기준 여행 인원 수. 현재 참여 계정 수와 별개';
COMMENT ON COLUMN trips.budget IS '여행 전체 참고 예산, 원 단위. 초과 대안도 장점과 함께 비교 가능';
COMMENT ON COLUMN trips.transport IS '추천 기준 이동 수단 코드. 실제 운행/좌석/예약 보장 아님';
COMMENT ON COLUMN trips.preferences IS '이 여행에 적용한 취향 목록 JSON. 계정 기본 취향과 분리';
COMMENT ON COLUMN trips.lodging IS '계획 생성 시 입력한 숙소 설명. 미입력은 빈 값이며 실제 선택 숙소는 trip_lodgings에 보관';
COMMENT ON COLUMN trips.version IS '공동 편집 충돌 검사 버전. 변경 시 증가';
COMMENT ON COLUMN trips.share_token IS '공개 일정 접근용 임의 토큰. NULL이면 공유 종료. 비공개 여행 ID와 분리';
COMMENT ON COLUMN trips.created_at IS '행을 생성한 DB 현지 시각. 타임존 오프셋은 별도 저장하지 않음';

COMMENT ON TABLE trip_members IS '현재 여행 참여 권한. 초대를 수락한 계정만 추가하며 나가기/참여 해제 시 제거';
COMMENT ON COLUMN trip_members.trip_id IS '참여 대상 여행 ID. 여행 삭제 시 함께 제거';
COMMENT ON COLUMN trip_members.member_id IS '여행에 접근할 수 있는 회원 ID. 소유자도 참여 행 유지';

COMMENT ON TABLE itinerary_items IS '날짜별 방문 장소와 시간, 직접 확인한 조건. 공동 편집 시 항목이 재삽입될 수 있음';
COMMENT ON COLUMN itinerary_items.id IS '서버가 발급한 UUID 식별자';
COMMENT ON COLUMN itinerary_items.trip_id IS '이 항목이 속한 여행 ID';
COMMENT ON COLUMN itinerary_items.day_index IS '여행 시작일부터의 일차 인덱스, 0부터 시작';
COMMENT ON COLUMN itinerary_items.sort_order IS '같은 날짜 안에서 방문하는 순서';
COMMENT ON COLUMN itinerary_items.place_id IS '공급자가 반환한 실제 장소 ID. AI가 임의 생성하지 않음';
COMMENT ON COLUMN itinerary_items.place_name IS '조회 당시 장소 이름';
COMMENT ON COLUMN itinerary_items.region IS '방문 지역명';
COMMENT ON COLUMN itinerary_items.category IS '여행용 장소 분류';
COMMENT ON COLUMN itinerary_items.latitude IS '장소 위도, 십진 도 단위';
COMMENT ON COLUMN itinerary_items.longitude IS '장소 경도, 십진 도 단위';
COMMENT ON COLUMN itinerary_items.start_time IS '방문 시작 현지 시각 HH:mm. 날짜는 day_index로 계산';
COMMENT ON COLUMN itinerary_items.duration IS '예정 방문 체류 시간, 분 단위';
COMMENT ON COLUMN itinerary_items.cost IS '추천 기준 1인당 참고 활동비, 원 단위. 확정 가격 아님';
COMMENT ON COLUMN itinerary_items.indoor IS '실내 여부. Oracle 0=실외, 1=실내';
COMMENT ON COLUMN itinerary_items.source IS '장소 정보 출처 코드. 샘플/실제 공급자 구분';
COMMENT ON COLUMN itinerary_items.place_url IS '장소 정보/외부 예약 확인 URL. 빈 값은 링크 미제공';
COMMENT ON COLUMN itinerary_items.fixed IS '방문 고정 여부. Oracle 0=조정 가능, 1=사용자가 고정';
COMMENT ON COLUMN itinerary_items.booking_status IS '예약 상태 NONE/NEEDS_CONFIRMATION/USER_CONFIRMED/PARTNER_CONFIRMED/FAILED/CANCELLED. 제휴 확인은 사용자 입력 불가';
COMMENT ON COLUMN itinerary_items.booking_reason IS '직접 기록한 예약 결과/실패 사유. 빈 값은 미입력';
COMMENT ON COLUMN itinerary_items.visit_start IS '사용자가 확인한 해당 방문일의 이용 가능 시작 HH:mm. NULL이면 미입력';
COMMENT ON COLUMN itinerary_items.visit_end IS '사용자가 확인한 이용 가능 마감 HH:mm. 시작 시각과 함께 입력';
COMMENT ON COLUMN itinerary_items.visit_cost IS '직접 확인한 1인당 예상 비용, 원 단위. NULL=미입력, 0=무료로 명시. 결제 확인 아님';
COMMENT ON COLUMN itinerary_items.visit_note IS '사용자가 직접 확인한 방문 조건 메모. 공급자 원문 자동 해석 결과 아님';

COMMENT ON TABLE trip_history IS '여행 변경 이력과 일정 복원용 JSON. 소유권/예약 전체를 자동 되돌리는 기능과 구분';
COMMENT ON COLUMN trip_history.id IS '서버가 발급한 UUID 식별자';
COMMENT ON COLUMN trip_history.trip_id IS '변경된 여행 ID';
COMMENT ON COLUMN trip_history.member_id IS '변경을 실행한 회원 ID. 탈퇴 후 비식별 연결 유지';
COMMENT ON COLUMN trip_history.version IS '이 변경 후의 여행 버전';
COMMENT ON COLUMN trip_history.summary IS '사용자에게 보여줄 변경 요약';
COMMENT ON COLUMN trip_history.snapshot IS '복원용 방문 일정 스냅샷 JSON. 공개 공유 대상 아님';
COMMENT ON COLUMN trip_history.created_at IS '행을 생성한 DB 현지 시각. 타임존 오프셋은 별도 저장하지 않음';

COMMENT ON TABLE invitations IS '여행 동행 초대와 수락 상태. 대기 초대 자체로는 비공개 여행 접근 불가';
COMMENT ON COLUMN invitations.id IS '서버가 발급한 UUID 식별자';
COMMENT ON COLUMN invitations.trip_id IS '초대 대상 여행 ID';
COMMENT ON COLUMN invitations.sender_id IS '초대를 보낸 회원 ID';
COMMENT ON COLUMN invitations.recipient_id IS '초대를 받은 회원 ID';
COMMENT ON COLUMN invitations.status IS '초대 상태 PENDING/ACCEPTED/DECLINED/REVOKED. 종료된 초대 재사용 불가';
COMMENT ON COLUMN invitations.created_at IS '행을 생성한 DB 현지 시각. 타임존 오프셋은 별도 저장하지 않음';

COMMENT ON TABLE trip_tasks IS '여행 참여자가 함께 사용하는 준비물/할 일';
COMMENT ON COLUMN trip_tasks.id IS '서버가 발급한 UUID 식별자';
COMMENT ON COLUMN trip_tasks.trip_id IS '준비물이 속한 여행 ID';
COMMENT ON COLUMN trip_tasks.content IS '준비물 또는 할 일 설명';
COMMENT ON COLUMN trip_tasks.done IS '완료 여부. Oracle 0=미완료, 1=완료';

COMMENT ON TABLE expenses IS '여행 참여자가 직접 입력한 지출. 송금/결제 승인 내역과는 별개';
COMMENT ON COLUMN expenses.id IS '서버가 발급한 UUID 식별자';
COMMENT ON COLUMN expenses.trip_id IS '지출이 속한 여행 ID';
COMMENT ON COLUMN expenses.member_id IS '지출을 기록한 회원 ID';
COMMENT ON COLUMN expenses.label IS '지출 이름';
COMMENT ON COLUMN expenses.amount IS '직접 입력한 양수 지출 금액, 원 단위';
COMMENT ON COLUMN expenses.created_at IS '행을 생성한 DB 현지 시각. 타임존 오프셋은 별도 저장하지 않음';

COMMENT ON TABLE posts IS '공개 여행 후기/추천/동행 모집. 작성자 삭제와 운영진 숨김을 별도로 관리';
COMMENT ON COLUMN posts.id IS '서버가 발급한 UUID 식별자';
COMMENT ON COLUMN posts.author_id IS '작성자 회원 ID. 탈퇴 후 비식별 연결 유지';
COMMENT ON COLUMN posts.category IS '게시판 종류 후기/추천/동행/코스/의견';
COMMENT ON COLUMN posts.region IS '관련 지역명. 탈퇴 시 빈 값으로 제거';
COMMENT ON COLUMN posts.title IS '게시글 제목. 탈퇴 시 비식별 안내로 대체';
COMMENT ON COLUMN posts.body IS '공개 본문. 탈퇴 시 내용 제거. 신고 당시 증거는 reports에 별도 보존';
COMMENT ON COLUMN posts.recruitment_date IS '동행 모집 여행 날짜. 일반 게시글은 NULL';
COMMENT ON COLUMN posts.capacity IS '동행 모집 인원, 1~20명. 일반 게시글은 NULL';
COMMENT ON COLUMN posts.status IS '동행 모집 상태 OPEN/CLOSED/CANCELLED. 복원 후에는 CLOSED';
COMMENT ON COLUMN posts.hidden IS '운영진 숨김. Oracle 0=숨기지 않음, 1=숨김. 작성자 복원으로 우회 불가';
COMMENT ON COLUMN posts.created_at IS '행을 생성한 DB 현지 시각. 타임존 오프셋은 별도 저장하지 않음';
COMMENT ON COLUMN posts.version IS '글 수정/삭제/복원/상태 변경 충돌 검사 버전';
COMMENT ON COLUMN posts.updated_at IS '작성자가 수정하거나 생명주기를 변경한 시각. 미변경은 NULL';
COMMENT ON COLUMN posts.deleted_at IS '작성자 휴지통 이동 또는 탈퇴 시각. NULL이면 삭제되지 않음. 공개 여부는 hidden도 검사';

COMMENT ON TABLE comments IS '게시글 댓글. 삭제 댓글은 공개 목록/개수에서 제외하고 복원 API를 제공하지 않음';
COMMENT ON COLUMN comments.id IS '서버가 발급한 UUID 식별자';
COMMENT ON COLUMN comments.post_id IS '댓글이 속한 게시글 ID. 다른 글 URL로 수정/삭제 불가';
COMMENT ON COLUMN comments.author_id IS '댓글 작성자 회원 ID';
COMMENT ON COLUMN comments.body IS '댓글 본문. 회원 탈퇴 시 내용 제거';
COMMENT ON COLUMN comments.created_at IS '행을 생성한 DB 현지 시각. 타임존 오프셋은 별도 저장하지 않음';
COMMENT ON COLUMN comments.version IS '댓글 자체 수정/삭제 충돌 검사 버전. 부모 글 버전과 분리';
COMMENT ON COLUMN comments.updated_at IS '댓글 본문 수정 시각. 미수정은 NULL';
COMMENT ON COLUMN comments.deleted_at IS '댓글 삭제/탈퇴 시각. NULL이면 삭제되지 않음. 부모 글 복원으로 되살리지 않음';

COMMENT ON TABLE blocks IS '회원 간 개인 대화 차단. 어느 방향이든 차단이면 새 요청/메시지 발송 제한';
COMMENT ON COLUMN blocks.blocker_id IS '차단을 설정한 회원 ID';
COMMENT ON COLUMN blocks.blocked_id IS '차단 대상 회원 ID. 여행 참여 권한/이전 대화는 별개';

COMMENT ON TABLE conversations IS '개인 또는 여행 단체 대화방. 메시지 열람 시 현재 참여 권한을 서버에서 검사';
COMMENT ON COLUMN conversations.id IS '서버가 발급한 UUID 식별자';
COMMENT ON COLUMN conversations.requester_id IS '개인 대화 요청자 또는 단체방 생성자 회원 ID';
COMMENT ON COLUMN conversations.recipient_id IS '개인 대화 수신자 회원 ID. 단체방은 생성자와 같음';
COMMENT ON COLUMN conversations.status IS '대화 상태 PENDING/ACTIVE/DECLINED/CLOSED. CLOSED는 탈퇴 후 읽기 전용';
COMMENT ON COLUMN conversations.trip_id IS '단체방의 여행 ID. NULL이면 개인 대화이며 양 당사자만 열람';
COMMENT ON COLUMN conversations.created_at IS '행을 생성한 DB 현지 시각. 타임존 오프셋은 별도 저장하지 않음';

COMMENT ON TABLE messages IS '대화 메시지. 전체 개인 대화는 운영자 목록에 노출하지 않으며 신고 한 건만 증거로 복사';
COMMENT ON COLUMN messages.id IS '서버가 발급한 UUID 식별자';
COMMENT ON COLUMN messages.conversation_id IS '메시지가 속한 대화방 ID';
COMMENT ON COLUMN messages.sender_id IS '메시지를 보낸 회원 ID. 탈퇴 후에도 공동 대화 맥락은 유지';
COMMENT ON COLUMN messages.body IS '메시지 본문. 수신/차단/대화 상태를 검사한 뒤 저장';
COMMENT ON COLUMN messages.created_at IS '행을 생성한 DB 현지 시각. 타임존 오프셋은 별도 저장하지 않음';

COMMENT ON TABLE reports IS '회원 신고 접수와 관리자 처리. 당시 내용 보존은 V13 이후 신규 접수에 적용';
COMMENT ON COLUMN reports.id IS '서버가 발급한 UUID 식별자';
COMMENT ON COLUMN reports.reporter_id IS '신고한 회원 ID. 공개 커뮤니티에 노출하지 않음';
COMMENT ON COLUMN reports.target_type IS '신고 대상 종류 POST=게시글, MESSAGE=메시지 한 건, USER=회원 공개 정보';
COMMENT ON COLUMN reports.target_id IS '대상 종류에 따른 ID. 다형 참조이므로 접수 서비스에서 존재/접근 권한 검사';
COMMENT ON COLUMN reports.reason IS '신고자가 입력한 사유, 최대 1000자';
COMMENT ON COLUMN reports.status IS '처리 상태 OPEN=대기, RESOLVED=완료. 최초 처리 후 변경 불가';
COMMENT ON COLUMN reports.resolution IS '관리자 처리 사유, 최대 1000자. 미처리는 빈 값';
COMMENT ON COLUMN reports.created_at IS '행을 생성한 DB 현지 시각. 타임존 오프셋은 별도 저장하지 않음';

COMMENT ON TABLE admin_audit IS '관리자 신고 처리 감사 요약. 상세 사유와 증거는 reports에서 관리';
COMMENT ON COLUMN admin_audit.id IS '서버가 발급한 UUID 식별자';
COMMENT ON COLUMN admin_audit.admin_id IS '처리를 실행한 관리자 회원 ID';
COMMENT ON COLUMN admin_audit.action IS '최대 200자의 처리 대상/조치 요약. 개인 대화 원문이나 긴 사유를 중복 저장하지 않음';
COMMENT ON COLUMN admin_audit.created_at IS '행을 생성한 DB 현지 시각. 타임존 오프셋은 별도 저장하지 않음';

COMMENT ON TABLE trip_lodgings IS '지역별 선택 숙소와 직접 확인한 숙박 조건. 날짜별 객실 재고/결제는 자동 확인하지 않음';
COMMENT ON COLUMN trip_lodgings.trip_id IS '숙소를 선택한 여행 ID';
COMMENT ON COLUMN trip_lodgings.place_id IS '실제 공급자 숙소 장소 ID';
COMMENT ON COLUMN trip_lodgings.region IS '숙박 지역명. 여행과 지역을 합쳐 한 숙소만 선택';
COMMENT ON COLUMN trip_lodgings.name IS '선택 당시 숙소 이름';
COMMENT ON COLUMN trip_lodgings.latitude IS '숙소 위도, 십진 도 단위';
COMMENT ON COLUMN trip_lodgings.longitude IS '숙소 경도, 십진 도 단위';
COMMENT ON COLUMN trip_lodgings.nightly_estimate IS '추천에 사용한 1객실 1박 참고 금액, 원 단위. 객실/날짜별 확정 가격 아님';
COMMENT ON COLUMN trip_lodgings.source IS '숙소 정보 공급자/샘플 출처 코드';
COMMENT ON COLUMN trip_lodgings.place_url IS '숙소 정보 또는 외부 예약 페이지 URL. 빈 값은 미제공';
COMMENT ON COLUMN trip_lodgings.booking_status IS '예약 상태 NONE/NEEDS_CONFIRMATION/USER_CONFIRMED/PARTNER_CONFIRMED/FAILED/CANCELLED. 직접 확인과 제휴 확인 구분';
COMMENT ON COLUMN trip_lodgings.booking_reason IS '사용자가 입력한 숙소 예약 결과/실패 사유';
COMMENT ON COLUMN trip_lodgings.check_in_date IS '직접 입력한 체크인 달력 날짜. NULL이면 미정';
COMMENT ON COLUMN trip_lodgings.check_out_date IS '직접 입력한 체크아웃 달력 날짜. NULL이면 미정';
COMMENT ON COLUMN trip_lodgings.guest_count IS '직접 입력한 숙박 인원. NULL이면 조건 미입력';
COMMENT ON COLUMN trip_lodgings.room_count IS '직접 입력한 객실 수. NULL이면 조건 미입력';
COMMENT ON COLUMN trip_lodgings.total_amount IS '전체 숙박 기간/전체 객실의 총 참고 금액, 원 단위. NULL=미입력, 결제 확인 아님';
COMMENT ON COLUMN trip_lodgings.check_in_time IS '선택 입력한 체크인 가능 HH:mm. NULL이면 미입력';
COMMENT ON COLUMN trip_lodgings.check_out_time IS '선택 입력한 체크아웃 마감 HH:mm. NULL이면 미입력';

COMMENT ON TABLE external_bookings IS '교통/항공/렌터카 등 외부 예매 결과를 직접 기록. 공급자의 재고/운행을 자동 조회한 결과 아님';
COMMENT ON COLUMN external_bookings.id IS '서버가 발급한 UUID 식별자';
COMMENT ON COLUMN external_bookings.trip_id IS '예약 기록이 속한 여행 ID';
COMMENT ON COLUMN external_bookings.label IS '예약 기록 이름';
COMMENT ON COLUMN external_bookings.category IS '외부 예약 유형 TRAIN/BUS/FLIGHT/CAR/OTHER';
COMMENT ON COLUMN external_bookings.departure IS '직접 입력한 출발 현지 날짜/시각 yyyy-MM-ddTHH:mm';
COMMENT ON COLUMN external_bookings.amount IS '직접 확인한 전체 예약 금액, 원 단위. NULL=미입력, 0=무료';
COMMENT ON COLUMN external_bookings.status IS '예약 확인 상태 NEEDS_CONFIRMATION/USER_CONFIRMED/FAILED/CANCELLED. 실제 제휴 콜백은 미연결';
COMMENT ON COLUMN external_bookings.reason IS '직접 입력한 예약 결과/실패 메모';
COMMENT ON COLUMN external_bookings.updated_by IS '마지막으로 기록을 변경한 참여 회원 ID';
COMMENT ON COLUMN external_bookings.arrival IS '직접 입력한 도착 현지 날짜/시각 yyyy-MM-ddTHH:mm. NULL이면 미입력';
COMMENT ON COLUMN external_bookings.departure_place IS '직접 입력한 출발 역/터미널/장소명. 미입력은 빈 값';
COMMENT ON COLUMN external_bookings.arrival_place IS '직접 입력한 도착 역/터미널/장소명. 미입력은 빈 값';
COMMENT ON COLUMN external_bookings.before_minutes IS '탑승 전 확보할 여유, 분 단위';
COMMENT ON COLUMN external_bookings.after_minutes IS '도착 후 확보할 여유, 분 단위';
COMMENT ON COLUMN external_bookings.schedule_linked IS '시간 보호 연결. Oracle 0=해제, 1=연결. 직접 예약 완료 상태도 함께 검사';

COMMENT ON TABLE trip_route_timings IS '실제 조회한 이동 시간의 명시적 일정 반영 기록. 검색어/전체 지도 선은 저장하지 않음';
COMMENT ON COLUMN trip_route_timings.trip_id IS '경로 시간을 반영한 여행 ID';
COMMENT ON COLUMN trip_route_timings.to_item_id IS '도착 일정 항목 ID. 항목 재삽입 구조이므로 서비스가 장소/날짜/순서 일치를 검사';
COMMENT ON COLUMN trip_route_timings.payload IS '출처/조회 시각/구간/여유/숙소 조건 등을 포함한 경로 반영 JSON. 운행/좌석 보장 아님';
