package org.sunbird.postgresql.impl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.sunbird.keys.JsonKey;
import org.sunbird.logging.LoggerUtil;
import org.sunbird.postgresql.PostgreSQLConnectionManager;
import org.sunbird.postgresql.PostgreSQLOperation;
import org.sunbird.request.RequestContext;
import org.sunbird.response.Response;

/**
 * PostgreSQL operation implementation
 *
 * @author System Migration Team
 */
public class PostgreSQLOperationImpl implements PostgreSQLOperation {

  private static final LoggerUtil logger = new LoggerUtil(PostgreSQLOperationImpl.class);
  private final PostgreSQLConnectionManager connectionManager;

  public PostgreSQLOperationImpl() {
    this.connectionManager = PostgreSQLConnectionManager.getInstance();
  }

  @Override
  public Response upsertRecord(
      String schemaName, String tableName, Map<String, Object> request, RequestContext context) {
    logger.debug(
        context, "PostgreSQL upsertRecord started for table: " + schemaName + "." + tableName);

    if (MapUtils.isEmpty(request)) {
      throw new RuntimeException("Request data cannot be empty for upsert operation");
    }

    StringBuilder queryBuilder = new StringBuilder();
    queryBuilder
        .append("INSERT INTO ")
        .append(schemaName)
        .append(".")
        .append(tableName)
        .append(" (");

    List<String> columns = new ArrayList<>(request.keySet());
    List<Object> values = new ArrayList<>();

    // Build column names
    for (int i = 0; i < columns.size(); i++) {
      queryBuilder.append(columns.get(i));
      if (i < columns.size() - 1) {
        queryBuilder.append(", ");
      }
      values.add(request.get(columns.get(i)));
    }

    queryBuilder.append(") VALUES (");

    // Build placeholders
    for (int i = 0; i < columns.size(); i++) {
      queryBuilder.append("?");
      if (i < columns.size() - 1) {
        queryBuilder.append(", ");
      }
    }

    queryBuilder.append(") ON CONFLICT (id) DO UPDATE SET ");

    // Build update clause for conflict resolution
    for (int i = 0; i < columns.size(); i++) {
      if (!"id".equals(columns.get(i))) {
        queryBuilder.append(columns.get(i)).append(" = EXCLUDED.").append(columns.get(i));
        if (i < columns.size() - 1) {
          queryBuilder.append(", ");
        }
      }
    }

    // Add updated_date if not present
    if (!request.containsKey("updated_date")) {
      queryBuilder.append(", updated_date = CURRENT_TIMESTAMP");
    }

    return executeUpdate(queryBuilder.toString(), values, context);
  }

  @Override
  public Response insertRecord(
      String schemaName, String tableName, Map<String, Object> request, RequestContext context) {
    logger.debug(
        context, "PostgreSQL insertRecord started for table: " + schemaName + "." + tableName);

    if (MapUtils.isEmpty(request)) {
      throw new RuntimeException("Request data cannot be empty for insert operation");
    }

    StringBuilder queryBuilder = new StringBuilder();
    queryBuilder
        .append("INSERT INTO ")
        .append(schemaName)
        .append(".")
        .append(tableName)
        .append(" (");

    List<String> columns = new ArrayList<>(request.keySet());
    List<Object> values = new ArrayList<>();

    // Build column names
    for (int i = 0; i < columns.size(); i++) {
      queryBuilder.append(columns.get(i));
      if (i < columns.size() - 1) {
        queryBuilder.append(", ");
      }
      values.add(request.get(columns.get(i)));
    }

    queryBuilder.append(") VALUES (");

    // Build placeholders
    for (int i = 0; i < columns.size(); i++) {
      queryBuilder.append("?");
      if (i < columns.size() - 1) {
        queryBuilder.append(", ");
      }
    }

    queryBuilder.append(")");

    return executeUpdate(queryBuilder.toString(), values, context);
  }

