# Local SpiceDB

This local instance is for schema modeling and SDK codegen.

```bash
docker compose -f infra/spicedb/docker-compose.yml up -d
zed --endpoint localhost:50051 \
  --token localdevlocaldevlocaldevlocaldev \
  --insecure \
  schema write infra/spicedb/doc.zed
```

Core model:

- `doc_node` covers space, folder, and document nodes.
- `parent` means permission inheritance. Do not write it when `doc_nodes.inheritance=false`.
- `owner/manager/editor/reader` are direct collaborator roles.
- `enterprise_*`, `public_*`, and `default_*` are non-collaborator grants from visibility/default enterprise member role.
- `owner_effective/manager_effective/editor_effective/reader_effective` mirror the CSES OpenFGA effective roles.
- `direct_assignment` is subtracted from inherited permissions so the nearest collaborator role wins, matching `DocCollaboratorRepositoryImpl.findCollaborators`.
- Parent owner becomes child manager because child `manager_effective` inherits `parent->manager_effective`, while `owner_effective` never inherits.

Quick validation:

```bash
zed --endpoint localhost:50051 --token localdevlocaldevlocaldevlocaldev --insecure \
  relationship create doc_node:space1 owner user:alice
zed --endpoint localhost:50051 --token localdevlocaldevlocaldevlocaldev --insecure \
  relationship create doc_node:folder1 parent doc_node:space1

# false: owner is direct only
zed --endpoint localhost:50051 --token localdevlocaldevlocaldevlocaldev --insecure \
  permission check doc_node:folder1 owner_effective user:alice

# true: parent owner becomes inherited manager
zed --endpoint localhost:50051 --token localdevlocaldevlocaldevlocaldev --insecure \
  permission check doc_node:folder1 manager_effective user:alice
```
