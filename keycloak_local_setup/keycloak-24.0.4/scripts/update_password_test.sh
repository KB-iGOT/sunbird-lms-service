#!/bin/bash

# Keycloak 24.0.4 - Generate Required Action Links
KEYCLOAK_URL="http://localhost:8080"
REALM="sunbird"
ADMIN_USER="admin"
ADMIN_PASS="admin123"

echo "Getting admin access token..."

# Get admin token from master realm
TOKEN_RESPONSE=$(curl -s -X POST \
  "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "grant_type=password" \
  -d "client_id=admin-cli" \
  -d "username=${ADMIN_USER}" \
  -d "password=${ADMIN_PASS}")

ACCESS_TOKEN=$(echo $TOKEN_RESPONSE | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)

if [ -z "$ACCESS_TOKEN" ]; then
    echo "Failed to get access token"
    exit 1
fi

echo "Access token obtained"

# Function to get user by username
get_user_by_username() {
    local username=$1
    curl -s -X GET \
        "${KEYCLOAK_URL}/admin/realms/${REALM}/users?username=${username}" \
        -H "Authorization: Bearer ${ACCESS_TOKEN}" \
        -H 'Content-Type: application/json'
}

# Function to send required action email (Method 1 - Email)
send_required_action_email() {
    local user_id=$1
    local client_id=$2
    local redirect_uri=$3
    local lifespan=$4
    
    echo "Sending required action email to user: $user_id"
    
    curl -X PUT \
        "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${user_id}/execute-actions-email" \
        -H "Authorization: Bearer ${ACCESS_TOKEN}" \
        -H 'Content-Type: application/json' \
        -d "[\"UPDATE_PASSWORD\"]" \
        --get \
        --data-urlencode "client_id=${client_id}" \
        --data-urlencode "redirect_uri=${redirect_uri}" \
        --data-urlencode "lifespan=${lifespan}"
}

# Function to generate action token (Method 2 - Direct Token)
generate_action_token() {
    local user_id=$1
    local client_id=$2
    local redirect_uri=$3
    local lifespan=$4
    
    echo "Generating action token for user: $user_id"
    
    curl -X POST \
        "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${user_id}/execute-actions-email" \
        -H "Authorization: Bearer ${ACCESS_TOKEN}" \
        -H 'Content-Type: application/json' \
        -d "[\"UPDATE_PASSWORD\"]" \
        --get \
        --data-urlencode "client_id=${client_id}" \
        --data-urlencode "redirect_uri=${redirect_uri}" \
        --data-urlencode "lifespan=${lifespan}"
}

# Alternative: Create action link manually (Method 3)
create_action_link_manual() {
    local username=$1
    local client_id=$2
    local redirect_uri=$3
    local lifespan=$4
    
    echo "Creating action link for username: $username"
    
    # First get user ID
    USER_DATA=$(get_user_by_username "$username")
    USER_ID=$(echo "$USER_DATA" | jq -r '.[0].id')
    
    if [ "$USER_ID" = "null" ] || [ -z "$USER_ID" ]; then
        echo "User not found: $username"
        return 1
    fi
    
    echo "Found user ID: $USER_ID"
    
    # Method 3a: Set required action and let user login normally
    curl -X PUT \
        "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${USER_ID}" \
        -H "Authorization: Bearer ${ACCESS_TOKEN}" \
        -H 'Content-Type: application/json' \
        -d '{
            "requiredActions": ["UPDATE_PASSWORD"]
        }'
    
    echo "Required action UPDATE_PASSWORD set for user"
    echo "User will be prompted to update password on next login"
    
    # Method 3b: Generate password reset link (if you need a direct link)
    echo ""
    echo "Sending password reset email..."
    curl -X PUT \
        "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${USER_ID}/reset-password-email" \
        -H "Authorization: Bearer ${ACCESS_TOKEN}" \
        -H 'Content-Type: application/json' \
        --get \
        --data-urlencode "client_id=${client_id}" \
        --data-urlencode "redirect_uri=${redirect_uri}"
}

# Example usage - replace these values with your actual data
USERNAME="jptestuser_dud8"
CLIENT_ID="lms"
REDIRECT_URI="https://dev.sunbirded.org"
LIFESPAN="155520000"  # in seconds

echo ""
echo "=== Keycloak 24.0.4 Required Action Link Generation ==="
echo ""

# Get user first
echo "1. Getting user information..."
USER_DATA=$(get_user_by_username "$USERNAME")
echo "User data: $USER_DATA"

USER_ID=$(echo "$USER_DATA" | jq -r '.[0].id // empty')

if [ -z "$USER_ID" ]; then
    echo "User not found: $USERNAME"
    exit 1
fi

echo "Found user ID: $USER_ID"
echo ""

# Choose one of these methods:

# Method 1: Send action email (recommended)
echo "2. Sending required action email..."
send_required_action_email "$USER_ID" "$CLIENT_ID" "$REDIRECT_URI" "$LIFESPAN"
echo ""

# Method 2: Set required action manually (alternative)
echo "3. Setting required action manually..."
create_action_link_manual "$USERNAME" "$CLIENT_ID" "$REDIRECT_URI" "$LIFESPAN"

echo ""
echo "Script completed"