  @Override
  public Response updateRecord(
      String schemaName, String tableName, Map<String, Object> request, RequestContext context) {
    logger.debug(
        context, "PostgreSQL updateRecord started for table: " + schemaName + "." + tableName);

    if (MapUtils.isEmpty(request)) {
      throw new RuntimeException("Request data cannot be empty for update operation");
    }

    if (!request.containsKey("id")) {
      throw new RuntimeException("Primary key 'id' is required for update operation");
    }

    StringBuilder queryBuilder = new StringBuilder();
    queryBuilder.append("UPDATE ").append(schemaName).append(".").append(tableName).append(" SET ");

    List<Object> values = new ArrayList<>();
    boolean first = true;

    for (Map.Entry<String, Object> entry : request.entrySet()) {
      if (!"id".equals(entry.getKey())) {
        if (!first) {
          queryBuilder.append(", ");
        }
        queryBuilder.append(entry.getKey()).append(" = ?");
        values.add(entry.getValue());
        first = false;
      }
    }

    // Add updated_date
    if (!request.containsKey("updated_date")) {
      queryBuilder.append(", updated_date = CURRENT_TIMESTAMP");
    }

    queryBuilder.append(" WHERE id = ?");
    values.add(request.get("id"));

    return executeUpdate(queryBuilder.toString(), values, context);
  }

  @Override
  public Response deleteRecord(
      String schemaName, String tableName, String identifier, RequestContext context) {
    logger.debug(
        context,
        "PostgreSQL deleteRecord started for table: "
            + schemaName
            + "."
            + tableName
            + ", id: "
            + identifier);

    String query = "DELETE FROM " + schemaName + "." + tableName + " WHERE id = ?";
    List<Object> values = new ArrayList<>();
    values.add(identifier);

    return executeUpdate(query, values, context);
  }

  @Override
  public Response getRecordById(
      String schemaName, String tableName, String key, RequestContext context) {
    logger.debug(
        context,
        "PostgreSQL getRecordById started for table: "
            + schemaName
            + "."
            + tableName
            + ", id: "
            + key);

    String query = "SELECT * FROM " + schemaName + "." + tableName + " WHERE id = ?";
    List<Object> values = new ArrayList<>();
    values.add(key);

    return executeQuery(query, values, context);
  }

  @Override
  public Response getAllRecords(String schemaName, String tableName, RequestContext context) {
    logger.debug(
        context, "PostgreSQL getAllRecords started for table: " + schemaName + "." + tableName);

    String query = "SELECT * FROM " + schemaName + "." + tableName;
    return executeQuery(query, new ArrayList<>(), context);
  }

  @Override
  public Response getRecordsByProperties(
      String schemaName,
      String tableName,
      Map<String, Object> propertyMap,
      RequestContext context) {
    logger.debug(
        context,
        "PostgreSQL getRecordsByProperties started for table: " + schemaName + "." + tableName);

    if (MapUtils.isEmpty(propertyMap)) {
      return getAllRecords(schemaName, tableName, context);
    }

    StringBuilder queryBuilder = new StringBuilder();
    queryBuilder
        .append("SELECT * FROM ")
        .append(schemaName)
        .append(".")
        .append(tableName)
        .append(" WHERE ");

    List<Object> values = new ArrayList<>();
    boolean first = true;

    for (Map.Entry<String, Object> entry : propertyMap.entrySet()) {
      if (!first) {
        queryBuilder.append(" AND ");
      }
      queryBuilder.append(entry.getKey()).append(" = ?");
      values.add(entry.getValue());
      first = false;
    }

    return executeQuery(queryBuilder.toString(), values, context);
  }

