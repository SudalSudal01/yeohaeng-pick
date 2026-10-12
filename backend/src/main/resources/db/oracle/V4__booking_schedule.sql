-- Oracle 19c 전용 이력. 공통 마이그레이션의 동일 버전과 기능을 맞춥니다.
-- 기존 기록은 자동으로 시간 제약에 포함하지 않습니다. 사용자가 도착 시간과 여유 시간을 확인한 뒤 연결합니다.
ALTER TABLE external_bookings ADD arrival VARCHAR2(16 CHAR);
ALTER TABLE external_bookings ADD departure_place VARCHAR2(100 CHAR);
ALTER TABLE external_bookings ADD arrival_place VARCHAR2(100 CHAR);
ALTER TABLE external_bookings ADD before_minutes NUMBER(10) DEFAULT 30 NOT NULL;
ALTER TABLE external_bookings ADD after_minutes NUMBER(10) DEFAULT 30 NOT NULL;
ALTER TABLE external_bookings ADD schedule_linked NUMBER(1) DEFAULT 0 NOT NULL CHECK (schedule_linked IN (0,1));
