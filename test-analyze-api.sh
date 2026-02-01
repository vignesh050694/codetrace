#!/bin/bash

# Project Analysis API Test Script
BASE_URL="http://localhost:8080"

echo "========================================"
echo "Project Analysis API Test"
echo "========================================"
echo ""

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test 1: Register and login a user
echo -e "${BLUE}Step 1: Register and Login User${NC}"
LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "password123"
  }')

TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.token')

if [ "$TOKEN" == "null" ] || [ -z "$TOKEN" ]; then
    echo -e "${YELLOW}User not found, registering new user...${NC}"
    REGISTER_RESPONSE=$(curl -s -X POST "$BASE_URL/api/auth/register" \
      -H "Content-Type: application/json" \
      -d '{
        "username": "testuser",
        "email": "testuser@example.com",
        "password": "password123",
        "fullName": "Test User"
      }')
    TOKEN=$(echo "$REGISTER_RESPONSE" | jq -r '.token')
fi

if [ "$TOKEN" != "null" ] && [ -n "$TOKEN" ]; then
    echo -e "${GREEN}✓ User authenticated successfully${NC}"
    echo "Token: ${TOKEN:0:50}..."
else
    echo -e "${RED}✗ Authentication failed${NC}"
    exit 1
fi
echo ""

# Test 2: Create a project with GitHub URLs
echo -e "${BLUE}Step 2: Create Project with GitHub URLs${NC}"
CREATE_PROJECT_RESPONSE=$(curl -s -X POST "$BASE_URL/api/projects" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "Analysis Test Project"
  }')

echo "$CREATE_PROJECT_RESPONSE" | jq .

PROJECT_ID=$(echo "$CREATE_PROJECT_RESPONSE" | jq -r '.id')

if [ "$PROJECT_ID" != "null" ] && [ -n "$PROJECT_ID" ]; then
    echo -e "${GREEN}✓ Project created successfully${NC}"
    echo "Project ID: $PROJECT_ID"
else
    echo -e "${RED}✗ Project creation failed${NC}"
    exit 1
fi
echo ""

# Test 3: Update project with GitHub repository URLs
echo -e "${BLUE}Step 3: Update Project with GitHub Repository URLs${NC}"
UPDATE_RESPONSE=$(curl -s -X PUT "$BASE_URL/api/projects/$PROJECT_ID" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "Analysis Test Project",
    "githubUrls": [
      "https://github.com/spring-projects/spring-boot.git",
      "https://github.com/torvalds/linux.git"
    ]
  }')

echo "$UPDATE_RESPONSE" | jq .
echo -e "${GREEN}✓ Project updated with GitHub URLs${NC}"
echo ""

# Test 4: Analyze the project
echo -e "${BLUE}Step 4: Analyze Project Repositories${NC}"
echo -e "${YELLOW}This will clone repositories and analyze them. This may take a minute...${NC}"
echo ""

ANALYZE_RESPONSE=$(curl -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/analyze" \
  -H "Authorization: Bearer $TOKEN")

echo "$ANALYZE_RESPONSE" | jq .

# Extract analysis results
STATUS=$(echo "$ANALYZE_RESPONSE" | jq -r '.status')
TOTAL=$(echo "$ANALYZE_RESPONSE" | jq -r '.totalRepositories')
CLONED=$(echo "$ANALYZE_RESPONSE" | jq -r '.successfullyCloned')
JAVA_REPOS=$(echo "$ANALYZE_RESPONSE" | jq -r '.javaRepositories')
NON_JAVA=$(echo "$ANALYZE_RESPONSE" | jq -r '.nonJavaRepositories')

echo ""
echo -e "${BLUE}========== Analysis Summary ==========${NC}"
echo -e "Status: ${GREEN}$STATUS${NC}"
echo -e "Total Repositories: $TOTAL"
echo -e "Successfully Cloned: $CLONED"
echo -e "Java Repositories: ${GREEN}$JAVA_REPOS${NC}"
echo -e "Non-Java Repositories: $NON_JAVA"
echo -e "${BLUE}======================================${NC}"
echo ""

# Test 5: Display detailed results for each repository
echo -e "${BLUE}Step 5: Detailed Repository Analysis${NC}"
echo "$ANALYZE_RESPONSE" | jq -r '.repositories[] |
    "
Repository: \(.repositoryUrl)
  - Cloned: \(.cloned)
  - Is Java: \(.isJavaRepository)
  - Has pom.xml: \(.javaProjectInfo.hasPomXml)
  - Has Gradle: \(.javaProjectInfo.hasGradleBuild)
  - Java Files: \(.javaProjectInfo.javaFileCount)
  - Build Tool: \(.javaProjectInfo.buildTool // "None")
  - Error: \(.errorMessage // "None")
"'

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Analysis completed successfully!${NC}"
echo -e "${GREEN}========================================${NC}"
