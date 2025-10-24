package org.sunbird.postgresql;

import java.util.List;
import java.util.Map;
import org.sunbird.request.RequestContext;
import org.sunbird.response.Response;

/**
 * PostgreSQL database operation interface - equivalent to CassandraOperation Provides CRUD
 * operations for PostgreSQL database
 *
 * @author System Migration Team
 */
public interface PostgreSQLOperation {

  /**
   * Insert/Update record in PostgreSQL (upsert operation using ON CONFLICT)
   *
   * @param schemaName String (database schema name)
   * @param tableName String
   * @param request Map<String,Object> (column name and value pairs)
   * @param context RequestContext
   * @return Response
   */
  Response upsertRecord(
      String schemaName, String tableName, Map<String, Object> request, RequestContext context);

  /**
   * Insert record in PostgreSQL
   *
   * @param schemaName Schema name
   * @param tableName Table name
   * @param request Map<String,Object> (column name and value pairs)
   * @param context RequestContext
   * @return Response
   */
  Response insertRecord(
      String schemaName, String tableName, Map<String, Object> request, RequestContext context);

  /**
   * Update record in PostgreSQL
   *
   * @param schemaName Schema name
   * @param tableName Table name
   * @param request Map<String,Object> (column name and value pairs)
   * @param context RequestContext
   * @return Response
   */
  Response updateRecord(
      String schemaName, String tableName, Map<String, Object> request, RequestContext context);

  /**
   * Delete record by primary key
   *
   * @param schemaName Schema name
   * @param tableName Table name
   * @param identifier Primary key value
   * @param context RequestContext
   * @return Response
   */
  Response deleteRecord(
      String schemaName, String tableName, String identifier, RequestContext context);

  /**
   * Get record by primary key
   *
   * @param schemaName Schema name
   * @param tableName Table name
   * @param key Primary key value
   * @param context RequestContext
   * @return Response
   */
  Response getRecordById(String schemaName, String tableName, String key, RequestContext context);

  /**
   * Get all records from table
   *
   * @param schemaName Schema name
   * @param tableName Table name
   * @param context RequestContext
   * @return Response
   */
  Response getAllRecords(String schemaName, String tableName, RequestContext context);

  /**
   * Get records by property values
   *
   * @param schemaName Schema name
   * @param tableName Table name
   * @param propertyMap Map of column name and value pairs for WHERE clause
   * @param context RequestContext
   * @return Response
   */
  Response getRecordsByProperties(
      String schemaName, String tableName, Map<String, Object> propertyMap, RequestContext context);

  /**
   * Batch insert records
   *
   * @param schemaName Schema name
   * @param tableName Table name
   * @param records List of records to insert
   * @param context RequestContext
   * @return Response
   */
  Response batchInsert(
      String schemaName,
      String tableName,
      List<Map<String, Object>> records,
      RequestContext context);
}
