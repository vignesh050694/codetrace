# Project Repository Analysis Feature

## Overview
The analyze API allows you to automatically clone GitHub repositories associated with a project and validate whether they are Java repositories.

## API Endpoint

### Analyze Project
```
POST /api/projects/{projectId}/analyze
Authorization: Bearer {token}
```

**Description**: Analyzes all GitHub repositories associated with a project by cloning them temporarily and checking for Java project indicators.

## Request

### Path Parameters
- `projectId` (required): The ID of the project to analyze

### Headers
- `Authorization: Bearer {token}` (required): JWT authentication token

### Example Request
```bash
curl -X POST http://localhost:8080/api/projects/65f1234567890abcdef12345/analyze \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
```

## Response

### Success Response (200 OK)

```json
{
  "projectId": "65f1234567890abcdef12345",
  "projectName": "My Project",
  "totalRepositories": 2,
  "successfullyCloned": 2,
  "javaRepositories": 1,
  "nonJavaRepositories": 1,
  "repositories": [
    {
      "repositoryUrl": "https://github.com/user/java-project.git",
      "cloned": true,
      "isJavaRepository": true,
      "errorMessage": null,
      "localPath": "/tmp/orkestify-analysis/java-project",
      "javaProjectInfo": {
        "hasPomXml": true,
        "hasGradleBuild": false,
        "hasJavaFiles": true,
        "javaFileCount": 45,
        "buildTool": "Maven"
      }
    },
    {
      "repositoryUrl": "https://github.com/user/python-project.git",
      "cloned": true,
      "isJavaRepository": false,
      "errorMessage": null,
      "localPath": "/tmp/orkestify-analysis/python-project",
      "javaProjectInfo": {
        "hasPomXml": false,
        "hasGradleBuild": false,
        "hasJavaFiles": false,
        "javaFileCount": 0,
        "buildTool": null
      }
    }
  ],
  "analyzedAt": "2026-01-31T20:30:00",
  "status": "COMPLETED"
}
```

### Response Fields

#### Top Level
- `projectId`: The project's unique identifier
- `projectName`: Name of the project
- `totalRepositories`: Total number of GitHub URLs in the project
- `successfullyCloned`: Number of repositories successfully cloned
- `javaRepositories`: Number of repositories identified as Java projects
- `nonJavaRepositories`: Number of repositories identified as non-Java projects
- `analyzedAt`: Timestamp when the analysis was performed
- `status`: Overall analysis status
  - `COMPLETED`: All repositories analyzed successfully
  - `PARTIAL`: Some repositories failed to clone/analyze
  - `FAILED`: All repositories failed to clone/analyze

#### Repository Analysis Result
- `repositoryUrl`: The GitHub repository URL
- `cloned`: Whether the repository was successfully cloned
- `isJavaRepository`: Whether the repository is identified as a Java project
- `errorMessage`: Error message if cloning/analysis failed (null if successful)
- `localPath`: Temporary path where repository was cloned (null if failed)
- `javaProjectInfo`: Detailed Java project information

#### Java Project Info
- `hasPomXml`: Whether a `pom.xml` file exists (Maven project)
- `hasGradleBuild`: Whether a `build.gradle` or `build.gradle.kts` exists (Gradle project)
- `hasJavaFiles`: Whether any `.java` files were found
- `javaFileCount`: Total number of `.java` files in the repository
- `buildTool`: Identified build tool (`Maven`, `Gradle`, or `null`)

### Error Responses

#### 404 Not Found
```json
{
  "timestamp": "2026-01-31T20:30:00",
  "status": 404,
  "error": "Not Found",
  "message": "Project not found with id: 65f1234567890abcdef12345",
  "path": "/api/projects/65f1234567890abcdef12345/analyze"
}
```

