# Infrastructure

See `infrastructure/CLAUDE.md` for the current implementation.

## Design constraints
- ARM64 (Graviton2) throughout — ECS task definition, Docker builds (`--platform linux/arm64`), and base images
- Private-with-egress VPC: ECS tasks run in private subnets with outbound internet access via a NAT gateway (no public IPs, no inbound from internet); VPC endpoints keep AWS service traffic off NAT
- Least-privilege IAM: use `grantConsumeMessages` pattern rather than inline policies
