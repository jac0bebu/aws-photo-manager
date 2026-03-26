package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class LambdaGenerateToken implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String PARAMETER_NAME = "key";

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        try {
            // 1. Take Secret Key from SSM through Lambda Extension (localhost)
            String secretKey = getSecretFromParameterStore(PARAMETER_NAME, context);

            if (secretKey == null || secretKey.isEmpty()) {
                return error(500, "Server Error: Could not retrieve secret from Parameter Store.");
            }

            // 2. Parse Input (Email)
            String body = request.getBody();
            if (body == null || body.isEmpty()) return error(400, "Body missing");

            JSONObject json = new JSONObject(body);
            String email = json.optString("email", "");

            if (email.isEmpty()) return error(400, "Email is required");

            // 3. Create Token (Email + Secret từ SSM)
            String token = generateHMAC(email, secretKey);

            // 4. Retuern Token
            JSONObject result = new JSONObject();
            result.put("token", token);

            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody(result.toString());

        } catch (Exception e) {
            return error(500, "Internal Server Error: " + e.getMessage());
        }
    }

    /**
     * Call Lambda Extension to get secret from AWS Systems Manager Parameter Store
     */
    private String getSecretFromParameterStore(String paramName, Context context) {
        try {
            
            String urlString = "http://localhost:2773/systemsmanager/parameters/get?name=" + paramName;
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            
            // Send AWS credentials to Extension for authentication (if running in Lambda environment)
            conn.setRequestProperty("X-Aws-Parameters-Secrets-Token", System.getenv("AWS_SESSION_TOKEN"));

            if (conn.getResponseCode() == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();

                // Parse response from Extension
                JSONObject jsonResponse = new JSONObject(response.toString());
                return jsonResponse.getJSONObject("Parameter").getString("Value");
            } else {
                context.getLogger().log("Error fetching SSM: " + conn.getResponseMessage());
            }
        } catch (Exception e) {
            context.getLogger().log("Exception fetching SSM: " + e.getMessage());
        }
        return null;
    }

    private String generateHMAC(String data, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hmacBytes);
    }

    private APIGatewayProxyResponseEvent error(int code, String msg) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(code)
                .withBody(new JSONObject().put("error", msg).toString());
    }
}