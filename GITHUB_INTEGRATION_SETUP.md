# GitHub OAuth Integration Setup Guide

## Step 1: Create GitHub OAuth App

1. Go to **GitHub Settings** → **Developer settings** → **OAuth Apps**
   - Direct URL: https://github.com/settings/developers

2. Click **"New OAuth App"**

3. Fill in the application details:
   ```
   Application name: Orkestify
   Homepage URL: http://localhost:8080
   Authorization callback URL: http://localhost:8080/login/oauth2/code/github
   ```

4. Click **"Register application"**

5. You'll see your **Client ID** on the next page

6. Click **"Generate a new client secret"** to get your **Client Secret**

7. **Save both values** - you'll need them for configuration

## Step 2: Update Environment Variables

Add these to your `.env` file:

```env
GITHUB_CLIENT_ID=your_actual_client_id_here
GITHUB_CLIENT_SECRET=your_actual_client_secret_here
```

## Step 3: Export Environment Variables

```bash
export $(cat .env | xargs)
```

## Step 4: Run the Application

```bash
./mvnw spring-boot:run
```

## Step 5: Test the Integration

### A. Initiate GitHub Login

Open browser and go to:
```
http://localhost:8080/oauth2/authorization/github
```

You'll be redirected to GitHub to authorize the app.

### B. After Authorization

You'll be redirected back to your app with the user info.

### C. API Endpoints

Replace `{userId}` with your GitHub username (login) from the callback.

#### 1. Check Token Status
```bash
GET http://localhost:8080/api/github/user/{userId}/token/status
```

#### 2. Get User Info
```bash
GET http://localhost:8080/api/github/user/{userId}
```

#### 3. Get All Organizations
```bash
GET http://localhost:8080/api/github/user/{userId}/organizations
```

Example response:
```json
[
  {
    "id": 123456,
    "login": "my-org",
    "avatarUrl": "https://avatars.githubusercontent.com/u/123456",
    "description": "My Organization",
    "url": "https://api.github.com/orgs/my-org",
    "reposUrl": "https://api.github.com/orgs/my-org/repos"
  }
]
```

#### 4. Get Organization Repositories
```bash
GET http://localhost:8080/api/github/user/{userId}/organization/{orgName}/repositories
```

Example:
```bash
GET http://localhost:8080/api/github/user/vignesh/organization/my-org/repositories
```

Response:
```json
[
  {
    "id": 789012,
    "name": "repo-name",
    "fullName": "my-org/repo-name",
    "description": "Repository description",
    "htmlUrl": "https://github.com/my-org/repo-name",
    "cloneUrl": "https://github.com/my-org/repo-name.git",
    "sshUrl": "git@github.com:my-org/repo-name.git",
    "defaultBranch": "main",
    "language": "Java",
    "private": false,
    "fork": false,
    "stargazersCount": 10,
    "forksCount": 2
  }
]
```

#### 5. Get User's Own Repositories
```bash
GET http://localhost:8080/api/github/user/{userId}/repositories
```

#### 6. Delete Token (Logout)
```bash
DELETE http://localhost:8080/api/github/user/{userId}/token
```

## Complete Flow Example

```bash
# 1. Login via browser
Open: http://localhost:8080/oauth2/authorization/github

# 2. After login, note your userId (GitHub username) from callback

# 3. Get your organizations
curl http://localhost:8080/api/github/user/YOUR_GITHUB_USERNAME/organizations

# 4. Get repositories for an organization
curl http://localhost:8080/api/github/user/YOUR_GITHUB_USERNAME/organization/ORG_NAME/repositories

# 5. Get your personal repositories
curl http://localhost:8080/api/github/user/YOUR_GITHUB_USERNAME/repositories
```

## Important Notes

### OAuth Scopes
The app requests these GitHub permissions:
- `read:user` - Read user profile data
- `read:org` - Read organization membership
- `repo` - Access repositories (both public and private)

### Security
- Tokens are stored in MongoDB
- Each user can have only one active token
- Tokens can be deleted via API
- All endpoints require the userId for security

### Callback URL
When deploying to production, update:
1. GitHub OAuth App callback URL to your production domain
2. `SecurityConfig.java` success URL if needed

### Rate Limits
GitHub API has rate limits:
- Authenticated: 5,000 requests per hour
- The app uses the user's OAuth token for all requests

## Troubleshooting

### Error: "redirect_uri_mismatch"
- Verify callback URL in GitHub OAuth App matches: `http://localhost:8080/login/oauth2/code/github`

### Error: "GitHub token not found"
- User needs to login first via `/oauth2/authorization/github`

### Error: "401 Unauthorized" from GitHub API
- Token might be expired or revoked
- User needs to re-authenticate

## Production Deployment

When deploying to production:

1. Update GitHub OAuth App:
   - Homepage URL: `https://yourdomain.com`
   - Callback URL: `https://yourdomain.com/login/oauth2/code/github`

2. Update environment variables with production URLs

3. Ensure HTTPS is enabled for OAuth security
