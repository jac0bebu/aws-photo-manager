# ☁️ AWS Photo Manager

A serverless photo management web application built with **AWS Lambda (Java)**, **S3**, **RDS MySQL**, and a plain HTML/JS frontend.

---

## 🏗️ Architecture Overview

```
User (Browser)
    │
    ▼
index.html (Frontend)
    │
    ├── POST /token     → LambdaGenerateToken       (SSM Parameter Store)
    ├── POST /list      → LambdaGetPhotosDB          (RDS MySQL)
    ├── POST /upload    → LambdaUploadObject         (S3 + RDS via Lambda invoke)
    │                       └── invokes → LambdaInsertRecordToRDS
    ├── POST /delete    → LambdaDeleteObject         (S3 + RDS via Lambda invoke)
    │                       └── invokes → LambdaDeleteRecordFromRDS
    └── POST /download  → LambdaCreateDownloadLink   (S3 Presigned URL)

S3 Events (automatic triggers):
    ├── ObjectCreated   → LambdaResizer              (resized-{bucket})
    └── ObjectRemoved   → LambdaDeleteResized        (resized-{bucket})
```

---

## 📦 Project Structure

```
photo-manager/
├── src/
│   └──main/
│      └── java/com/example/
│          ├── LambdaGenerateToken.java       # Auth: generate HMAC token from email
│          ├── LambdaTokenChecker.java        # Auth: shared token validation utility
│          ├── LambdaUploadObject.java        # Upload image to S3 + insert DB record
│          ├── LambdaInsertRecordToRDS.java   # Insert photo metadata into MySQL
│          ├── LambdaGetPhotosDB.java         # List all photos from MySQL
│          ├── LambdaDeleteObject.java        # Delete image from S3 + DB record
│          ├── LambdaDeleteRecordFromRDS.java # Delete photo record from MySQL (ownership check)
│          ├── LambdaCreateDownloadLink.java  # Generate S3 presigned download URL
│          ├── LambdaResizer.java             # Resize image on S3 upload event
│          └── LambdaDeleteResized.java       # Delete resized image on S3 delete event
│   
├── web/
│   └── index.html                             # Frontend app
├── pom.xml
└── README.md
```

---

## ⚙️ AWS Services Used

| Service | Purpose |
|---|---|
| **AWS Lambda** | Serverless function execution (Java 11+) |
| **Amazon S3** | Store original and resized images |
| **Amazon RDS (MySQL)** | Store photo metadata (key, description, owner) |
| **AWS SSM Parameter Store** | Securely store the HMAC secret key |
| **Lambda Function URLs** | HTTP endpoints for each Lambda (no API Gateway needed) |

---

## 🗄️ Database Schema

```sql
CREATE DATABASE CloudDatabase;

USE CloudDatabase;

CREATE TABLE Photos (
    ID          INT AUTO_INCREMENT PRIMARY KEY,
    Description VARCHAR(255),
    S3Key       VARCHAR(500) NOT NULL,
    user_email  VARCHAR(255) NOT NULL,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Required: Grant IAM auth access to the DB user
GRANT SELECT, INSERT, DELETE ON CloudDatabase.* TO 'admin'@'%';
GRANT USAGE ON *.* TO 'admin'@'%' REQUIRE SSL;
FLUSH PRIVILEGES;
```

---

## 🔐 Authentication Flow

This app uses **stateless HMAC tokens** — no sessions, no database lookups:

```
1. User submits email
2. LambdaGenerateToken fetches secret from SSM Parameter Store
3. Token = HMAC-SHA256(email, secret)
4. Token is sent back to the browser
5. Every subsequent request includes token + email
6. LambdaTokenChecker re-derives the expected token and compares
```