  @Override
  public Response batchInsert(
      String schemaName,
      String tableName,
      List<Map<String, Object>> records,
      RequestContext context) {
    logger.debug(
        context,
        "PostgreSQL batchInsert started for table: "
            + schemaName
            + "."
            + tableName
            + ", records: "
            + records.size());

    if (CollectionUtils.isEmpty(records)) {
      throw new RuntimeException("Records list cannot be empty for batch insert operation");
    }

    try (Connection connection = connectionManager.getConnection()) {
      connection.setAutoCommit(false);

      Map<String, Object> firstRecord = records.get(0);
      List<String> columns = new ArrayList<>(firstRecord.keySet());

      StringBuilder queryBuilder = new StringBuilder();
      queryBuilder
          .append("INSERT INTO ")
          .append(schemaName)
          .append(".")
          .append(tableName)
          .append(" (");

      for (int i = 0; i < columns.size(); i++) {
        queryBuilder.append(columns.get(i));
        if (i < columns.size() - 1) {
          queryBuilder.append(", ");
        }
      }

      queryBuilder.append(") VALUES (");

      for (int i = 0; i < columns.size(); i++) {
        queryBuilder.append("?");
        if (i < columns.size() - 1) {
          queryBuilder.append(", ");
        }
      }

      queryBuilder.append(")");

      try (PreparedStatement statement = connection.prepareStatement(queryBuilder.toString())) {
        for (Map<String, Object> record : records) {
          for (int i = 0; i < columns.size(); i++) {
            setPreparedStatementValue(statement, i + 1, record.get(columns.get(i)));
          }
          statement.addBatch();
        }

        int[] results = statement.executeBatch();
        connection.commit();

        Response response = new Response();
        response.put(JsonKey.RESPONSE, "SUCCESS");
        logger.debug(
            context,
            "PostgreSQL batchInsert completed successfully, rows affected: " + results.length);
        return response;

      } catch (SQLException e) {
        connection.rollback();
        logger.error(context, "PostgreSQL batchInsert failed", e);
        throw new RuntimeException("Batch insert operation failed", e);
      }

    } catch (SQLException e) {
      logger.error(context, "PostgreSQL batchInsert connection failed", e);
      throw new RuntimeException("Database connection failed for batch insert", e);
    }
  }

  private Response executeUpdate(String query, List<Object> values, RequestContext context) {
    try (Connection connection = connectionManager.getConnection();
        PreparedStatement statement = connection.prepareStatement(query)) {

      for (int i = 0; i < values.size(); i++) {
        setPreparedStatementValue(statement, i + 1, values.get(i));
      }

      int rowsAffected = statement.executeUpdate();

      Response response = new Response();
      response.put(JsonKey.RESPONSE, "SUCCESS");
      response.put("rowsAffected", rowsAffected);

      logger.debug(context, "PostgreSQL executeUpdate completed, rows affected: " + rowsAffected);
      return response;

    } catch (SQLException e) {
      logger.error(context, "PostgreSQL executeUpdate failed for query: " + query, e);
      throw new RuntimeException("Database operation failed", e);
    }
  }

  private Response executeQuery(String query, List<Object> values, RequestContext context) {
    try (Connection connection = connectionManager.getConnection();
        PreparedStatement statement = connection.prepareStatement(query)) {

      for (int i = 0; i < values.size(); i++) {
        setPreparedStatementValue(statement, i + 1, values.get(i));
      }

      try (ResultSet resultSet = statement.executeQuery()) {
        List<Map<String, Object>> records = new ArrayList<>();
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();

        while (resultSet.next()) {
          Map<String, Object> record = new HashMap<>();
          for (int i = 1; i <= columnCount; i++) {
            String columnName = metaData.getColumnName(i);
            Object value = resultSet.getObject(i);
            record.put(columnName, value);
          }
          records.add(record);
        }

        Response response = new Response();
        response.put(JsonKey.RESPONSE, records);

        logger.debug(
            context, "PostgreSQL executeQuery completed, records found: " + records.size());
        return response;
      }

    } catch (SQLException e) {
      logger.error(context, "PostgreSQL executeQuery failed for query: " + query, e);
      throw new RuntimeException("Database query failed", e);
    }
  }

  private void setPreparedStatementValue(PreparedStatement statement, int index, Object value)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, java.sql.Types.NULL);
    } else if (value instanceof String) {
      statement.setString(index, (String) value);
    } else if (value instanceof Integer) {
      statement.setInt(index, (Integer) value);
    } else if (value instanceof Long) {
      statement.setLong(index, (Long) value);
    } else if (value instanceof Double) {
      statement.setDouble(index, (Double) value);
    } else if (value instanceof Boolean) {
      statement.setBoolean(index, (Boolean) value);
    } else if (value instanceof Timestamp) {
      statement.setTimestamp(index, (Timestamp) value);
    } else if (value instanceof java.util.Date) {
      statement.setTimestamp(index, new Timestamp(((java.util.Date) value).getTime()));
    } else {
      // For complex objects, convert to string (JSON format)
      statement.setString(index, value.toString());
    }
  }
}
