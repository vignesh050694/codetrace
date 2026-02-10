# Canonical ID Implementation for Stable Graph Identity

## Overview

This document describes the implementation of canonical IDs for the CodeTrace graph system. Canonical IDs enable reliable architectural comparison across branches, commits, and pull requests by providing stable, deterministic identifiers that are independent of UUIDs, line numbers, and file locations.

## Problem Statement

Without canonical IDs, comparing graphs across different branches or commits is unreliable because:
- UUIDs change on every analysis
- Line numbers change with code formatting
- File locations may shift
- Dependency rewiring cannot be reliably detected

## Solution: Deterministic Canonical IDs

Every node and edge in the graph now has two IDs:
1. **UUID (`id`)**: Internal identifier for database operations
2. **Canonical ID (`canonicalId`)**: Stable, deterministic identifier for cross-branch comparison

## Canonical ID Formats

### Node Types

| Node Type | Canonical ID Format | Example |
|-----------|---------------------|---------|
| **Controller** | `controller:{package}.{className}` | `controller:com.example.api.UserController` |
| **Endpoint** | `endpoint:{HTTP_METHOD}:{normalizedPath}` | `endpoint:GET:/api/users/{*}` |
| **Method** | `method:{fullyQualifiedClass}.{methodName}({paramTypes})` | `method:com.example.service.UserService.findUser(String,int)` |
| **ExternalCall** | `external:{httpMethod}:{normalizedPath}:resolved={true\|false}` | `external:POST:/api/orders/{*}:resolved=true` |
| **Service** | `service:{fullyQualifiedClass}` | `service:com.example.service.UserService` |
| **Repository** | `repository:{fullyQualifiedClass}` | `repository:com.example.repo.UserRepository` |
| **Application** | `application:{appKey}` | `application:com.example.MainApplication` |
| **KafkaTopic** | `kafka_topic:{topicName}` | `kafka_topic:user-events` |
| **DatabaseTable** | `database_table:{tableName}` | `database_table:users` |

### Edge Format

Edges use the canonical IDs of their source and target nodes:

```
{edgeType}:{sourceCanonicalId}->{targetCanonicalId}
```

**Examples:**
- `calls:controller:com.example.UserController->service:com.example.UserService`
- `has_endpoint:controller:com.example.UserController->endpoint:GET:/api/users/{*}`
- `makes_external_call:endpoint:POST:/api/orders->external:GET:/api/payment/{*}:resolved=true`

## Path Normalization Rules

Paths in endpoints and external calls are normalized to ensure stability:

### Normalization Strategy

1. **Path variables** like `{id}`, `{userId}` → `{*}`
2. **Numeric IDs** like `/users/123` → `/users/{*}`
3. **UUID patterns** like `/orders/550e8400-e29b-41d4-a916-446655440000` → `/orders/{*}`
4. **Dynamic placeholders** like `<dynamic>` → `{*}`
5. **Trailing slashes** are removed
6. **Query parameters** are stripped

### Examples

| Original Path | Normalized Path |
|---------------|-----------------|
| `/api/users/{id}` | `/api/users/{*}` |
| `/api/users/123` | `/api/users/{*}` |
| `/api/orders/{orderId}/items/{itemId}` | `/api/orders/{*}/items/{*}` |
| `http://localhost:8080/api/users/123` | `/api/users/{*}` |
| `https://api.example.com/v1/orders?status=active` | `/v1/orders` |
| `GET <dynamic>/api/users/roll/<dynamic>` | `GET /api/users/roll/{*}` |

## Method Signature Normalization

Method canonical IDs include parameter types but not parameter names:

### Strategy

1. Extract parameter types from signature
2. Ignore parameter names
3. Preserve generic types
4. Handle method overloading

### Examples

| Method Signature | Canonical ID |
|------------------|--------------|
| `findUser(String name, int age)` | `method:UserService.findUser(String,int)` |
| `save(User user)` | `method:UserService.save(User)` |
| `getOrders(List<String> ids)` | `method:OrderService.getOrders(List<String>)` |

## Stability Guarantees

### Canonical IDs REMAIN UNCHANGED When:

✅ Code formatting changes  
✅ Line numbers change  
✅ Files are moved (but symbols remain the same)  
✅ Whitespace changes  
✅ Comments are added/removed  

### Canonical IDs CHANGE When:

❌ Endpoint path or HTTP method changes  
❌ Method signature changes (name or param types)  
❌ Controller/Service class changes package or name  
❌ Database table name changes  
❌ Kafka topic name changes  

## Implementation Details

### Core Components

#### 1. **CanonicalIdGenerator Service**

Location: `com.architecture.memory.orkestify.service.graph.CanonicalIdGenerator`

Centralized service for generating all canonical IDs with:
- Deterministic ID generation methods for each node type
- Path normalization utilities
- Method signature parsing
- Edge canonical ID generation

#### 2. **Node Models Updated**

All node models now include a `canonicalId` field:

```java
@Property("canonicalId")
private String canonicalId;
```