> ⚠️ Tokens never expire in this implementation. See [Improvements](#-potential-improvements) below.

---

## 🚀 Setup & Deployment

### Prerequisites

- Java 17
- Maven 3.x
- An AWS account with permissions to create Lambda, S3, RDS, SSM

### 1. Store your HMAC secret in SSM

```bash
aws ssm put-parameter \
  --name "key" \
  --value "your-super-secret-key" \
  --type "SecureString" \
  --region ap-southeast-1
```

### 2. Configure S3 Buckets

```bash
# Source bucket
aws s3 mb s3://cob-kun-public --region ap-southeast-1

# Resized bucket (naming convention: resized-{source})
aws s3 mb s3://resized-cob-kun-public --region ap-southeast-1
```

### 3. Build the project

```bash
mvn clean package -DskipTests
```

The deployable JAR will be at `target/your-project-1.0-SNAPSHOT.jar`.

### 4. Deploy Lambda Functions

For each Lambda, deploy via AWS Console or CLI:

```bash
aws lambda create-function \
  --function-name LambdaUploadObject \
  --runtime java11 \
  --handler com.example.LambdaUploadObject::handleRequest \
  --zip-file fileb://target/your-project.jar \
  --role arn:aws:iam::ACCOUNT_ID:role/your-lambda-role \
  --environment Variables="{S3_BUCKET_NAME=cob-kun-public,INSERT_LAMBDA_NAME=LambdaInsertRecordToRDS}" \
  --region ap-southeast-1
```

### 5. Environment Variables per Lambda

| Lambda | Environment Variables |
|---|---|
| `LambdaUploadObject` | `S3_BUCKET`, `INSERT_LAMBDA_NAME` |
| `LambdaDeleteObject` | `S3_BUCKET`, `DELETE_LAMBDA_NAME` |
| `LambdaResizer` | `S3_SOURCE` |
| `LambdaInsertRecordToRDS` | `RDS_HOST`, `RDS_PORT`, `DB_USER` |
| `LambdaDeleteRecordFromRDS` | `RDS_HOST`, `RDS_PORT`, `DB_USER` |
| `LambdaGetPhotosDB` | `RDS_HOST`, `RDS_PORT`, `DB_USER` |
| `LambdaCreateDownloadLink` | `S3_BUCKET` |

### 6. Configure S3 Event Triggers

In AWS Console → S3 → `cob-kun-public` → Properties → Event Notifications:

| Event | Lambda |
|---|---|
| `s3:ObjectCreated:*` | `LambdaResizer` |
| `s3:ObjectRemoved:*` | `LambdaDeleteResized` |

### 7. Add Lambda Extensions for SSM

Attach the **AWS Parameters and Secrets Lambda Extension** layer to:
- `LambdaGenerateToken`
- `LambdaTokenChecker` (all Lambdas that validate tokens)

---

## 🌐 Frontend Configuration

Open `web/index.html` and update the API URLs with your Lambda Function URLs:

```javascript
const API_URLS = {
    TOKEN:    "https://<your-token-lambda-url>.lambda-url.ap-southeast-1.on.aws/",
    LIST:     "https://<your-list-lambda-url>.lambda-url.ap-southeast-1.on.aws/",
    UPLOAD:   "https://<your-upload-lambda-url>.lambda-url.ap-southeast-1.on.aws/",
    DELETE:   "https://<your-delete-lambda-url>.lambda-url.ap-southeast-1.on.aws/",
    DOWNLOAD: "https://<your-download-lambda-url>.lambda-url.ap-southeast-1.on.aws/"
};
```

> ⚠️ Never commit real Lambda URLs to a public GitHub repository.

---

## 🔧 IAM Role Requirements

Your Lambda execution role must have:

```json
{
  "Effect": "Allow",
  "Action": [
    "s3:GetObject", "s3:PutObject", "s3:DeleteObject",
    "rds-db:connect",
    "lambda:InvokeFunction",
    "ssm:GetParameter",
    "logs:CreateLogGroup", "logs:CreateLogStream", "logs:PutLogEvents"
  ],
  "Resource": "*"
}
```

---

## 💡 Potential Improvements

### Security
- [ ] **Token expiry** — add a timestamp to the HMAC payload: `HMAC(email + ":" + timestamp, secret)`
- [ ] **`LambdaCreateDownloadLink`** has no token validation — anyone with a file key can get a download link
- [ ] **`LambdaGetPhotosDB`** returns all photos for all users — consider filtering by `user_email`
- [ ] Move Lambda Function URLs out of `index.html` into a config file excluded from git

### Code Quality
- [ ] **`convertMapStringToJson()`** in `LambdaInsertRecordToRDS` and `LambdaDeleteRecordFromRDS` is fragile — file names or descriptions containing commas will break parsing. Switch to proper JSON payloads using `SdkBytes.fromUtf8String(new JSONObject(...).toString())`
- [ ] **SSM secret caching** in `LambdaTokenChecker` — fetches SSM on every invocation. Cache in a `static` field for warm Lambda reuse
- [ ] Thumbnail size `MAX_DIMENSION = 100` in `LambdaResizer` is very small — consider 300–500px

### Architecture
- [ ] Add **API Gateway** in front of Lambda URLs for rate limiting, CORS, and WAF support
- [ ] Add **CloudFront** in front of S3 for the resized image thumbnails
- [ ] Consider **Lambda Layers** to share `LambdaTokenChecker` and DB utilities across functions

---

## 🛡️ .gitignore Recommendations

Make sure your `.gitignore` includes:

```gitignore
target/
*.class
*.jar
*.war

# IDE
.idea/
*.iml
.eclipse/

# AWS — NEVER commit
.aws/
*.pem
*.key

# Sensitive config
application.properties
application.yml
```

---

## 📜 License

MIT