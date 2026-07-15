#!/bin/bash

# Keycloak 24.0.4 with Java 17 Setup Script
# This script sets up Keycloak 24.0.4 with PostgreSQL 15.14

set -e  # Exit on any error

echo "Starting Keycloak 24.0.4 setup..."

# Create directory structure
mkdir -p $HOME/sunbird-dbs
export SUNBIRD_DBS_PATH=$HOME/sunbird-dbs
echo "Database path: $SUNBIRD_DBS_PATH"

# Create Docker network
echo "Creating Docker network..."
sudo docker network create keycloak-postgres-network || echo "Network already exists"

# Start PostgreSQL container
echo "Starting PostgreSQL container..."
sudo docker run --name=kc_postgres \
  --net keycloak-postgres-network \
  -e POSTGRES_PASSWORD=kcpgpassword \
  -e POSTGRES_USER=kcpgadmin \
  -e POSTGRES_DB=keycloakdb \
  -p 32769:5432 \
  -d postgres:15.14

echo "PostgreSQL container created."

# Wait for PostgreSQL to be ready
echo "Waiting for PostgreSQL to be ready..."
sleep 10

# Create Keycloak directory structure
echo "Creating directory structure..."
mkdir -p $SUNBIRD_DBS_PATH/keycloak/tmp
mkdir -p $SUNBIRD_DBS_PATH/keycloak/themes
mkdir -p $SUNBIRD_DBS_PATH/keycloak/conf
mkdir -p $SUNBIRD_DBS_PATH/keycloak/realm
mkdir -p $SUNBIRD_DBS_PATH/keycloak/providers

# Copy files if they exist (uncomment and modify paths as needed)
 cp -r themes/* $SUNBIRD_DBS_PATH/keycloak/themes/ 2>/dev/null || echo "No themes directory found, skipping..."
 cp -r conf/* $SUNBIRD_DBS_PATH/keycloak/conf/ 2>/dev/null || echo "No conf directory found, skipping..."
 cp -r realm/* $SUNBIRD_DBS_PATH/keycloak/realm/ 2>/dev/null || echo "No realm directory found, skipping..."
 cp -r providers/* $SUNBIRD_DBS_PATH/keycloak/providers/ 2>/dev/null || echo "No providers directory found, skipping..."

# Disable firewall (optional - be careful with this in production)
# ufw disable

# Get PostgreSQL container IP
POSTGRES_IP=$(docker inspect -f '{{range.NetworkSettings.Networks}}{{.IPAddress}}{{end}}' kc_postgres)
echo "PostgreSQL IP: $POSTGRES_IP"

# Start Keycloak container
echo "Starting Keycloak 24.0.4 container..."
sudo docker run --name kc_local -p 8080:8080 \
        -e KEYCLOAK_ADMIN=admin \
        -e KEYCLOAK_ADMIN_PASSWORD=sunbird \
        -e KC_DB=postgres \
        -e KC_DB_URL="jdbc:postgresql://kc_postgres:5432/keycloakdb" \
        -e KC_DB_USERNAME=kcpgadmin \
        -e KC_DB_PASSWORD=kcpgpassword \
        -e KC_HOSTNAME_STRICT=false \
        -e KC_HOSTNAME_STRICT_HTTPS=false \
        -e KC_HTTP_ENABLED=true \
        --add-host=host.docker.internal:host-gateway \
        -e sunbird_user_service_base_url="http://host.docker.internal:9000" \
        -v $SUNBIRD_DBS_PATH/keycloak/tmp:/tmp \
        -v $SUNBIRD_DBS_PATH/keycloak/themes:/opt/keycloak/themes \
        -v $SUNBIRD_DBS_PATH/keycloak/providers:/opt/keycloak/providers \
        -v $SUNBIRD_DBS_PATH/keycloak/realm:/opt/keycloak/data/import \
        --net keycloak-postgres-network \
        -d quay.io/keycloak/keycloak:24.0.4 start-dev

echo "Keycloak container created."

# Wait for Keycloak to start
echo "Waiting for Keycloak to start..."
sleep 30

# Copy custom themes if they exist
if [ -d "$SUNBIRD_DBS_PATH/keycloak/themes/sunbird" ]; then
    sudo docker cp $SUNBIRD_DBS_PATH/keycloak/themes/sunbird kc_local:/opt/keycloak/themes/sunbird
    echo "Sunbird themes copied to container."
else
    echo "No sunbird themes found, skipping..."
fi

# Copy configuration if it exists
if [ -f "$SUNBIRD_DBS_PATH/keycloak/conf/keycloak.conf" ]; then
    sudo docker cp $SUNBIRD_DBS_PATH/keycloak/conf/keycloak.conf kc_local:/opt/keycloak/conf/keycloak.conf
    echo "Configuration copied to container."
else
    echo "No keycloak.conf found, skipping..."
fi

# Import realm if it exists
if [ -f "$SUNBIRD_DBS_PATH/keycloak/realm/sunbird-realm.json" ]; then
    echo "Importing realm..."
    sudo docker exec kc_local /opt/keycloak/bin/kc.sh import --file /opt/keycloak/data/import/sunbird-realm.json
    echo "Realm imported."
else
    echo "No realm file found, skipping realm import..."
    echo "Expected realm file at: $SUNBIRD_DBS_PATH/keycloak/realm/sunbird-realm.json"
fi

# Restart container to apply changes
echo "Restarting Keycloak container..."
sudo docker container restart kc_local

echo "Waiting for Keycloak to restart..."
sleep 20

echo ""
echo "========================================="
echo "Keycloak 24.0.4 setup completed!"
echo "========================================="
echo "Keycloak Admin Console: http://localhost:8080"
echo "Username: admin"
echo "Password: sunbird"
echo ""
echo "PostgreSQL Database:"
echo "Host: localhost:32769"
echo "Database: keycloakdb"
echo "Username: kcpgadmin"
echo "Password: kcpgpassword"
echo "========================================="

# Check container status
echo "Container status:"
sudo docker ps --filter name=kc_local --filter name=kc_postgres

echo ""
echo "To view Keycloak logs: docker logs kc_local"
echo "To view PostgreSQL logs: docker logs kc_postgres"