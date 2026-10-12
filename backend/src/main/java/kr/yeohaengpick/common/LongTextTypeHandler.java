package kr.yeohaengpick.common;

import java.io.StringReader;
import java.sql.*;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * 긴 JSON/본문을 Oracle CLOB에 명시적으로 바인딩합니다. 19c + JDBC에서 큰 setString이나
 * setCharacterStream은 청크 경계의 이모지 서로게이트를 손상시킬 수 있어 setClob(Reader)를 사용합니다.
 * H2/PostgreSQL의 TEXT/VARCHAR에는 기존 setString을 유지합니다. 민감한 본문은 로그로 남기지 않습니다.
 */
public class LongTextTypeHandler extends BaseTypeHandler<String> {

  @Override
  public void setNonNullParameter(
    PreparedStatement statement,
    int index,
    String value,
    JdbcType type
  ) throws SQLException {
    if (
      "Oracle".equals(
        statement.getConnection().getMetaData().getDatabaseProductName()
      )
    ) {
      statement.setClob(index, new StringReader(value));
    } else {
      statement.setString(index, value);
    }
  }

  @Override
  public String getNullableResult(ResultSet result, String column)
    throws SQLException {
    return result.getString(column);
  }

  @Override
  public String getNullableResult(ResultSet result, int column)
    throws SQLException {
    return result.getString(column);
  }

  @Override
  public String getNullableResult(CallableStatement statement, int column)
    throws SQLException {
    return statement.getString(column);
  }
}
