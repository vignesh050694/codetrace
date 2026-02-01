#!/bin/bash

# Script to test the Project Management API
# Make sure the application is running before executing this script

BASE_URL="http://localhost:8080/api/projects"

echo "=========================================="
echo "Testing Orkestify Project Management API"
echo "=========================================="
echo ""

# Test 1: Create a new project
echo "1. Creating a new project..."
CREATE_RESPONSE=$(curl -s -X POST $BASE_URL \
  -H "Content-Type: application/json" \
  -d '{"name": "Test Project"}')

echo "Response: $CREATE_RESPONSE"
PROJECT_ID=$(echo $CREATE_RESPONSE | grep -o '"id":"[^"]*"' | cut -d'"' -f4)
echo "Project ID: $PROJECT_ID"
echo ""

# Test 2: Get all projects
echo "2. Getting all projects..."
curl -s -X GET $BASE_URL | json_pp
echo ""

# Test 3: Get project by ID
echo "3. Getting project by ID..."
curl -s -X GET "$BASE_URL/$PROJECT_ID" | json_pp
echo ""

# Test 4: Update project with GitHub URLs
echo "4. Updating project with GitHub URLs..."
curl -s -X PUT "$BASE_URL/$PROJECT_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Updated Test Project",
    "githubUrls": [
      "https://github.com/test/repo1",
      "https://github.com/test/repo2"
    ]
  }' | json_pp
echo ""

# Test 5: Get active projects
echo "5. Getting active projects..."
curl -s -X GET "$BASE_URL?status=active" | json_pp
echo ""

# Test 6: Archive project
echo "6. Archiving project..."
curl -s -X PATCH "$BASE_URL/$PROJECT_ID/archive" | json_pp
echo ""

# Test 7: Get archived projects
echo "7. Getting archived projects..."
curl -s -X GET "$BASE_URL?status=archived" | json_pp
echo ""

# Test 8: Unarchive project
echo "8. Unarchiving project..."
curl -s -X PATCH "$BASE_URL/$PROJECT_ID/unarchive" | json_pp
echo ""

# Test 9: Search projects
echo "9. Searching projects..."
curl -s -X GET "$BASE_URL?search=Test" | json_pp
echo ""

# Test 10: Delete project
echo "10. Deleting project..."
curl -s -X DELETE "$BASE_URL/$PROJECT_ID" -w "\nHTTP Status: %{http_code}\n"
echo ""

echo "=========================================="
echo "All tests completed!"
echo "=========================================="
