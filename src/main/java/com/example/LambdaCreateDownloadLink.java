package com.example;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import org.json.JSONObject;

import java.net.URL;
import java.util.Date;

public class LambdaCreateDownloadLink implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    // configure this in AWS Console Environment Variables
    private static final String BUCKET_NAME = System.getenv("S3_BUCKET");
    private final AmazonS3 s3 = AmazonS3ClientBuilder.defaultClient();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        try {
            // 1. Parse Input
            String body = request.getBody();
            if (body == null) return response(400, "Body missing");
            
            JSONObject json = new JSONObject(body);
            String key = json.optString("key", "");

            if (key.isEmpty()) return response(400, "File Key is required");

            // 2. Generate Presigned URL (Valid for 5 minutes)
            Date expiration = new Date();
            long expTimeMillis = expiration.getTime();
            expTimeMillis += 1000 * 60 * 5; // 5 minutes
            expiration.setTime(expTimeMillis);

            GeneratePresignedUrlRequest generatePresignedUrlRequest =
                    new GeneratePresignedUrlRequest(BUCKET_NAME, key)
                            .withMethod(HttpMethod.GET)
                            .withExpiration(expiration);

            URL url = s3.generatePresignedUrl(generatePresignedUrlRequest);

            // 3. Return the URL
            JSONObject result = new JSONObject();
            result.put("downloadUrl", url.toString());

            return response(200, result.toString());

        } catch (Exception e) {
            return response(500, "Error: " + e.getMessage());
        }
    }

    private APIGatewayProxyResponseEvent response(int code, String body) {
        // Handle non-JSON error messages safely
        String finalBody = body.trim().startsWith("{") ? body : new JSONObject().put("error", body).toString();
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(code)
                .withBody(finalBody);
    }
}