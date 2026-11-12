-- 최신 메시지 조회와 (created_at,id) 이전 페이지를 같은 인덱스로 탐색합니다.
-- 공통 마이그레이션과 같은 정의이며 기존 데이터·권한·컬럼 의미는 변경하지 않습니다.
CREATE INDEX idx_message_history ON messages(conversation_id, created_at, id);
