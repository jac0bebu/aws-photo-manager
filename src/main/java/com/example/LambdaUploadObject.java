package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import org.json.JSONObject;

import java.util.Base64;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

public class LambdaUploadObject
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String BUCKET_NAME = System.getenv("S3_BUCKET") != null ? 
        System.getenv("S3_BUCKET") : "cob-kun-public";
    private static final String INSERT_LAMBDA = System.getenv("INSERT_LAMBDA_NAME") != null ? 
        System.getenv("INSERT_LAMBDA_NAME") : "LambdaInsertRecordToRDS";

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {

        LambdaLogger logger = context.getLogger();

        try {
            // ===================== VALIDATE REQUEST =====================
            String body = request.getBody();
            if (body == null || body.isEmpty()) {
                return error(400, "Request body is missing");
            }

            logger.log("Incoming body length: " + body.length());
            JSONObject json = new JSONObject(body);

            String key = json.optString("key", null);
            String description = json.optString("description", "");
            String email = json.optString("email", null);
            String token = json.optString("token", null);
            String fileContent = json.optString("fileContent", null);

            if (key == null || fileContent == null || email == null || token == null) {
                return error(400, "Missing key, email, token, or fileContent");
            }
            if (!LambdaTokenChecker.isValid(email, token)) {
                logger.log("SECURITY ALERT: Invalid token for email: " + email);
                return error(401, "Unauthorized: Invalid or expired token");
            }
            logger.log("Token validated successfully for: " + email);

            // Remove data URL prefix if present
            if (fileContent.startsWith("data:")) {
                fileContent = fileContent.substring(fileContent.indexOf(",") + 1);
            }

            // ===================== DECODE BASE64 =====================
            byte[] fileBytes = Base64.getDecoder().decode(fileContent);
            logger.log("Decoded file size: " + fileBytes.length);

            // ===================== UPLOAD ORIGINAL TO S3 =====================
            S3Client s3 = S3Client.builder()
                    .region(Region.AP_SOUTHEAST_1)
                    .build();

            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(BUCKET_NAME)
                            .key(key)
                            .contentType("image/jpeg")
                            .build(),
                    RequestBody.fromBytes(fileBytes)
            );

            logger.log("S3 upload completed: " + key);

            // ===================== INVOKE LAMBDA (INSERT DB) =====================
            LambdaClient lambdaClient = LambdaClient.builder()
                    .region(Region.AP_SOUTHEAST_1)
                    .build();

            JSONObject insertPayload = new JSONObject();
            insertPayload.put("key", key);
            insertPayload.put("description", description);
            insertPayload.put("email", email);

            InvokeResponse insertRes = lambdaClient.invoke(
                    InvokeRequest.builder()
                            .functionName(INSERT_LAMBDA)
                            .payload(SdkBytes.fromUtf8String(insertPayload.toString()))
                            .build()
            );

            String insertRaw = insertRes.payload().asUtf8String();
            logger.log("Lambda DB Insert response: " + insertRaw);

            JSONObject insertResult = safeJson(insertRaw);

            // ===================== FINAL RESPONSE =====================
            JSONObject response = new JSONObject();
            response.put("status", "success");
            response.put("uploadedKey", key);
            response.put("bucket", BUCKET_NAME);
            response.put("insertResult", insertResult);

            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody(response.toString());

        } catch (Exception e) {
            logger.log("ERROR: " + e.getMessage());
            return error(500, e.getMessage());
        }
    }

    // ===================== HELPERS =====================

    private JSONObject safeJson(String raw) {
        try {
            raw = raw.trim();
            if (raw.startsWith("\"") && raw.endsWith("\"")) {
                raw = raw.substring(1, raw.length() - 1);
            }
            // Unescape escaped quotes if necessary (simple handling)
            raw = raw.replace("\\\"", "\""); 
            return new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject().put("raw", raw);
        }
    }

    private APIGatewayProxyResponseEvent error(int code, String msg) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(code)
                .withBody(new JSONObject().put("error", msg).toString());
    }
}