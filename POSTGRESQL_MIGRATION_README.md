# System Settings Migration from Cassandra to PostgreSQL

This document provides comprehensive instructions for migrating the system settings table from Cassandra to PostgreSQL.

## Overview

The migration involves:
1. Setting up PostgreSQL database
2. Creating the system_settings table structure
3. Migrating existing data from Cassandra
4. Switching the application to use PostgreSQL
5. Verifying the migration

## Prerequisites

- PostgreSQL 10.15 or higher installed and running
- Access to existing Cassandra database
- Java application with appropriate permissions

## Migration Steps

### Step 1: Setup PostgreSQL Database

1. **Create Database and Schema:**
   ```sql
   -- Connect to PostgreSQL as superuser
   CREATE DATABASE sunbird;
   
   -- Connect to sunbird database
   \c sunbird;
   
   -- Create schema
   CREATE SCHEMA IF NOT EXISTS sunbird;
   ```

2. **Create System Settings Table:**
   ```bash
   # Execute the table creation script
   psql -h localhost -U postgres -d sunbird -f scripts/postgresql/01_create_system_settings_table.sql
   ```

3. **Migrate Data:**
   ```bash
   # Execute the data migration script
   psql -h localhost -U postgres -d sunbird -f scripts/postgresql/02_migrate_system_settings_data.sql
   ```

### Step 2: Configure Application

1. **Set Environment Variables:**
   ```bash
   export SUNBIRD_SYSTEM_SETTINGS_DB_TYPE=postgresql
   export SUNBIRD_PG_HOST=localhost
   export SUNBIRD_PG_PORT=5432
   export SUNBIRD_PG_DB=sunbird
   export SUNBIRD_PG_USER=postgres
   export SUNBIRD_PG_PASSWORD=your_password
   ```

2. **Or use System Properties:**
   ```bash
   -Dsunbird_system_settings_db_type=postgresql
   -Dsunbird_pg_host=localhost
   -Dsunbird_pg_port=5432
   -Dsunbird_pg_db=sunbird
   -Dsunbird_pg_user=postgres
   -Dsunbird_pg_password=your_password
   ```

3. **Or update application.properties:**
   ```properties
   sunbird_system_settings_db_type=postgresql
   sunbird_pg_host=localhost
   sunbird_pg_port=5432
   sunbird_pg_db=sunbird
   sunbird_pg_user=postgres
   sunbird_pg_password=your_password
   ```

### Step 3: Programmatic Migration (Optional)

If you prefer to migrate data programmatically:

```java
// Create migration utility
SystemSettingsMigrationUtil migrationUtil = new SystemSettingsMigrationUtil();
RequestContext context = new RequestContext();

// Perform migration
MigrationResult result = migrationUtil.migrateSystemSettings(context);

// Verify migration
boolean countVerified = migrationUtil.verifyMigration(context);
boolean dataVerified = migrationUtil.verifyDataIntegrity(context, 5);

System.out.println("Migration Result: " + result);
System.out.println("Count Verified: " + countVerified);
System.out.println("Data Verified: " + dataVerified);
```

### Step 4: Testing

1. **Test Database Connection:**
   ```java
   PostgreSQLConnectionManager manager = PostgreSQLConnectionManager.getInstance();
   boolean healthy = manager.isHealthy();
   System.out.println("PostgreSQL Connection Healthy: " + healthy);
   ```

2. **Test System Settings Operations:**
   ```java
   SystemSettingDao dao = new SystemSettingDaoImpl();
   RequestContext context = new RequestContext();
   
   // Test read all
   List<SystemSetting> allSettings = dao.readAll(context);
   System.out.println("Total Settings: " + allSettings.size());
   
   // Test read by ID
   SystemSetting setting = dao.readById("emailUnique", context);
   System.out.println("Email Unique Setting: " + setting);
   ```

### Step 5: Rollback Plan (if needed)

To rollback to Cassandra:

1. **Change Configuration:**
   ```bash
   export SUNBIRD_SYSTEM_SETTINGS_DB_TYPE=cassandra
   # or remove the environment variable to use default
   unset SUNBIRD_SYSTEM_SETTINGS_DB_TYPE
   ```

2. **Restart Application**

## Database Schema

### PostgreSQL Table Structure
```sql
CREATE TABLE sunbird.system_settings (
    id VARCHAR(255) PRIMARY KEY,
    field VARCHAR(255) NOT NULL,
    value TEXT,
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### Indexes
```sql
CREATE INDEX idx_system_settings_field ON sunbird.system_settings(field);
CREATE INDEX idx_system_settings_created_date ON sunbird.system_settings(created_date);
```

## Configuration Parameters

| Parameter | Description | Default |
|-----------|-------------|---------|
| `sunbird_system_settings_db_type` | Database type: 'cassandra' or 'postgresql' | cassandra |
| `sunbird_pg_host` | PostgreSQL host | localhost |
| `sunbird_pg_port` | PostgreSQL port | 5432 |
| `sunbird_pg_db` | PostgreSQL database name | sunbird |
| `sunbird_pg_user` | PostgreSQL username | postgres |
| `sunbird_pg_password` | PostgreSQL password | password |
| `sunbird_pg_pool_max_size` | Maximum connection pool size | 10 |
| `sunbird_pg_pool_min_idle` | Minimum idle connections | 2 |
| `sunbird_pg_connection_timeout` | Connection timeout (ms) | 30000 |
| `sunbird_pg_idle_timeout` | Idle timeout (ms) | 600000 |
| `sunbird_pg_max_lifetime` | Maximum connection lifetime (ms) | 1800000 |

## Migration Data

The migration includes the following system settings:
- shadowdbmandatorycolumn
- wfDomainServiceConfig
- channelRegStatus
- emailUnique
- Certificate templates (Bihar, Andaman, etc.)
- Workflow configurations
- User profile configurations
- And more...

## Troubleshooting

### Common Issues

1. **Connection Failed:**
   - Check PostgreSQL is running
   - Verify connection parameters
   - Check firewall settings

2. **Migration Errors:**
   - Check PostgreSQL permissions
   - Verify table exists
   - Check data format compatibility

3. **Performance Issues:**
   - Adjust connection pool settings
   - Add appropriate indexes
   - Monitor query performance

### Logs

Monitor application logs for:
- PostgreSQL connection messages
- Migration progress
- Database operation errors

### Health Checks

```java
// Check PostgreSQL connection health
PostgreSQLConnectionManager.getInstance().isHealthy()

// Verify data migration
SystemSettingsMigrationUtil util = new SystemSettingsMigrationUtil();
util.verifyMigration(context);
util.verifyDataIntegrity(context, 10);
```

## Performance Considerations

1. **Connection Pooling:** HikariCP is used for efficient connection management
2. **Prepared Statements:** All queries use prepared statements for better performance
3. **Batch Operations:** Bulk operations are supported for better throughput
4. **Indexes:** Appropriate indexes are created for common query patterns

## Security Considerations

1. **Connection Security:** Use SSL connections in production
2. **Credentials:** Store database credentials securely
3. **Access Control:** Implement proper database user permissions
4. **Audit:** Enable database audit logging if required

## Maintenance

1. **Backup:** Regular database backups
2. **Monitoring:** Monitor connection pool metrics
3. **Updates:** Keep PostgreSQL driver updated
4. **Cleanup:** Monitor and clean old connections if needed