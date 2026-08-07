<!-- gitnexus:start -->
# GitNexus MCP

This project is indexed as **authcses-sdk** by GitNexus, providing AI agents with deep code intelligence.

## Project: authcses-sdk

| Metric | Count |
|--------|-------|
| Files | 174 |
| Symbols | 1330 |
| Relationships | 4324 |
| Communities | 392 |
| Processes | 75 |

## Quick Start

```
1. READ gitnexus://repos                          → Discover all indexed repos
2. READ gitnexus://repo/authcses-sdk/context     → Get codebase overview (~150 tokens)
3. READ gitnexus://repo/authcses-sdk/clusters    → See all functional clusters
4. gitnexus_search({query: "...", repo: "authcses-sdk"}) → Find code by query
```

## Available Resources

| Resource | Purpose |
|----------|---------|
| `gitnexus://repos` | List all indexed repositories |
| `gitnexus://repo/authcses-sdk/context` | Codebase stats, tools, and resources overview |
| `gitnexus://repo/authcses-sdk/clusters` | All clusters with symbol counts and cohesion |
| `gitnexus://repo/authcses-sdk/cluster/{name}` | Cluster members and details |
| `gitnexus://repo/authcses-sdk/processes` | All execution flows with types |
| `gitnexus://repo/authcses-sdk/process/{name}` | Full process trace with steps |
| `gitnexus://repo/authcses-sdk/schema` | Graph schema for Cypher queries |

## Available Tools

| Tool | Purpose | When to Use |
|------|---------|-------------|
| `list_repos` | Discover indexed repos | First step with multiple repos |
| `search` | Semantic + keyword search | Finding code by query |
| `overview` | List clusters & processes | Understanding architecture |
| `explore` | Deep dive on symbol/cluster/process | Detailed investigation |
| `impact` | Blast radius analysis | Before making changes |
| `cypher` | Raw graph queries | Complex analysis |

> **Multi-repo:** When multiple repos are indexed, pass `repo: "authcses-sdk"` to target this project.

## Workflow Examples

### Exploring the Codebase
```
READ gitnexus://repos                            → Discover repos
READ gitnexus://repo/authcses-sdk/context       → Stats and overview
READ gitnexus://repo/authcses-sdk/clusters      → Find relevant cluster
READ gitnexus://repo/authcses-sdk/cluster/Auth  → Explore Auth cluster
gitnexus_explore({name: "validateUser", type: "symbol", repo: "authcses-sdk"})
```

### Planning a Change
```
gitnexus_impact({target: "UserService", direction: "upstream", repo: "authcses-sdk"})
READ gitnexus://repo/authcses-sdk/processes     → Check affected flows
gitnexus_explore({name: "LoginFlow", type: "process", repo: "authcses-sdk"})
```

## Graph Schema

**Nodes:** File, Function, Class, Interface, Method, Community, Process

**Relationships:** CALLS, IMPORTS, EXTENDS, IMPLEMENTS, DEFINES, MEMBER_OF, STEP_IN_PROCESS

```cypher
// Example: Find callers of a function
MATCH (caller)-[:CodeRelation {type: 'CALLS'}]->(f:Function {name: "myFunc"})
RETURN caller.name, caller.filePath
```

<!-- gitnexus:end -->