#### 400 Bad Request
```json
{
  "timestamp": "2026-01-31T20:30:00",
  "status": 400,
  "error": "Bad Request",
  "message": "Project has no GitHub repositories configured",
  "path": "/api/projects/65f1234567890abcdef12345/analyze"
}
```

#### 401 Unauthorized
```json
{
  "timestamp": "2026-01-31T20:30:00",
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid username or password",
  "path": "/api/projects/65f1234567890abcdef12345/analyze"
}
```

## How It Works

### Process Flow

1. **Authentication Check**: Verifies the user is authenticated and owns the project
2. **Validation**: Ensures the project has GitHub URLs configured
3. **Repository Cloning**: For each GitHub URL:
   - Clones the repository to a temporary directory (`/tmp/orkestify-analysis/`)
   - Uses `git clone --depth 1` for faster cloning (shallow clone)
4. **Java Detection**: Checks for:
   - `pom.xml` file (Maven project)
   - `build.gradle` or `build.gradle.kts` files (Gradle project)
   - `.java` files anywhere in the repository
   - Counts total Java files
5. **Cleanup**: Automatically deletes cloned repositories after analysis
6. **Response**: Returns comprehensive analysis results

### Java Repository Detection

A repository is identified as a Java repository if **any** of the following conditions are met:
- Contains a `pom.xml` file (Maven project)
- Contains a `build.gradle` or `build.gradle.kts` file (Gradle project)
- Contains one or more `.java` files

## Prerequisites

### System Requirements
- **Git**: Must be installed and accessible in the system PATH
- **Disk Space**: Temporary space for cloning repositories
- **Permissions**: Write permissions to `/tmp` directory

### Verify Git Installation
```bash
git --version
```

