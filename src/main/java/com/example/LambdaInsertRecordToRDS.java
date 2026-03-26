package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Base64;
import java.util.Properties;

import org.json.JSONObject;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

public class LambdaInsertRecordToRDS implements RequestHandler<Object, String> {

    // ---------------- RDS SETTINGS ----------------
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

            String key = payload.getString("key");
            String description = payload.getString("description");
            String email = payload.getString("email");

            // ============ CONNECT TO RDS ============
            Class.forName("com.mysql.cj.jdbc.Driver");

            Connection conn = DriverManager.getConnection(
                    JDBC_URL,
                    buildConnectionProperties()
            );

            // ============ INSERT DB ROW ============
            PreparedStatement st = conn.prepareStatement(
                    "INSERT INTO Photos (Description, S3Key, user_email) VALUES (?, ?, ?)"
            );

            st.setString(1, description);
            st.setString(2, key);
            st.setString(3, email);

            int rows = st.executeUpdate();
            logger.log("Inserted rows: " + rows);

            responseJson.put("success", true);
            responseJson.put("message", "Inserted successfully");
            responseJson.put("rows", rows);

        } catch (Exception e) {
            logger.log("ERROR in Lambda B: " + e);

            responseJson.put("success", false);
            responseJson.put("error", e.getMessage());
        }

        // Return Base64-encoded JSON back to Lambda A
        return Base64.getEncoder().encodeToString(responseJson.toString().getBytes());
    }

    // ===================================================================
    // Convert "{key=value, desc=test}" to {"key":"value","desc":"test"}
    // ===================================================================
    private JSONObject convertMapStringToJson(String input) {
        JSONObject json = new JSONObject();

        input = input.trim();

        // remove outer braces
        if (input.startsWith("{") && input.endsWith("}")) {
            input = input.substring(1, input.length() - 1);
        }

        if (input.isEmpty())
            return json;

        // split by comma
        String[] pairs = input.split(",");

        for (String p : pairs) {
            String[] kv = p.split("=", 2);  // split only first "="

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
