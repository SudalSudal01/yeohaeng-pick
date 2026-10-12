import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;

/**
 * V11에서 중지한 여행픽 H2의 전체 관계 데이터를 빈 Oracle 전용 스키마로 옮기는 일회성 도구입니다.
 * 원본은 읽기 전용으로 열고, FK 부모부터 삽입하며, 모든 행/열의 해시가 맞아야 한 번 커밋합니다.
 * 비밀번호·회원/여행 원문을 로그로 출력하지 않습니다. 실행 전 파일 백업은 호출 스크립트의 책임입니다.
 */
class MigrateH2ToOracle {

  static final List<String> TABLES = List.of(
    "members",
    "email_tokens",
    "trips",
    "trip_members",
    "itinerary_items",
    "trip_history",
    "invitations",
    "trip_tasks",
    "expenses",
    "posts",
    "comments",
    "blocks",
    "conversations",
    "messages",
    "reports",
    "admin_audit",
    "trip_lodgings",
    "external_bookings",
    "trip_route_timings",
    "trip_ownership_history"
  );
  static final Set<String> BLANK_COLUMNS = Set.of(
    "trips.lodging",
    "itinerary_items.place_url",
    "itinerary_items.booking_reason",
    "itinerary_items.visit_note",
    "trip_lodgings.place_url",
    "trip_lodgings.booking_reason",
    "external_bookings.departure_place",
    "external_bookings.arrival_place",
    "external_bookings.reason",
    "reports.resolution",
    "posts.region",
    "posts.body",
    "comments.body"
  );

  record Column(String name, int sourceType, int targetType) {}

