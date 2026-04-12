# Development Guide

## Prerequisites

### JDK (Amazon Corretto 25)

The service requires JDK 25. Gradle will auto-provision it via the Foojay toolchain resolver on first build, but you can also install it manually:

- **macOS**: `brew install --cask corretto@25`
- **Linux**: follow the [Amazon Corretto 25 install guide](https://docs.aws.amazon.com/corretto/latest/corretto-25-ug/what-is-corretto-25.html)
- **Windows**: download the MSI from the Corretto releases page

Verify: `java -version` should report `25`.

### Node.js 24+

Required for the CDK infrastructure package.

- **macOS**: `brew install node`
- **Linux / Windows**: download from [nodejs.org](https://nodejs.org)

Verify: `node --version` should report `v24.x.x` or later.

### AWS CDK CLI

```bash
npm install -g aws-cdk
```

Verify: `cdk --version`

### AWS CLI v2

Follow the [AWS CLI v2 install guide](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html).

Verify: `aws --version` should report `aws-cli/2.x.x`.

### Docker

Required to build and push the service container image. Install [Docker Desktop](https://www.docker.com/products/docker-desktop/) or the Docker Engine for your platform.

Verify: `docker --version`

---

## Environment Configuration

Configure your AWS credentials and default region:

```bash
aws configure
```

Or export them directly:

```bash
export AWS_ACCESS_KEY_ID=...
export AWS_SECRET_ACCESS_KEY=...
export AWS_DEFAULT_REGION=us-east-1
```

---

## Service Development (`service/`)

### Build

```bash
cd service
./gradlew build
```

### Run tests

```bash
./gradlew test

# Single test class
./gradlew test --tests "com.template.queue.QueuePollerTest"
```

### Run locally

Requires a real or [ElasticMQ](https://github.com/softwaremill/elasticmq) SQS queue URL.

```bash
export QUEUE_URL=https://sqs.us-east-1.amazonaws.com/123456789012/MyQueue
export AWS_REGION=us-east-1
./gradlew run
```

### Build Docker image

```bash
# Local testing (native architecture)
docker build -t queue-processing-service:latest .

# Production (ARM64/Graviton2, matches ECS Fargate task definition)
docker buildx build --platform linux/arm64 -t queue-processing-service:latest .
```

---

## Infrastructure Development (`infrastructure/`)

### Install dependencies

```bash
cd infrastructure
npm install
```

### Bootstrap (once per AWS account/region)

CDK requires a bootstrap stack the first time you deploy to an account/region:

```bash
cdk bootstrap aws://<account-id>/<region>
```

### Synthesize and diff

```bash
cdk synth   # generate CloudFormation templates into cdk.out/
cdk diff    # compare against the currently deployed stack
```

### Test

Snapshot tests use Jest and verify that stack changes produce the expected CloudFormation template. The committed snapshots in `test/__snapshots__/` are the source of truth.

```bash
npm test
```

If you make an intentional change to the stack and the snapshot is legitimately out of date, regenerate it:

```bash
npm run test:update
```

Review the diff in `test/__snapshots__/queue-processing-stack.test.ts.snap` before committing to confirm only expected resources changed.

### Deploy

```bash
cdk deploy
```

After a successful deploy, the stack outputs the resources you need for the next step:

| Output | Description |
|---|---|
| `QueueUrl` | SQS input queue URL — set as `QUEUE_URL` when running the service locally |
| `DeadLetterQueueUrl` | DLQ URL for monitoring failed messages |
| `RepositoryUri` | ECR repository URI — push your Docker image here |

---

## Full Deployment Workflow

1. **Build and push the Docker image**

   ```bash
   # Authenticate Docker to ECR
   aws ecr get-login-password --region <region> | \
     docker login --username AWS --password-stdin <account-id>.dkr.ecr.<region>.amazonaws.com

   cd service
   docker buildx build --platform linux/arm64 \
     -t <RepositoryUri>:latest \
     --push .
   ```

2. **Deploy the CDK stack**

   ```bash
   cd infrastructure
   cdk deploy
   ```

   ECS will pull the image and start the Fargate task automatically.

3. **Verify**

   ```bash
   # Send a test message
   aws sqs send-message \
     --queue-url <QueueUrl> \
     --message-body '{"hello": "world"}'

   # Tail container logs (replace with your log group name from cdk synth output)
   aws logs tail /aws/ecs/queue-processing-service --follow
   ```

---

## Troubleshooting

**Fargate task stops immediately after deploy**
The container image may not exist in ECR yet, or the wrong architecture was pushed. Confirm with:
```bash
aws ecr describe-images --repository-name queue-processing-service
```

**`cdk deploy` fails with "not bootstrapped"**
Run `cdk bootstrap aws://<account-id>/<region>` once for the target account and region.

**Service throws `QUEUE_URL environment variable is required`**
The `QUEUE_URL` environment variable is injected by the CDK stack at deploy time. When running locally, export it manually before calling `./gradlew run`.

**Gradle build fails with JDK not found**
Ensure `JAVA_HOME` points to a JDK 25 installation, or let Gradle auto-provision it by running `./gradlew build` once with an internet connection (the Foojay resolver will download Corretto 25).
