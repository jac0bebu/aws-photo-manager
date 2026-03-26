package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Properties;

import org.json.JSONObject;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

public class LambdaDeleteRecordFromRDS implements RequestHandler<Object, String> {

    // ---------------- RDS SETTINGS ----------------
    // Make sure to fill this in, or keep consistent with your Insert Lambda
    private static final String RDS_HOST = System.getenv("RDS_HOST") != null ? 
        System.getenv("RDS_HOST") : "localhost";
    private static final int RDS_PORT = Integer.parseInt(System.getenv("RDS_PORT") != null ? 
        System.getenv("RDS_PORT") : "3306");
    private static final String DB_USER = System.getenv("DB_USER") != null ? 
        System.getenv("DB_USER") : "admin";
    private static final String JDBC_URL =
            "jdbc:mysql://" + RDS_HOST + ":" + RDS_PORT + "/CloudDatabase";

    @Override
    public String handleRequest(Object input, Context context) {

        LambdaLogger logger = context.getLogger();
        JSONObject responseJson = new JSONObject();

        try {
            // ============ RAW INPUT ============
            String rawInput = input.toString();
            logger.log("RAW INPUT >>> " + rawInput);

            // ============ PARSE TO JSON ============
            JSONObject payload = convertMapStringToJson(rawInput);
            logger.log("CLEAN JSON >>> " + payload);

            // Extract key and email for deletion
            String key = payload.optString("key", "").trim();
            String email = payload.optString("email", "").trim();

            logger.log("Attempting delete - Key: '" + key + "', Email: '" + email + "'");

            if (key.isEmpty()) {
                throw new RuntimeException("Missing 'key' in input");
            }

            if (email.isEmpty()) {
                throw new RuntimeException("Missing 'email' in input");
            }

            // ============ CONNECT TO RDS ============
            Class.forName("com.mysql.cj.jdbc.Driver");

            Connection conn = DriverManager.getConnection(
                    JDBC_URL,
                    buildConnectionProperties()
            );

            // ============ DELETE DB ROW WITH OWNERSHIP VERIFICATION ============
            // Only delete if both key AND email match (correct column name: S3Key)
            PreparedStatement st = conn.prepareStatement(
                    "DELETE FROM Photos WHERE S3Key = ? AND user_email = ?"
            );

            st.setString(1, key);
            st.setString(2, email);

            int rows = st.executeUpdate();
            logger.log("Deleted rows: " + rows);

            if (rows > 0) {
                responseJson.put("success", true);
                responseJson.put("message", "Record deleted successfully");
                responseJson.put("rows_deleted", rows);
            } else {
                // User tried to delete a file they don't own
                responseJson.put("success", false);
                responseJson.put("message", "Permission Denied: You are not authorized to delete this file");
                responseJson.put("rows_deleted", 0);
                logger.log("DELETE BLOCKED - Email '" + email + "' does not own file '" + key + "'");
            }

        } catch (Exception e) {
            logger.log("ERROR in Lambda Delete: " + e.getMessage());
            responseJson.put("success", false);
            responseJson.put("error", e.getMessage());
        }

        // Return JSON back to caller
        return responseJson.toString();
    }

    // ===================================================================
    // Convert "{key=value, desc=test}" to {"key":"value","desc":"test"}
    // ===================================================================
    private JSONObject convertMapStringToJson(String input) {
        JSONObject json = new JSONObject();

        input = input.trim();

        if (input.startsWith("{") && input.endsWith("}")) {
            input = input.substring(1, input.length() - 1);
        }

        if (input.isEmpty())
            return json;

        String[] pairs = input.split(",");

        for (String p : pairs) {
            String[] kv = p.split("=", 2);

            if (kv.length == 2) {
                String key = kv[0].trim();
                String value = kv[1].trim();
                json.put(key, value);
            }
        }
        return json;
    }

    // ===================================================================
    // IAM Auth Token for MySQL
    // ===================================================================
    private Properties buildConnectionProperties() throws Exception {
        Properties props = new Properties();
        props.setProperty("useSSL", "true");
        props.setProperty("user", DB_USER);
        props.setProperty("password", generateAuthToken());
        return props;
    }

    private String generateAuthToken() throws Exception {
        RdsUtilities utilities = RdsUtilities.builder().build();

        return utilities.generateAuthenticationToken(
                GenerateAuthenticationTokenRequest.builder()
                        .hostname(RDS_HOST)
                        .port(RDS_PORT)
                        .username(DB_USER)
                        .region(Region.AP_SOUTHEAST_1)
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .build()
        );
    }
}