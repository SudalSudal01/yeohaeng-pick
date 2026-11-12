-- 최신 메시지 조회와 (created_at,id) 이전 페이지를 같은 인덱스로 탐색합니다.
-- 데이터·권한·컬럼 의미는 바꾸지 않으며 기존 메시지의 시간/ID를 수정하지 않습니다.
CREATE INDEX idx_message_history ON messages(conversation_id, created_at, id);