  record Fingerprint(long rows, String sha256) {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1 || !Set.of("copy", "verify").contains(args[0])) {
      throw new IllegalArgumentException(
        "사용법: MigrateH2ToOracle.java copy|verify (접속 정보는 환경변수)"
      );
    }
    String url = required("DATABASE_URL"),
      user = required("DATABASE_USERNAME"),
      password = required("DATABASE_PASSWORD");
    if (
      !url.matches(
        "jdbc:oracle:thin:@//(localhost|127\\.0\\.0\\.1):1521/[a-zA-Z0-9._-]+"
      ) ||
      !Set.of("YEOHAENG_PICK", "YEOHAENG_PICK_MIGRATION_QA").contains(user)
    ) {
      throw new IllegalArgumentException(
        "로컬 여행픽 전용 스키마만 허용합니다."
      );
    }
    Path sourceFile = Path.of(required("H2_SOURCE_FILE"))
      .toAbsolutePath()
      .normalize();
    if (
      !Files.isRegularFile(Path.of(sourceFile + ".mv.db"))
    ) throw new IllegalArgumentException("기존 H2 파일이 없습니다.");
    String sourceUrl =
      "jdbc:h2:file:" +
      sourceFile.toString().replace('\\', '/') +
      ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;IFEXISTS=TRUE;ACCESS_MODE_DATA=r";
    // DB 구조는 새 Oracle 전용 Flyway 이력으로 생성합니다. 다른 DB의 이력 행을 복사하지 않습니다.
    if (args[0].equals("copy")) Flyway.configure()
      .dataSource(url, user, password)
      .locations("classpath:db/oracle")
      .target("11")
      .cleanDisabled(true)
      .load()
      .migrate();
    try (
      var source = DriverManager.getConnection(sourceUrl, "sa", "");
      var target = DriverManager.getConnection(url, user, password)
    ) {
      validateSchema(source, target);
      target.setAutoCommit(false);
      try {
        if (args[0].equals("copy")) {
          // 기존 실사용 테이블이 한 행이라도 있으면 덮어쓰기/병합/삭제 없이 중단합니다.
          for (String table : TABLES)
            try (
              var statement = target.createStatement();
              var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)
            ) {
              rows.next();
              if (rows.getLong(1) != 0) throw new IllegalStateException(
                "대상 스키마가 비어 있지 않습니다: " + table
              );
            }
          for (String table : TABLES)
            copy(source, target, table, columns(source, target, table));
        }
        verify(source, target);
        if (args[0].equals("copy")) target.commit();
        // 커밋 직후 새 연결에서도 동일하게 읽히는지 확인합니다. 검증 모드는 쓰기를 하지 않습니다.
        try (var committed = DriverManager.getConnection(url, user, password)) {
          verify(source, committed);
        }
        System.out.println(
          "완료: 20개 테이블의 행 수와 전체 열 SHA-256이 일치합니다. 개인정보 원문은 출력하지 않았습니다."
        );
      } catch (Exception failure) {
        target.rollback();
        // 원본 값이 포함될 수 있는 드라이버 SQL 오류는 출력하지 않고 테이블/오류 코드만 전달합니다.
        if (
          failure instanceof SQLException sql
        ) throw new IllegalStateException(
          "Oracle 이전 실패 (SQLState=" +
            sql.getSQLState() +
            ", code=" +
            sql.getErrorCode() +
            ")"
        );
        throw failure;
      }
    }
  }

  static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(
      name + " 설정이 필요합니다."
    );
    return value;
  }

  static void validateSchema(Connection source, Connection target)
    throws Exception {
    Set<String> sourceTables = new HashSet<>();
    try (
      var statement = source.createStatement();
      var rows = statement.executeQuery(
        "SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_name <> 'flyway_schema_history'"
      )
    ) {
      while (rows.next()) sourceTables.add(rows.getString(1));
    }
    if (
      !sourceTables.equals(new HashSet<>(TABLES))
    ) throw new IllegalStateException(
      "예상하지 못한 H2 테이블 구성입니다. 이전 도구를 최신 스키마와 맞춰 주세요."
    );
    try (
      var statement = source.createStatement();
      var rows = statement.executeQuery(
        "SELECT version FROM flyway_schema_history WHERE success=TRUE AND version IS NOT NULL ORDER BY installed_rank DESC FETCH FIRST 1 ROWS ONLY"
      )
    ) {
      if (
        !rows.next() || !"11".equals(rows.getString(1))
      ) throw new IllegalStateException("원본은 검증된 V11 스키마여야 합니다.");
    }
    for (String table : TABLES) columns(source, target, table);
  }

  static List<Column> columns(
    Connection source,
    Connection target,
    String table
  ) throws Exception {
    try (
      var sourceStatement = source.createStatement();
      var sourceRows = sourceStatement.executeQuery(
        "SELECT * FROM " + table + " WHERE 1=0"
      );
      var targetStatement = target.createStatement();
      var targetRows = targetStatement.executeQuery(
        "SELECT * FROM " + table + " WHERE 1=0"
      )
    ) {
      var from = sourceRows.getMetaData();
      var to = targetRows.getMetaData();
      if (
        from.getColumnCount() != to.getColumnCount()
      ) throw new IllegalStateException("열 수가 다릅니다: " + table);
      var columns = new ArrayList<Column>();
      for (int i = 1; i <= from.getColumnCount(); i++) {
        String name = from.getColumnName(i).toLowerCase(Locale.ROOT);
        if (
          !name.matches("[a-z_]+") ||
          !name.equalsIgnoreCase(to.getColumnName(i))
        ) throw new IllegalStateException("열 구성이 다릅니다: " + table);
        columns.add(
          new Column(name, from.getColumnType(i), to.getColumnType(i))
        );
      }
      return columns;
    }
  }

  static void copy(
    Connection source,
    Connection target,
    String table,
    List<Column> columns
  ) throws Exception {
    String names = String.join(
      ",",
      columns.stream().map(Column::name).toList()
    );
    String values = String.join(",", Collections.nCopies(columns.size(), "?"));
    try (
      var statement = source.createStatement();
      var rows = statement.executeQuery("SELECT " + names + " FROM " + table);
      var insert = target.prepareStatement(
        "INSERT INTO " + table + "(" + names + ") VALUES(" + values + ")"
      )
    ) {
      while (rows.next()) {
        for (int i = 1; i <= columns.size(); i++) {
          Column column = columns.get(i - 1);
          if (rows.getObject(i) == null) {
            insert.setNull(i, column.targetType());
            continue;
          }
          if (column.targetType() == Types.CLOB) insert.setClob(
            i,
            new StringReader(rows.getString(i))
          );
          else switch (column.sourceType()) {
            case Types.BOOLEAN -> insert.setInt(i, rows.getBoolean(i) ? 1 : 0);
            case Types.INTEGER -> insert.setInt(i, rows.getInt(i));
            case Types.DOUBLE -> insert.setDouble(i, rows.getDouble(i));
            case Types.DATE -> insert.setDate(i, rows.getDate(i));
            case Types.TIMESTAMP -> insert.setTimestamp(
              i,
              rows.getTimestamp(i)
            );
            default -> insert.setString(i, rows.getString(i));
          }
        }
        insert.executeUpdate();
      }
    } catch (SQLException error) {
      throw new IllegalStateException(
        "테이블 이전 실패: " + table + " (code=" + error.getErrorCode() + ")"
      );
    }
  }

  static String order(Connection source, String table) throws Exception {
    var keys = new TreeMap<Short, String>();
    try (
      var rows = source.getMetaData().getPrimaryKeys(null, "public", table)
    ) {
      while (rows.next())
        keys.put(rows.getShort("KEY_SEQ"), rows.getString("COLUMN_NAME"));
    }
    if (
      keys.isEmpty() ||
      keys
        .values()
        .stream()
        .anyMatch(n -> !n.matches("[a-z_]+"))
    ) throw new IllegalStateException(
      "안전한 PK 정렬을 찾지 못했습니다: " + table
    );
    return String.join(",", keys.values());
  }

  static void verify(Connection source, Connection target) throws Exception {
    for (String table : TABLES) {
      var columns = columns(source, target, table);
      String order = order(source, table);
      if (
        !fingerprint(source, table, columns, order).equals(
          fingerprint(target, table, columns, order)
        )
      ) {
        throw new IllegalStateException(
          "이전 전후 데이터가 다릅니다: " + table
        );
      }
    }
  }

  static Fingerprint fingerprint(
    Connection connection,
    String table,
    List<Column> columns,
    String order
  ) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    long count = 0;
    String names = String.join(
      ",",
      columns.stream().map(Column::name).toList()
    );
    try (
      var statement = connection.createStatement();
      var rows = statement.executeQuery(
        "SELECT " + names + " FROM " + table + " ORDER BY " + order
      )
    ) {
      while (rows.next()) {
        count++;
        for (int i = 1; i <= columns.size(); i++) {
          Column column = columns.get(i - 1);
          String value = null;
          if (rows.getObject(i) != null) value = switch (column.sourceType()) {
            case Types.BOOLEAN -> rows.getBoolean(i) ? "1" : "0";
            case Types.INTEGER -> rows
              .getBigDecimal(i)
              .stripTrailingZeros()
              .toPlainString();
            case Types.DOUBLE -> Long.toHexString(
              Double.doubleToLongBits(rows.getDouble(i))
            );
            case Types.DATE -> rows.getDate(i).toLocalDate().toString();
            case Types.TIMESTAMP -> rows
              .getTimestamp(i)
              .toLocalDateTime()
              .toString();
            default -> rows.getString(i);
          };
          // Oracle에서 NULL과 빈 문자열을 구분할 수 없는, 원래 빈 값을 허용한 열만 동일시합니다.
          if (
            value == null && BLANK_COLUMNS.contains(table + "." + column.name())
          ) value = "";
          byte[] bytes =
            value == null
              ? new byte[0]
              : value.getBytes(StandardCharsets.UTF_8);
          digest.update(
            ByteBuffer.allocate(4)
              .putInt(value == null ? -1 : bytes.length)
              .array()
          );
          digest.update(bytes);
        }
      }
    }
    return new Fingerprint(count, HexFormat.of().formatHex(digest.digest()));
  }
}
