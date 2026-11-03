package com.example.kyc.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

@Service
public class S3StorageService {
    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final String bucket;
    private final boolean autoCreateBucket;
    private final long uploadUrlMinutes;
    private final long downloadUrlMinutes;

    public S3StorageService(S3Client s3Client,
                            S3Presigner presigner,
                            @Value("${app.s3.bucket:digital-wallet-documents}") String bucket,
                            @Value("${app.s3.auto-create-bucket:false}") boolean autoCreateBucket,
                            @Value("${app.s3.upload-url-minutes:15}") long uploadUrlMinutes,
                            @Value("${app.s3.download-url-minutes:10}") long downloadUrlMinutes) {
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.bucket = bucket;
        this.autoCreateBucket = autoCreateBucket;
        this.uploadUrlMinutes = uploadUrlMinutes;
        this.downloadUrlMinutes = downloadUrlMinutes;
    }

    @PostConstruct
    void ensureBucketExists() {
        if (!autoCreateBucket) return;
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception exception) {
            // This is intended for local MinIO only. Production buckets should be provisioned by IaC.
            if (exception.statusCode() == 404 || exception.statusCode() == 403) {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            } else {
                throw exception;
            }
        }
    }

    public PresignedUpload presignUpload(String objectKey, String contentType) {
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(uploadUrlMinutes));
        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(bucket).key(objectKey).contentType(contentType).build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(uploadUrlMinutes))
                .putObjectRequest(objectRequest).build();
        PresignedPutObjectRequest request = presigner.presignPutObject(presignRequest);
        return new PresignedUpload(request.url(), expiresAt);
    }

    public boolean objectExists(String objectKey) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
            return true;
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404 || exception.statusCode() == 403) return false;
            throw exception;
        }
    }

    public void putCsv(String objectKey, String csv) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket).key(objectKey).contentType("text/csv; charset=utf-8").build();
        s3Client.putObject(request, RequestBody.fromBytes(csv.getBytes(StandardCharsets.UTF_8)));
    }

    public PresignedDownload presignDownload(String objectKey) {
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(downloadUrlMinutes));
        GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(bucket).key(objectKey).build();
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(downloadUrlMinutes))
                .getObjectRequest(getObjectRequest).build();
        PresignedGetObjectRequest presigned = presigner.presignGetObject(request);
        return new PresignedDownload(presigned.url(), expiresAt);
    }

    public record PresignedUpload(URL url, Instant expiresAt) { }
    public record PresignedDownload(URL url, Instant expiresAt) { }
}
