package com.example;

import com.amazonaws.services.lambda.runtime.Context;

import com.amazonaws.services.lambda.runtime.LambdaLogger;

import com.amazonaws.services.lambda.runtime.RequestHandler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import java.sql.Connection;

import java.sql.DriverManager;

import java.sql.PreparedStatement;

import java.sql.ResultSet;

import java.util.Base64;

import java.util.Properties;

import org.json.JSONArray;

import org.json.JSONObject;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

import software.amazon.awssdk.regions.Region;

import software.amazon.awssdk.services.rds.RdsUtilities;

import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

 

public class LambdaGetPhotosDB implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

 

    private static final String RDS_INSTANCE_HOSTNAME

            = System.getenv("RDS_HOST") != null ? 
            System.getenv("RDS_HOST") : "localhost";

    private static final int RDS_INSTANCE_PORT = Integer.parseInt(System.getenv("RDS_PORT") != null ? 
            System.getenv("RDS_PORT") : "3306");

    private static final String DB_USER = System.getenv("DB_USER") != null ? 
            System.getenv("DB_USER") : "admin";

    private static final String JDBC_URL

            = "jdbc:mysql://" + RDS_INSTANCE_HOSTNAME

            + ":" + RDS_INSTANCE_PORT + "/CloudDatabase";

 

    @Override

    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {

        LambdaLogger logger = context.getLogger();

        JSONArray items = new JSONArray();

        try {
            // ===================== 1. PARSE BODY TO GET EMAIL & TOKEN =====================
            String bodyStr = request.getBody();
            if (bodyStr == null || bodyStr.isEmpty()) {
                return error(400, "Request body is missing");
            }

            JSONObject body = new JSONObject(bodyStr);
            String email = body.optString("email", null);
            String token = body.optString("token", null);

            // ===================== 2. GATEKEEPER: TOKEN VALIDATION =====================
            if (email == null || token == null) {
                return error(400, "Missing email or token");
            }

            // Call helper class to validate token
            if (!LambdaTokenChecker.isValid(email, token)) {
                logger.log("Unauthorized access attempt for email: " + email);
                return error(401, "Unauthorized: Invalid token");
            }

            logger.log("Token validated. Fetching photos for: " + email);
            

            Class.forName("com.mysql.cj.jdbc.Driver");

            Connection mySQLClient = 

                    DriverManager.getConnection(JDBC_URL, 

                            setMySqlConnectionProperties());

            

            //PreparedStatement st = mySQLClient.prepareStatement("SELECT 1");

            //st.execute();

            //result = "Success!";

            PreparedStatement st = mySQLClient.prepareStatement(

                    "SELECT ID, Description, S3Key, user_email FROM Photos"

            );

             ResultSet rs = st.executeQuery();

             while (rs.next()) {

                    JSONObject item = new JSONObject();

                    item.put("ID", rs.getInt("ID"));

                    item.put("Description", rs.getString("Description"));

                    item.put("S3Key", rs.getString("S3Key"));

                    item.put("UserEmail", rs.getString("user_email"));

                    items.put(item);

                }

          

        } catch (ClassNotFoundException ex) {

            logger.log(ex.toString());

        } catch (Exception ex) {

            logger.log(ex.toString());

        }


 

        APIGatewayProxyResponseEvent response

                = new APIGatewayProxyResponseEvent();

        response.setStatusCode(200);

        response.setBody(items.toString());

        response.withIsBase64Encoded(false);

        response.setHeaders(java.util.Collections

                .singletonMap("Content-Type", "application/json"));

        return response;

 

    }

 

    private static Properties setMySqlConnectionProperties() throws Exception {

        Properties mysqlConnectionProperties = new Properties();

        mysqlConnectionProperties.setProperty("useSSL", "true");

        mysqlConnectionProperties.setProperty("user", DB_USER);

        mysqlConnectionProperties.setProperty("password", generateAuthToken()); // HERE

        return mysqlConnectionProperties;

    }

 

    private static String generateAuthToken() throws Exception {

 

        RdsUtilities rdsUtilities = RdsUtilities.builder().build();

 

        // Generate the authentication token

        String authToken

                = rdsUtilities.generateAuthenticationToken(

                        GenerateAuthenticationTokenRequest.builder()

                                .hostname(RDS_INSTANCE_HOSTNAME)

                                .port(RDS_INSTANCE_PORT)

                                .username(DB_USER)

                                .region(Region.AP_SOUTHEAST_1)

                                .credentialsProvider(DefaultCredentialsProvider.create())

                                .build());

        return authToken;

 

    }

 

    private APIGatewayProxyResponseEvent error(int code, String msg) {

        return new APIGatewayProxyResponseEvent()

                .withStatusCode(code)

                .withBody(new JSONObject().put("error", msg).toString());

    }

}
