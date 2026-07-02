#!/bin/bash

# Script to get User Federation Provider ID from Keycloak Admin API

# Configuration
KEYCLOAK_URL="http://localhost:8080"  # Change to your Keycloak URL
REALM="sunbird"                       # Your realm name
ADMIN_USERNAME="admin"                # Admin username
ADMIN_PASSWORD="sunbird"                # Admin password
CLIENT_ID="admin-cli"                 # Admin CLI client

# Get admin access token
echo "Getting admin access token..."
TOKEN_RESPONSE=$(curl -s -X POST \
  "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=$ADMIN_USERNAME" \
  -d "password=$ADMIN_PASSWORD" \
  -d "grant_type=password" \
  -d "client_id=$CLIENT_ID")

ACCESS_TOKEN=$(echo $TOKEN_RESPONSE | jq -r '.access_token')

if [ "$ACCESS_TOKEN" = "null" ]; then
    echo "Failed to get access token"
    echo "Response: $TOKEN_RESPONSE"
    exit 1
fi

echo "Access token obtained successfully"

# Get all user federation providers for the realm
echo "Getting user federation providers for realm: $REALM"
PROVIDERS_RESPONSE=$(curl -s -X GET \
  "$KEYCLOAK_URL/admin/realms/$REALM/components?type=org.keycloak.storage.UserStorageProvider" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json")

echo "User Federation Providers:"
echo "$PROVIDERS_RESPONSE" | jq -r '.[] | "ID: \(.id), Name: \(.name), Provider ID: \(.providerId)"'

# Extract Cassandra provider specifically
echo ""
echo "Cassandra Storage Provider Details:"
echo "$PROVIDERS_RESPONSE" | jq -r '.[] | select(.name | contains("cassandra")) | "Provider ID: \(.id), Name: \(.name), Provider Type: \(.providerId)"'

# Also check the component details
echo ""
echo "Full details for Cassandra provider:"
echo "$PROVIDERS_RESPONSE" | jq '.[] | select(.name | contains("cassandra"))'
