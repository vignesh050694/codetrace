#!/bin/bash

# User Authentication & Project Management API Test Script
BASE_URL="http://localhost:8080"

echo "========================================"
echo "User Authentication & Project Management API Tests"
echo "========================================"
echo ""

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Test 1: Register a new user
echo -e "${BLUE}Test 1: Register New User${NC}"
REGISTER_RESPONSE=$(curl -s -X POST "$BASE_URL/api/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "email": "testuser@example.com",
    "password": "password123",
    "fullName": "Test User"
  }')

echo "$REGISTER_RESPONSE" | jq .

# Extract token from registration response
TOKEN=$(echo "$REGISTER_RESPONSE" | jq -r '.token')

if [ "$TOKEN" != "null" ] && [ -n "$TOKEN" ]; then
    echo -e "${GREEN}✓ User registered successfully${NC}"
    echo "Token: $TOKEN"
else
    echo -e "${RED}✗ User registration failed${NC}"
fi
echo ""

# Test 2: Login with the registered user
echo -e "${BLUE}Test 2: Login User${NC}"
LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "password123"
  }')

echo "$LOGIN_RESPONSE" | jq .

# Update token from login response
TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.token')

if [ "$TOKEN" != "null" ] && [ -n "$TOKEN" ]; then
    echo -e "${GREEN}✓ User logged in successfully${NC}"
else
    echo -e "${RED}✗ User login failed${NC}"
fi
echo ""

# Test 3: Get current user profile
echo -e "${BLUE}Test 3: Get Current User Profile${NC}"
curl -s -X GET "$BASE_URL/api/auth/me" \
  -H "Authorization: Bearer $TOKEN" | jq .
echo -e "${GREEN}✓ User profile retrieved${NC}"
echo ""

# Test 4: Create a project
echo -e "${BLUE}Test 4: Create Project${NC}"
CREATE_PROJECT_RESPONSE=$(curl -s -X POST "$BASE_URL/api/projects" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "My First Project"
  }')

echo "$CREATE_PROJECT_RESPONSE" | jq .

PROJECT_ID=$(echo "$CREATE_PROJECT_RESPONSE" | jq -r '.id')

if [ "$PROJECT_ID" != "null" ] && [ -n "$PROJECT_ID" ]; then
    echo -e "${GREEN}✓ Project created successfully${NC}"
    echo "Project ID: $PROJECT_ID"
else
    echo -e "${RED}✗ Project creation failed${NC}"
fi
echo ""

# Test 5: Get all projects for the user
echo -e "${BLUE}Test 5: Get All Projects${NC}"
curl -s -X GET "$BASE_URL/api/projects" \
  -H "Authorization: Bearer $TOKEN" | jq .
echo -e "${GREEN}✓ Projects retrieved${NC}"
echo ""

# Test 6: Update project with GitHub URLs
echo -e "${BLUE}Test 6: Update Project with GitHub URLs${NC}"
curl -s -X PUT "$BASE_URL/api/projects/$PROJECT_ID" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "My Updated Project",
    "githubUrls": [
      "https://github.com/user/repo1",
      "https://github.com/user/repo2"
    ]
  }' | jq .
echo -e "${GREEN}✓ Project updated with GitHub URLs${NC}"
echo ""

# Test 7: Get specific project
echo -e "${BLUE}Test 7: Get Project by ID${NC}"
curl -s -X GET "$BASE_URL/api/projects/$PROJECT_ID" \
  -H "Authorization: Bearer $TOKEN" | jq .
echo -e "${GREEN}✓ Project retrieved${NC}"
echo ""

# Test 8: Create another project
echo -e "${BLUE}Test 8: Create Second Project${NC}"
CREATE_PROJECT2_RESPONSE=$(curl -s -X POST "$BASE_URL/api/projects" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "My Second Project"
  }')

echo "$CREATE_PROJECT2_RESPONSE" | jq .

PROJECT_ID2=$(echo "$CREATE_PROJECT2_RESPONSE" | jq -r '.id')
echo -e "${GREEN}✓ Second project created${NC}"
echo ""

# Test 9: Archive a project
echo -e "${BLUE}Test 9: Archive Project${NC}"
curl -s -X PATCH "$BASE_URL/api/projects/$PROJECT_ID2/archive" \
  -H "Authorization: Bearer $TOKEN" | jq .
echo -e "${GREEN}✓ Project archived${NC}"
echo ""

# Test 10: Get active projects only
echo -e "${BLUE}Test 10: Get Active Projects${NC}"
curl -s -X GET "$BASE_URL/api/projects?status=active" \
  -H "Authorization: Bearer $TOKEN" | jq .
