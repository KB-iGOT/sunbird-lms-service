package org.sunbird.postgresql.helper;

import org.sunbird.postgresql.PostgreSQLOperation;
import org.sunbird.postgresql.impl.PostgreSQLOperationImpl;

/**
 * PostgreSQL Service Factory - equivalent to ServiceFactory for Cassandra Provides
 * PostgreSQLOperation instance with singleton pattern
 *
 * @author System Migration Team
 */
public class PostgreSQLServiceFactory {

  private static PostgreSQLOperation operation = null;

  private PostgreSQLServiceFactory() {}

  /**
   * Returns a singleton instance of PostgreSQLOperation
   *
   * @return PostgreSQLOperation instance
   */
  public static PostgreSQLOperation getInstance() {
    if (null == operation) {
      synchronized (PostgreSQLServiceFactory.class) {
        if (null == operation) {
          operation = new PostgreSQLOperationImpl();
        }
      }
    }
    return operation;
  }
}
