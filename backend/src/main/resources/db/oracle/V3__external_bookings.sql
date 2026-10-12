-- Oracle 19c 전용 이력. 공통 마이그레이션의 동일 버전과 기능을 맞춥니다.
-- 제휴 콜백 없이도 교통·항공·기타 예매의 실패/취소를 명시적으로 기록할 수 있습니다.
CREATE TABLE external_bookings (
 id VARCHAR2(36 CHAR) PRIMARY KEY, trip_id VARCHAR2(36 CHAR) NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
 label VARCHAR2(100 CHAR) NOT NULL, category VARCHAR2(20 CHAR) NOT NULL, departure VARCHAR2(16 CHAR) NOT NULL,
 amount NUMBER(10), status VARCHAR2(30 CHAR) DEFAULT 'NEEDS_CONFIRMATION' NOT NULL, reason VARCHAR2(150 CHAR),
 updated_by VARCHAR2(36 CHAR) NOT NULL REFERENCES members(id)
);
