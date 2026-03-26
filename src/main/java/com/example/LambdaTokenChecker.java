package com.example;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

public class LambdaTokenChecker {

    // 1. Function to get SECRET_KEY from SSM Parameter Store
    public static String getSecretKey() {
        String sessionToken = System.getenv("AWS_SESSION_TOKEN");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            String url = "http://localhost:2773/systemsmanager/parameters/get/?name=key&withDecryption=true";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-Aws-Parameters-Secrets-Token", sessionToken)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JSONObject json = new JSONObject(response.body());
                return json.getJSONObject("Parameter").getString("Value");
            }
        } catch (Exception e) {
            System.err.println("Error fetching SSM: " + e.getMessage());
        }
        return null;
    }

    // 2. Function to generate HMAC signature from Email + SecretKey
    public static String generateHMAC(String data, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hmacBytes);
    }

    // 3. MOST IMPORTANT: Validate if Token is valid
    public static boolean isValid(String emailFromWeb, String tokenFromWeb) {
        try {
            // Step A: Get secret key from SSM
            String secretKey = getSecretKey();
            if (secretKey == null) return false;

            // Step B: Calculate expected token based on email from user
            String expectedToken = generateHMAC(emailFromWeb, secretKey);

            // Step C: Compare calculated token with token from web request
            return expectedToken.equals(tokenFromWeb);
            
        } catch (Exception e) {
            return false;
        }
    }
}