-- Oracle 19c 전용 이력. 공통 마이그레이션의 동일 버전과 기능을 맞춥니다.
-- 기존 숙소/예약 상태는 유지하고 사용자가 확인한 숙박 조건만 선택적으로 보관합니다.
-- total_amount는 전체 숙박 기간·전체 객실의 합계이며 결제 확인이나 자동 조회 가격이 아닙니다.
ALTER TABLE trip_lodgings ADD check_in_date DATE;
ALTER TABLE trip_lodgings ADD check_out_date DATE;
ALTER TABLE trip_lodgings ADD guest_count NUMBER(10);
ALTER TABLE trip_lodgings ADD room_count NUMBER(10);
ALTER TABLE trip_lodgings ADD total_amount NUMBER(10);
ALTER TABLE trip_lodgings ADD check_in_time VARCHAR2(5 CHAR);
ALTER TABLE trip_lodgings ADD check_out_time VARCHAR2(5 CHAR);
