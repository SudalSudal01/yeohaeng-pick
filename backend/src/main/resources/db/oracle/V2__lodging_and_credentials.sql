-- Oracle 19c 전용 이력. 공통 마이그레이션의 동일 버전과 기능을 맞춥니다.
-- 숙소의 좌표를 일정과 분리해 보존합니다. 장소 교체나 일정 복원으로 숙소 예약이 바뀌지 않습니다.
CREATE TABLE trip_lodgings (
 trip_id VARCHAR2(36 CHAR) NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
 place_id VARCHAR2(100 CHAR) NOT NULL, region VARCHAR2(50 CHAR) NOT NULL, name VARCHAR2(150 CHAR) NOT NULL,
 latitude BINARY_DOUBLE NOT NULL, longitude BINARY_DOUBLE NOT NULL,
 nightly_estimate NUMBER(10) NOT NULL, source VARCHAR2(30 CHAR) NOT NULL, place_url CLOB,
 booking_status VARCHAR2(30 CHAR) DEFAULT 'NONE' NOT NULL, booking_reason VARCHAR2(150 CHAR),
 PRIMARY KEY(trip_id, region)
);
-- 비밀번호 변경 이후 이전에 발급된 모든 세션을 다음 요청부터 거절하기 위한 버전입니다.
ALTER TABLE members ADD credential_version NUMBER(10) DEFAULT 0 NOT NULL;
