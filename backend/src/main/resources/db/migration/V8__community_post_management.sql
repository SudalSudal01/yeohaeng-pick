-- 작성자의 삭제와 운영진 숨김은 별도 상태입니다. 삭제해도 댓글·신고 참조를 남겨 복원할 수 있습니다.
ALTER TABLE posts ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE posts ADD COLUMN updated_at TIMESTAMP;
ALTER TABLE posts ADD COLUMN deleted_at TIMESTAMP;
CREATE INDEX idx_posts_author_deleted ON posts(author_id, deleted_at);
