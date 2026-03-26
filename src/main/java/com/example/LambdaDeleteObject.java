package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import org.json.JSONObject;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

public class LambdaDeleteObject implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String BUCKET_NAME = System.getenv("S3_BUCKET") != null ? 
        System.getenv("S3_BUCKET") : "cob-kun-public";
    private static final String DELETE_DB_LAMBDA = System.getenv("DELETE_LAMBDA_NAME") != null ? 
        System.getenv("DELETE_LAMBDA_NAME") : "LambdaDeleteRecordFromRDS";

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        LambdaLogger logger = context.getLogger();

        try {
            // 1. PARSE JSON from body
            String body = request.getBody();
            if (body == null || body.isEmpty()) {
                return error(400, "Request body is missing");
            }

            JSONObject json = new JSONObject(body);
            String email = json.optString("email", null);
            String token = json.optString("token", null);
            String key = json.optString("key", null);

            logger.log("Received - Email: " + email + ", Key: " + key);

            // 2. GATEKEEPER: Validate token using LambdaTokenChecker
            if (email == null || token == null) {
                return error(400, "Missing email or token");
            }

            if (!LambdaTokenChecker.isValid(email, token)) {
                logger.log("Token validation failed for email: " + email);
                return error(401, "Unauthorized: Invalid token");
            }

            logger.log("Token validated successfully");

            if (key == null || key.isEmpty()) {
                return error(400, "Missing S3 key for deletion");
            }

            // 3. CALL LAMBDA WORKER RDS to delete record (ownership check happens here)
            logger.log("Invoking RDS Lambda to delete record...");
            LambdaClient lambdaClient = LambdaClient.builder()
                    .region(Region.AP_SOUTHEAST_1)
                    .build();

            JSONObject rdsPayload = new JSONObject();
            rdsPayload.put("key", key);
            rdsPayload.put("email", email);
            InvokeResponse dbRes = lambdaClient.invoke(
                    InvokeRequest.builder()
                            .functionName(DELETE_DB_LAMBDA)
                            .payload(SdkBytes.fromUtf8String(rdsPayload.toString()))
                            .build()
            );

            String dbRaw = dbRes.payload().asUtf8String();
            logger.log("DB Lambda response: " + dbRaw);

            JSONObject dbResult = decodeAndParse(dbRaw);
            
            if (!dbResult.optBoolean("success", false)) {
                String msg = dbResult.optString("message", "Database record not found or error");
                logger.log("RDS deletion failed: " + msg);
                return error(500, "Database deletion failed: " + msg);
            }

            // 4. DELETE from S3
            logger.log("Deleting object from S3...");
            S3Client s3 = S3Client.builder()
                    .region(Region.AP_SOUTHEAST_1)
                    .build();

            s3.deleteObject(
                    DeleteObjectRequest.builder()
                            .bucket(BUCKET_NAME)
                            .key(key)
                            .build()
            );

            logger.log("S3 deletion completed for key: " + key);

            // 5. Return success response
            JSONObject response = new JSONObject();
            response.put("status", "success");
            response.put("deletedKey", key);
            response.put("message", "Object deleted from both RDS and S3");

            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody(response.toString());

        } catch (Exception e) {
            logger.log("ERROR: " + e.getMessage());
            e.printStackTrace();
            return error(500, e.getMessage());
        }
    }

    private JSONObject decodeAndParse(String raw) {
        try {
            if (raw == null || raw.isEmpty()) {
                return new JSONObject().put("success", false).put("message", "Empty response");
            }

            raw = raw.trim();

            // Handle quoted JSON
            if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() > 1) {
                raw = raw.substring(1, raw.length() - 1);
            }

            // Unescape quotes
            if (raw.contains("\\\"")) {
                raw = raw.replace("\\\"", "\"");
            }

            return new JSONObject(raw);

        } catch (Exception e) {
            return new JSONObject()
                    .put("success", false)
                    .put("message", "Parse error: " + e.getMessage())
                    .put("raw_received", raw);
        }
    }

    private APIGatewayProxyResponseEvent error(int code, String msg) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(code)
                .withBody(new JSONObject().put("error", msg).toString());
    }
}