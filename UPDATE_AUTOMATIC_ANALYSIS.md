# Update: Automatic Repository Analysis

## Change Summary

### What Changed
The `/api/projects/{projectId}/analyze-code` endpoint has been updated to **automatically analyze ALL repositories** configured in the project. Users no longer need to specify individual repository URLs.

### Before
```bash
POST /api/projects/{projectId}/analyze-code?repositoryUrl={url}
```
- Required explicit `repositoryUrl` query parameter
- Analyzed only one repository at a time
- User had to make multiple requests for multiple repositories

### After
```bash
POST /api/projects/{projectId}/analyze-code
```
- **No repository URL needed**
- Automatically retrieves ALL repository URLs from the project
- Analyzes all repositories in a single request
- Returns array of results (one per repository)

## Benefits

1. **Simpler API** - No need to pass repository URLs
2. **Single Request** - Analyze all repositories at once
3. **Complete Results** - Get comprehensive analysis of entire project
4. **Error Resilient** - If one repo fails, others still complete
5. **User Friendly** - Just click "Analyze" on a project

## API Changes

### Endpoint
```
POST /api/projects/{projectId}/analyze-code
Authorization: Bearer {token}
```

### Request
```bash
curl -X POST "http://localhost:8080/api/projects/65f123.../analyze-code" \
  -H "Authorization: Bearer eyJhbGc..."
```

### Response
Returns **array** of `CodeAnalysisResponse` objects:

```json
[
  {
    "projectId": "proj_123",
    "repoUrl": "https://github.com/user/repo1.git",
    "analyzedAt": "2026-01-31T21:00:00",
    "status": "COMPLETED",
    "totalClasses": 15,
    "totalMethods": 45,
    "controllers": [...],
    "services": [...],
    "repositories": [...]
  },
  {
    "projectId": "proj_123",
    "repoUrl": "https://github.com/user/repo2.git",
    "analyzedAt": "2026-01-31T21:02:00",
    "status": "COMPLETED",
    "totalClasses": 8,
    "totalMethods": 23,
    "controllers": [...],
    "services": [...],
    "repositories": [...]
  }
]
```

## Implementation Details

### Controller Change
```java
// Before
@PostMapping("/{id}/analyze-code")
public ResponseEntity<CodeAnalysisResponse> analyzeRepositoryCode(
    @PathVariable String id,
    @RequestParam String repositoryUrl) {
    ...
}

// After
@PostMapping("/{id}/analyze-code")
public ResponseEntity<List<CodeAnalysisResponse>> analyzeProjectCode(
    @PathVariable String id) {
    ...
}
```

### Service Change
```java
// New method in ProjectService
public List<CodeAnalysisResponse> analyzeProjectCode(String projectId, String username) {
    // 1. Get project and validate user ownership
    // 2. Get all githubUrls from project
    // 3. Loop through each repository URL
    // 4. Analyze each repository
    // 5. Return array of results
    // 6. Handle errors gracefully (failed repos get error status)
}
```

## Error Handling

If a repository fails to analyze, the response includes:
```json
{
  "projectId": "proj_123",
  "repoUrl": "https://github.com/user/failed-repo.git",
  "analyzedAt": "2026-01-31T21:00:00",
  "status": "FAILED: Git clone failed",
  "totalClasses": 0,
  "totalMethods": 0,
  "controllers": [],
  "services": [],
  "repositories": []
}
```

The analysis continues for remaining repositories.

## Usage Example

### Workflow
1. User creates/updates project with multiple GitHub URLs
2. User clicks "Analyze Project" button
3. Backend automatically:
   - Retrieves all repository URLs from project
   - Clones each repository
   - Analyzes with Spoon
   - Returns comprehensive results
4. Frontend displays results for all repositories

### Example
```javascript
// Frontend code
async function analyzeProject(projectId, token) {
  const response = await fetch(
    `/api/projects/${projectId}/analyze-code`,
    {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`
      }
    }
  );

  const results = await response.json();
  
  // results is an array
  console.log(`Analyzed ${results.length} repositories`);
  
  results.forEach(result => {
    console.log(`\n${result.repoUrl}:`);
    console.log(`  Status: ${result.status}`);
    console.log(`  Controllers: ${result.controllers.length}`);
    console.log(`  Services: ${result.services.length}`);
    console.log(`  Repositories: ${result.repositories.length}`);
  });
}
```

## Backward Compatibility

⚠️ **Breaking Change**: This is a breaking change for any existing clients.

**Migration Required:**
- Remove `repositoryUrl` query parameter from API calls
- Update response handling to expect an array instead of single object
- Update UI to handle multiple results

## Build Status

✅ **BUILD SUCCESS**  
✅ All tests pass  
✅ Ready for deployment

## Updated Documentation

- `CODE_ANALYSIS_IMPLEMENTATION.md` - Updated with new endpoint details
- `ANALYSIS_SUMMARY.md` - Updated workflow and examples

---

**Change Type**: Enhancement  
**Breaking Change**: Yes  
**Status**: Complete  
**Build**: Success
