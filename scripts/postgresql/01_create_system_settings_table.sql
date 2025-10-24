-- PostgreSQL script to create system_settings table
-- Compatible with PostgreSQL 10.15

-- Create schema if it doesn't exist
CREATE SCHEMA IF NOT EXISTS sunbird;

-- Create system_settings table
CREATE TABLE IF NOT EXISTS sunbird.system_settings (
    id VARCHAR(255) PRIMARY KEY,
    field VARCHAR(255) NOT NULL,
    value TEXT,
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes for better performance
CREATE INDEX IF NOT EXISTS idx_system_settings_field ON sunbird.system_settings(field);
CREATE INDEX IF NOT EXISTS idx_system_settings_created_date ON sunbird.system_settings(created_date);

-- Create trigger to update updated_date automatically
CREATE OR REPLACE FUNCTION sunbird.update_updated_date_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_date = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_system_settings_updated_date 
    BEFORE UPDATE ON sunbird.system_settings 
    FOR EACH ROW 
    EXECUTE FUNCTION sunbird.update_updated_date_column();

-- Grant permissions (adjust as needed for your setup)
-- GRANT ALL PRIVILEGES ON SCHEMA sunbird TO your_app_user;
-- GRANT ALL PRIVILEGES ON TABLE sunbird.system_settings TO your_app_user;
-- GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA sunbird TO your_app_user;