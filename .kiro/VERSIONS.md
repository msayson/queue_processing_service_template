# Versions

This is the single source of truth for all dependency and tool versions used in this project. All other steering documents reference this file rather than repeating version numbers.

## Runtime

| Component | Version |
|-----------|---------|
| JDK (Amazon Corretto) | 25 |
| Kotlin | 2.3.x |
| Gradle | 9+ |

## Kotlin Dependencies

| Dependency | Version |
|-----------|---------|
| AWS SDK for Kotlin BOM | 1.x.x |
| kotlinx-coroutines-core | 1.10.x |
| kotlinx-serialization-json | 1.11.x |
| kotlin-logging-jvm | 8.x.x |
| log4j-slf4j2-impl | 2.x.x |

## Infrastructure (CDK / TypeScript)

| Dependency | Version |
|-----------|---------|
| aws-cdk-lib | ^2.x.x |
| constructs | ^10.x.x |
| typescript | ^5.x.x |
| ts-node | ^10.x.x |
| @types/node | ^24.x.x |

## Local Development Tools

| Tool | Version |
|------|---------|
| Node.js | 24+ |
| AWS CLI | v2+ |
| Docker | latest |

## Docker Base Images

| Image | Version |
|-------|---------|
| Build stage | `amazoncorretto:25-alpine-jdk` |
| Runtime stage | `amazoncorretto:25-alpine-jre` |
