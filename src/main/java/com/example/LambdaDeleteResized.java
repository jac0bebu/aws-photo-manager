package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class LambdaDeleteResized implements RequestHandler<S3Event, String> {

    @Override
    public String handleRequest(S3Event s3event, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("Delete handler started...\n");

        S3Client s3Client = S3Client.builder().build();

        for (S3EventNotificationRecord record : s3event.getRecords()) {
            String eventName = record.getEventName();
            logger.log("Event name: " + eventName + "\n");

            // Only act on delete events
            if (!eventName.startsWith("ObjectRemoved:")) {
                logger.log("Skipping non-delete event: " + eventName + "\n");
                continue;
            }

            // Source bucket and key (original image)
            String srcBucket = record.getS3().getBucket().getName();
            String srcKey = record.getS3().getObject().getUrlDecodedKey();

            logger.log("Original deleted object: " + srcBucket + "/" + srcKey + "\n");

            // Your naming rule for resized images
            String dstBucket = "resized-" + srcBucket;      // e.g. resized-cob-kun-public
            String dstKey = "resized-" + srcKey;            // e.g. resized-images/cat.jpg

            logger.log("Trying to delete resized object: " + dstBucket + "/" + dstKey + "\n");

            try {
                DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                        .bucket(dstBucket)
                        .key(dstKey)
                        .build();

                s3Client.deleteObject(deleteRequest);
                logger.log("Deleted resized object: " + dstBucket + "/" + dstKey + "\n");

            } catch (S3Exception e) {
                logger.log("Failed to delete resized object: " + e.awsErrorDetails().errorMessage() + "\n");
            }
        }

        return "Done";
    }
}
