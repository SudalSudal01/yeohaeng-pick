-- Oracle 19c 전용 이력. 공통 마이그레이션의 동일 버전과 기능을 맞춥니다.
-- 공급자 원문을 자동 해석하지 않고 사용자가 당일 확인한 조건만 보존합니다.
-- NULL 금액은 미입력, 0원은 명시적 입력입니다. 기존 추천 비용은 별도 cost 열에 유지합니다.
ALTER TABLE itinerary_items ADD visit_start VARCHAR2(5 CHAR);
ALTER TABLE itinerary_items ADD visit_end VARCHAR2(5 CHAR);
ALTER TABLE itinerary_items ADD visit_cost NUMBER(10) CHECK (visit_cost >= 0 AND visit_cost <= 500000);
ALTER TABLE itinerary_items ADD visit_note VARCHAR2(500 CHAR);
ALTER TABLE itinerary_items ADD CONSTRAINT visit_window_pair CHECK (
 (visit_start IS NULL AND visit_end IS NULL) OR (visit_start IS NOT NULL AND visit_end IS NOT NULL AND visit_start < visit_end)
);
