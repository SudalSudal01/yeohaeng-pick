import java.nio.file.*;
import java.sql.*;
import org.flywaydb.core.Flyway;

/** 새로 만든 이전 QA 스키마에서만 합성 데이터의 전체 보존, 실패 롤백, 재실행 거절을 검사합니다. */
class VerifyOracleMigration {

  public static void main(String[] args) throws Exception {
    if (
      !"YEOHAENG_PICK_MIGRATION_QA".equals(System.getenv("DATABASE_USERNAME"))
    ) throw new IllegalStateException("이전 QA 전용 계정이 필요합니다.");
    if (args.length == 1 && args[0].equals("cleanup")) {
      // 기존 합성 원본과 전체 해시가 일치하는 경우에만 이전 QA 결과를 정리합니다.
      MigrateH2ToOracle.main(new String[] { "verify" });
      cleanup();
      return;
    }
    String source =
      "jdbc:h2:file:" +
      System.getenv("H2_SOURCE_FILE").replace('\\', '/') +
      ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
    if (
      Files.exists(Path.of(System.getenv("H2_SOURCE_FILE") + ".mv.db"))
    ) throw new IllegalStateException("새 합성 데이터 파일만 허용합니다.");
    // 전환 도구의 보장 범위는 당시의 V11입니다. 이후 앱 스키마 검증은 HTTP 테스트가 담당합니다.
    Flyway.configure()
      .dataSource(source, "sa", "")
      .target("11")
      .load()
      .migrate();
    try (
      var c = DriverManager.getConnection(source, "sa", "");
      var s = c.createStatement()
    ) {
      s.executeUpdate(
        "INSERT INTO members(id,email,password_hash,nickname,verified) VALUES('fixture-owner','owner@example.test','synthetic-hash','여행 검증자 🧭',TRUE)"
      );
      s.executeUpdate(
        "INSERT INTO members(id,email,password_hash,nickname,deleted_at) VALUES('fixture-former','withdrawn:fixture','!withdrawn','탈퇴한 회원',TIMESTAMP '2026-10-01 09:00:00.123456')"
      );
      s.executeUpdate(
        "INSERT INTO email_tokens(token_hash,member_id,purpose,expires_at) VALUES('fixture-token','fixture-owner','VERIFY',TIMESTAMP '2026-11-01 09:00:00.123456')"
      );
      s.executeUpdate(
        "INSERT INTO trips(id,owner_id,title,regions,start_date,end_date,origin,party_size,budget,transport,preferences,lodging,share_token) VALUES('fixture-trip','fixture-owner','이전 검증 여행','[\"서울\"]',DATE '2026-11-08',DATE '2026-11-09','서울 출발',2,200000,'TRANSIT','[\"자연\"]','','fixture-share')"
      );
      s.executeUpdate(
        "INSERT INTO trip_members VALUES('fixture-trip','fixture-owner')"
      );
      s.executeUpdate(
        "INSERT INTO itinerary_items(id,trip_id,day_index,sort_order,place_id,place_name,region,category,latitude,longitude,start_time,duration,cost,indoor,source,place_url,fixed,visit_cost,visit_start,visit_end) VALUES('fixture-item','fixture-trip',0,0,'fixture-place','한글 장소 🧭','서울','자연',37.1234567890123,127.9876543210123,'10:00',60,1000,FALSE,'SAMPLE','',TRUE,0,'09:00','18:00')"
      );
      s.executeUpdate(
        "INSERT INTO invitations(id,trip_id,sender_id,recipient_id,status) VALUES('fixture-invite','fixture-trip','fixture-owner','fixture-former','REVOKED')"
      );
      s.executeUpdate(
        "INSERT INTO trip_tasks VALUES('fixture-task','fixture-trip','예약 확인',TRUE)"
      );
      s.executeUpdate(
        "INSERT INTO expenses(id,trip_id,member_id,label,amount) VALUES('fixture-expense','fixture-trip','fixture-owner','점심',12345)"
      );
      s.executeUpdate(
        "INSERT INTO posts(id,author_id,category,region,title,body) VALUES('fixture-post','fixture-owner','후기','서울','긴 본문','임시')"
      );
      s.executeUpdate(
        "INSERT INTO posts(id,author_id,category,region,title,body,deleted_at) VALUES('fixture-erased','fixture-former','후기','','탈퇴한 회원의 게시글','',CURRENT_TIMESTAMP)"
      );
      s.executeUpdate(
        "INSERT INTO comments(id,post_id,author_id,body,deleted_at) VALUES('fixture-comment','fixture-post','fixture-former','',CURRENT_TIMESTAMP)"
      );
      s.executeUpdate(
        "INSERT INTO blocks VALUES('fixture-owner','fixture-former')"
      );
      s.executeUpdate(
        "INSERT INTO conversations(id,requester_id,recipient_id,status) VALUES('fixture-room','fixture-owner','fixture-former','CLOSED')"
      );
      s.executeUpdate(
        "INSERT INTO messages(id,conversation_id,sender_id,body) VALUES('fixture-message','fixture-room','fixture-former','보존되는 한 건의 대화 🧭')"
      );
      s.executeUpdate(
        "INSERT INTO reports(id,reporter_id,target_type,target_id,reason) VALUES('fixture-report','fixture-owner','MESSAGE','fixture-message','합성 신고 사유')"
      );
      s.executeUpdate(
        "INSERT INTO admin_audit(id,admin_id,action) VALUES('fixture-audit','fixture-owner','합성 운영 기록')"
      );
      s.executeUpdate(
        "INSERT INTO trip_lodgings(trip_id,place_id,region,name,latitude,longitude,nightly_estimate,source,place_url,check_in_date,check_out_date,guest_count,room_count,total_amount) VALUES('fixture-trip','fixture-hotel','서울','검증 숙소',37.5,127.1,80000,'SAMPLE','',DATE '2026-11-08',DATE '2026-11-09',2,1,0)"
      );
      s.executeUpdate(
        "INSERT INTO external_bookings(id,trip_id,label,category,departure,amount,updated_by) VALUES('fixture-booking','fixture-trip','교통 확인','TRAIN','2026-11-08T08:00',0,'fixture-owner')"
      );
      s.executeUpdate(
        "INSERT INTO trip_route_timings VALUES('fixture-trip','fixture-item','{\"synthetic\":true}')"
      );
      s.executeUpdate(
        "INSERT INTO trip_ownership_history(id,trip_id,previous_owner_id,new_owner_id,version,revoked_invitations) VALUES('fixture-ownership','fixture-trip','fixture-former','fixture-owner',1,0)"
      );
      try (
        var post = c.prepareStatement(
          "UPDATE posts SET body=? WHERE id='fixture-post'"
        );
        var history = c.prepareStatement(
          "INSERT INTO trip_history(id,trip_id,member_id,version,summary,snapshot) VALUES('fixture-history','fixture-trip','fixture-owner',1,'합성 변경',?)"
        )
      ) {
        post.setString(1, "한글과 이모지 🧭\n".repeat(900));
        post.executeUpdate();
        history.setString(1, "{\"note\":\"" + "한글🧭".repeat(10000) + "\"}");
        history.executeUpdate();
      }
    }
    String url = System.getenv("DATABASE_URL"),
      user = System.getenv("DATABASE_USERNAME"),
      password = System.getenv("DATABASE_PASSWORD");
    Flyway.configure()
      .dataSource(url, user, password)
      .locations("classpath:db/oracle")
      .target("11")
      .load()
      .migrate();
    try (
      var c = DriverManager.getConnection(url, user, password);
      var s = c.createStatement()
    ) {
      for (String table : MigrateH2ToOracle.TABLES)
        try (var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
          r.next();
          if (r.getLong(1) != 0) throw new IllegalStateException(
            "이전 QA 스키마는 비어 있어야 합니다."
          );
        }
      // 부모 회원을 삽입한 뒤 여행에서 실패하도록 하여 부분 데이터가 남지 않는지 확인합니다.
      s.executeUpdate(
        "ALTER TABLE trips ADD CONSTRAINT fixture_reject CHECK (title <> '이전 검증 여행')"
      );
    }
    try {
      MigrateH2ToOracle.main(new String[] { "copy" });
      throw new AssertionError("실패해야 하는 이전이 성공했습니다.");
    } catch (IllegalStateException expected) {
      if (
        !expected.getMessage().contains("테이블 이전 실패: trips")
      ) throw expected;
    }
    try (
      var c = DriverManager.getConnection(url, user, password);
      var s = c.createStatement();
      var r = s.executeQuery("SELECT COUNT(*) FROM members")
    ) {
      r.next();
      if (r.getInt(1) != 0) throw new AssertionError(
        "이전 실패 뒤 회원 데이터가 남았습니다."
      );
      s.executeUpdate("ALTER TABLE trips DROP CONSTRAINT fixture_reject");
    }
    MigrateH2ToOracle.main(new String[] { "copy" });
    try {
      MigrateH2ToOracle.main(new String[] { "copy" });
      throw new AssertionError("기존 데이터 덮어쓰기가 허용됐습니다.");
    } catch (IllegalStateException expected) {
      if (!expected.getMessage().contains("비어 있지 않습니다")) throw expected;
    }
    MigrateH2ToOracle.main(new String[] { "verify" });
    cleanup();
    System.out.println(
      "이전 QA 통과: 전체 20개 테이블, 한글/이모지/대용량 CLOB/날짜/소수 좌표/NULL/0/빈 문자열, 실패 롤백, 재실행 거절."
    );
  }

  static void cleanup() throws Exception {
    if (
      !"YEOHAENG_PICK_MIGRATION_QA".equals(System.getenv("DATABASE_USERNAME"))
    ) throw new IllegalStateException("이전 QA만 정리할 수 있습니다.");
    try (
      var c = DriverManager.getConnection(
        System.getenv("DATABASE_URL"),
        System.getenv("DATABASE_USERNAME"),
        System.getenv("DATABASE_PASSWORD")
      )
    ) {
      c.setAutoCommit(false);
      try (var s = c.createStatement()) {
        // 검증 직후의 합성 행을 FK 자식부터 제거합니다. 실패하면 정리도 전부 롤백합니다.
        var tables = new java.util.ArrayList<>(MigrateH2ToOracle.TABLES);
        java.util.Collections.reverse(tables);
        for (String table : tables) s.executeUpdate("DELETE FROM " + table);
        c.commit();
      } catch (Exception error) {
        c.rollback();
        throw error;
      }
    }
    System.out.println(
      "검증한 이전 QA 합성 기록을 정리했습니다. 실제 여행픽 스키마는 접근하지 않았습니다."
    );
  }
}
