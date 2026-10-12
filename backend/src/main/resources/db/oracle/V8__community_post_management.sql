-- Oracle 19c 전용 이력. 공통 마이그레이션의 동일 버전과 기능을 맞춥니다.
-- 작성자의 삭제와 운영진 숨김은 별도 상태입니다. 삭제해도 댓글·신고 참조를 남겨 복원할 수 있습니다.
ALTER TABLE posts ADD version NUMBER(10) DEFAULT 0 NOT NULL;
ALTER TABLE posts ADD updated_at TIMESTAMP;
ALTER TABLE posts ADD deleted_at TIMESTAMP;
CREATE INDEX idx_posts_author_deleted ON posts(author_id, deleted_at);
