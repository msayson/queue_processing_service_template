# Infrastructure

See `infrastructure/CLAUDE.md` for the current implementation.

## Design constraints
- ARM64 (Graviton2) throughout — ECS task definition, Docker builds (`--platform linux/arm64`), and base images
- Fully private VPC: isolated private subnets only, no internet gateway, no NAT gateway; all AWS API calls route through VPC endpoints
- Least-privilege IAM: use `grantConsumeMessages` pattern rather than inline policies
