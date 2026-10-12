-- 공동 기록의 외래키는 유지하되 로그인·복구가 불가능한 비식별 계정으로 전환합니다.
-- 기존 회원과 여행 데이터는 수정하지 않습니다. 실제 탈퇴 요청에서만 값을 변경합니다.
ALTER TABLE members ADD COLUMN deleted_at TIMESTAMP;