If Git is not installed:
- **macOS**: `brew install git` or download from [git-scm.com](https://git-scm.com/)
- **Linux**: `sudo apt-get install git` (Ubuntu/Debian) or `sudo yum install git` (CentOS/RHEL)
- **Windows**: Download from [git-scm.com](https://git-scm.com/)

## Usage Examples

### Complete Workflow

```bash
# 1. Login to get authentication token
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "myuser", "password": "mypassword"}' \
  | jq -r '.token')

# 2. Create a project
PROJECT_ID=$(curl -s -X POST http://localhost:8080/api/projects \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"name": "My Java Project"}' \
  | jq -r '.id')

# 3. Add GitHub URLs to the project
curl -X PUT http://localhost:8080/api/projects/$PROJECT_ID \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "My Java Project",
    "githubUrls": [
      "https://github.com/spring-projects/spring-boot.git",
      "https://github.com/apache/kafka.git"
    ]
  }'

# 4. Analyze the project
curl -X POST http://localhost:8080/api/projects/$PROJECT_ID/analyze \
  -H "Authorization: Bearer $TOKEN" | jq .
```

### Using the Test Script

```bash
# Run the automated test script
./test-analyze-api.sh
```

The test script will:
1. Authenticate a user
2. Create a project
3. Add GitHub URLs (Spring Boot and Linux kernel repos as examples)
4. Analyze the project
5. Display detailed results

## Performance Considerations

### Cloning Time
- Shallow clones (`--depth 1`) are used to minimize clone time
- Large repositories may still take significant time
- Analysis is synchronous - the API call waits for completion

### Recommended Approach
- Limit the number of repositories per project for faster analysis
- Consider analyzing during off-peak hours for large repositories
- For production, consider implementing async analysis with status polling

### Typical Analysis Times
- Small repository (<10 MB): 2-5 seconds
- Medium repository (10-100 MB): 5-30 seconds
- Large repository (>100 MB): 30-120 seconds

## Limitations

1. **Synchronous Processing**: The API waits for all repositories to be analyzed before responding
2. **Network Dependency**: Requires internet access to clone GitHub repositories
3. **Private Repositories**: Currently only supports public repositories
4. **Git Requirement**: Git must be installed on the server
5. **Disk Space**: Temporary disk space needed for cloning

## Security Considerations

1. **User Authorization**: Only the project owner can analyze their projects
2. **Temporary Files**: All cloned repositories are automatically cleaned up
3. **Public Repositories Only**: Private repository support requires GitHub token integration
4. **URL Validation**: GitHub URLs are used as-is (consider adding URL validation)

## Troubleshooting

### Error: "Failed to clone repository"
**Causes:**
- Git is not installed
- No internet connection
- Invalid GitHub URL
- Repository doesn't exist or is private

**Solutions:**
- Verify Git installation: `git --version`
- Check internet connectivity
- Verify the GitHub URL is correct and the repository is public
- Ensure the URL ends with `.git`

### Error: "Project has no GitHub repositories configured"
**Cause:** The project doesn't have any GitHub URLs

**Solution:** Update the project with GitHub URLs first:
```bash
curl -X PUT http://localhost:8080/api/projects/{projectId} \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"name": "Project Name", "githubUrls": ["https://github.com/user/repo.git"]}'
```

### Error: "Permission denied" when cloning
**Cause:** Insufficient permissions to write to temp directory

**Solution:** Ensure the application has write permissions to `/tmp/orkestify-analysis/`

### Analysis Takes Too Long
**Solutions:**
- Reduce the number of repositories per project
- Use smaller repositories for testing
- Check network bandwidth
- Consider implementing async analysis (future enhancement)

## Future Enhancements

Potential improvements:
1. **Async Analysis**: Return immediately with a job ID, poll for status
2. **Private Repository Support**: Use GitHub tokens for private repos
3. **Webhook Notifications**: Notify when analysis completes
4. **Cache Results**: Store and reuse analysis results
5. **Parallel Processing**: Clone and analyze multiple repos concurrently
6. **Advanced Java Detection**: Parse `pom.xml`/`build.gradle` for more details
7. **Support for Other Languages**: Detect Python, Node.js, Go, etc.
8. **Dependency Analysis**: Extract and analyze project dependencies

## Example Output

```
========== Analysis Summary ==========
Status: COMPLETED
Total Repositories: 2
Successfully Cloned: 2
Java Repositories: 1
Non-Java Repositories: 1
======================================

Repository: https://github.com/spring-projects/spring-boot.git
  - Cloned: true
  - Is Java: true
  - Has pom.xml: true
  - Has Gradle: false
  - Java Files: 3421
  - Build Tool: Maven
  - Error: None

Repository: https://github.com/torvalds/linux.git
  - Cloned: true
  - Is Java: false
  - Has pom.xml: false
  - Has Gradle: false
  - Java Files: 0
  - Build Tool: None
  - Error: None
```

## API Integration Example (JavaScript)

```javascript
async function analyzeProject(projectId, token) {
  const response = await fetch(
    `http://localhost:8080/api/projects/${projectId}/analyze`,
    {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`
      }
    }
  );

  if (!response.ok) {
    throw new Error(`Analysis failed: ${response.statusText}`);
  }

  const analysis = await response.json();
  
  console.log(`Status: ${analysis.status}`);
  console.log(`Java Repositories: ${analysis.javaRepositories}/${analysis.totalRepositories}`);
  
  analysis.repositories.forEach(repo => {
    console.log(`\n${repo.repositoryUrl}:`);
    console.log(`  - Is Java: ${repo.isJavaRepository}`);
    if (repo.javaProjectInfo) {
      console.log(`  - Build Tool: ${repo.javaProjectInfo.buildTool || 'None'}`);
      console.log(`  - Java Files: ${repo.javaProjectInfo.javaFileCount}`);
    }
  });
  
  return analysis;
}
```

## Notes

- Repositories are cloned to `/tmp/orkestify-analysis/{repo-name}/`
- All temporary files are automatically cleaned up after analysis
- The `.git` folder is excluded from Java file counting
- Analysis results are not persisted (call the API again to re-analyze)