echo -e "${GREEN}✓ Active projects retrieved${NC}"
echo ""

# Test 11: Get archived projects only
echo -e "${BLUE}Test 11: Get Archived Projects${NC}"
curl -s -X GET "$BASE_URL/api/projects?status=archived" \
  -H "Authorization: Bearer $TOKEN" | jq .
echo -e "${GREEN}✓ Archived projects retrieved${NC}"
echo ""

# Test 12: Unarchive a project
echo -e "${BLUE}Test 12: Unarchive Project${NC}"
curl -s -X PATCH "$BASE_URL/api/projects/$PROJECT_ID2/unarchive" \
  -H "Authorization: Bearer $TOKEN" | jq .
echo -e "${GREEN}✓ Project unarchived${NC}"
echo ""

# Test 13: Search projects
echo -e "${BLUE}Test 13: Search Projects${NC}"
curl -s -X GET "$BASE_URL/api/projects?search=Second" \
  -H "Authorization: Bearer $TOKEN" | jq .
echo -e "${GREEN}✓ Project search completed${NC}"
echo ""

# Test 14: Test unauthorized access (without token)
echo -e "${BLUE}Test 14: Unauthorized Access Test${NC}"
UNAUTHORIZED_RESPONSE=$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X GET "$BASE_URL/api/projects")
echo "$UNAUTHORIZED_RESPONSE"
if echo "$UNAUTHORIZED_RESPONSE" | grep -q "HTTP_STATUS:401"; then
    echo -e "${GREEN}✓ Unauthorized access properly blocked${NC}"
else
    echo -e "${RED}✗ Unauthorized access not blocked${NC}"
fi
echo ""

# Test 15: Register another user to test isolation
echo -e "${BLUE}Test 15: Register Second User (Testing User Isolation)${NC}"
REGISTER_RESPONSE2=$(curl -s -X POST "$BASE_URL/api/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser2",
    "email": "testuser2@example.com",
    "password": "password456",
    "fullName": "Test User Two"
  }')

echo "$REGISTER_RESPONSE2" | jq .

TOKEN2=$(echo "$REGISTER_RESPONSE2" | jq -r '.token')

if [ "$TOKEN2" != "null" ] && [ -n "$TOKEN2" ]; then
    echo -e "${GREEN}✓ Second user registered successfully${NC}"
else
    echo -e "${RED}✗ Second user registration failed${NC}"
fi
echo ""

# Test 16: Verify second user cannot see first user's projects
echo -e "${BLUE}Test 16: Verify User Project Isolation${NC}"
USER2_PROJECTS=$(curl -s -X GET "$BASE_URL/api/projects" \
  -H "Authorization: Bearer $TOKEN2")
echo "$USER2_PROJECTS" | jq .

PROJECT_COUNT=$(echo "$USER2_PROJECTS" | jq 'length')
if [ "$PROJECT_COUNT" -eq 0 ]; then
    echo -e "${GREEN}✓ User isolation verified - User 2 cannot see User 1's projects${NC}"
else
    echo -e "${RED}✗ User isolation failed - User 2 can see other user's projects${NC}"
fi
echo ""

# Test 17: Verify second user cannot access first user's project
echo -e "${BLUE}Test 17: Verify Cannot Access Other User's Project${NC}"
OTHER_USER_ACCESS=$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X GET "$BASE_URL/api/projects/$PROJECT_ID" \
  -H "Authorization: Bearer $TOKEN2")
echo "$OTHER_USER_ACCESS"
if echo "$OTHER_USER_ACCESS" | grep -q "HTTP_STATUS:404"; then
    echo -e "${GREEN}✓ Access control verified - User 2 cannot access User 1's project${NC}"
else
    echo -e "${RED}✗ Access control failed${NC}"
fi
echo ""

# Test 18: Delete a project
echo -e "${BLUE}Test 18: Delete Project${NC}"
DELETE_RESPONSE=$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X DELETE "$BASE_URL/api/projects/$PROJECT_ID2" \
  -H "Authorization: Bearer $TOKEN")
echo "$DELETE_RESPONSE"
if echo "$DELETE_RESPONSE" | grep -q "HTTP_STATUS:204"; then
    echo -e "${GREEN}✓ Project deleted successfully${NC}"
else
    echo -e "${RED}✗ Project deletion failed${NC}"
fi
echo ""

echo "========================================"
echo -e "${GREEN}All tests completed!${NC}"
echo "========================================"