**Updated Models:**
- `ControllerNode`
- `EndpointNode`
- `MethodNode`
- `ExternalCallNode`
- `ServiceNode`
- `RepositoryClassNode`
- `ApplicationNode`
- `KafkaTopicNode`
- `DatabaseTableNode`

#### 3. **DTO Models Updated**

Visualization DTOs include canonical IDs:

```java
public class GraphNode {
    private String id;              // UUID for internal use
    private String canonicalId;     // Stable ID for comparison
    // ...
}

public class GraphEdge {
    private String id;              // UUID for internal use
    private String canonicalId;     // Stable ID for comparison
    // ...
}
```

#### 4. **Graph Builder Integration**

`Neo4jGraphBuilder` generates canonical IDs during node creation:

```java
ControllerNode controllerNode = ControllerNode.builder()
    .className(controller.getClassName())
    .packageName(controller.getPackageName())
    .canonicalId(canonicalIdGenerator.generateControllerCanonicalId(
        controller.getPackageName(), controller.getClassName()))
    // ...
    .build();
```

#### 5. **Visualization Service Integration**

`GraphVisualizationService` propagates canonical IDs to DTOs:

```java
return GraphNode.builder()
    .id(controller.getId())
    .canonicalId(controller.getCanonicalId())
    .label(controller.getClassName())
    // ...
    .build();
```

## Usage in Shadow Graph Analysis

Canonical IDs enable reliable PR diff analysis:

### Before (Unreliable):
```
Base Branch: Node UUID=abc123
PR Branch:   Node UUID=xyz789
❌ Cannot match - different UUIDs
```

### After (Reliable):
```
Base Branch: canonicalId=endpoint:GET:/api/users/{*}
PR Branch:   canonicalId=endpoint:GET:/api/users/{*}
✅ Same endpoint - can compare
```

### Detecting Changes:

```java
// Example: Detect endpoint rewiring
String baseCanonicalId = "endpoint:POST:/api/orders";
String prCanonicalId = "endpoint:POST:/api/orders";

if (baseCanonicalId.equals(prCanonicalId)) {
    // Same endpoint - check if relationships changed
    Set<String> baseCallEdges = getOutgoingEdges(baseGraph, baseCanonicalId);
    Set<String> prCallEdges = getOutgoingEdges(prGraph, prCanonicalId);
    
    if (!baseCallEdges.equals(prCallEdges)) {
        reportDependencyRewiring(baseCanonicalId);
    }
}
```

## Edge Cases Handled

### 1. Dynamic Endpoints
```java
// Dynamic segments normalized
"GET <dynamic>/api/users/roll/<dynamic>"
→ canonicalId = "endpoint:GET:/api/users/roll/{*}"
```

### 2. External Call Resolution
```java
// Resolution state included
ExternalCall(GET, "/api/payment", resolved=true)
→ canonicalId = "external:GET:/api/payment:resolved=true"
```

### 3. Method Overloading
```java
// Parameter types preserved
method.findUser(String name)
→ "method:UserService.findUser(String)"

method.findUser(int id)
→ "method:UserService.findUser(int)"
```

### 4. Unknown/Null Values
```java
// Fallback to "unknown"
generateControllerCanonicalId(null, "UserController")
→ "controller:unknown"
```

## Benefits for Shadow Graph Comparison

1. **Reliable Node Matching**: Same architectural element always has same canonical ID
2. **Dependency Rewiring Detection**: Can detect when calls change targets
3. **Impact Analysis**: Precisely identify what changed between branches
4. **File Change Detection**: Match nodes even when files move
5. **Reduced Noise**: Formatting changes don't affect comparison

## Future Enhancements

### Planned:
- [ ] Canonical ID indexing for faster lookups
- [ ] Canonical ID validation on node creation
- [ ] Migration script to generate canonical IDs for existing nodes
- [ ] Canonical ID versioning for breaking changes
- [ ] Shadow graph diff service using canonical IDs

### Considerations:
- Generic type erasure strategy
- Reflection-based call handling (`unknown:*` pattern)
- Composite canonical IDs for complex relationships

## Migration Path

For existing graphs without canonical IDs:

```cypher
// Find nodes missing canonical IDs
MATCH (n) WHERE n.canonicalId IS NULL RETURN count(n);

// Re-analyze project to generate canonical IDs
POST /api/projects/{projectId}/analyze
```

## Testing Strategy

### Unit Tests Required:
- [ ] Path normalization edge cases
- [ ] Method signature parsing
- [ ] Canonical ID generation for each node type
- [ ] Edge canonical ID generation
- [ ] Stability across code changes

### Integration Tests Required:
- [ ] Full graph analysis with canonical IDs
- [ ] Shadow graph comparison using canonical IDs
- [ ] PR diff detection

## References

- [GRAPH_ENRICHMENT.md](./explanations/GRAPH_ENRICHMENT.md)
- [COMPLETE_SOLUTION.md](./explanations/COMPLETE_SOLUTION.md)
- `CanonicalIdGenerator.java`
- `Neo4jGraphBuilder.java`
- `GraphVisualizationService.java`

---

**Status**: ✅ Implemented  
**Version**: 1.0  
**Last Updated**: February 10, 2026

