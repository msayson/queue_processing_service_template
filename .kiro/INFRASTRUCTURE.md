# Infrastructure

See `infrastructure/CLAUDE.md` for the current implementation.

## Design constraints
- ARM64 (Graviton2) throughout — ECS task definition, Docker builds (`--platform linux/arm64`), and base images
- Private subnets only; Fargate tasks have no public ingress, egress via NAT gateway
- Least-privilege IAM: use `grantConsumeMessages` pattern rather than inline policies
