package com.wtc.shared.storage;

import com.wtc.shared.config.WtcProperties;
import java.net.URI;
import java.time.Duration;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/** Adaptateur S3 (AWS, MinIO, Ceph…). Démarrage refusé si le stockage n'est pas configuré. */
@Component
final class S3ObjectStorage implements ObjectStorage, AutoCloseable {

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    S3ObjectStorage(WtcProperties properties) {
        WtcProperties.Storage storage = properties.storage();
        if (storage.endpoint() == null || storage.endpoint().isBlank()) {
            throw new IllegalStateException(
                    "wtc.storage.endpoint est obligatoire (WTC_STORAGE_ENDPOINT)");
        }
        if (storage.accessKey() == null
                || storage.accessKey().isBlank()
                || storage.secretKey() == null
                || storage.secretKey().isBlank()) {
            throw new IllegalStateException(
                    "wtc.storage.access-key et secret-key sont obligatoires");
        }
        this.bucket = storage.bucket();
        StaticCredentialsProvider credentials =
                StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(storage.accessKey(), storage.secretKey()));
        Region region = Region.of(storage.region());
        S3Configuration s3 =
                S3Configuration.builder().pathStyleAccessEnabled(storage.pathStyle()).build();
        this.client =
                S3Client.builder()
                        .endpointOverride(URI.create(storage.endpoint()))
                        .region(region)
                        .credentialsProvider(credentials)
                        .serviceConfiguration(s3)
                        .build();
        String publicEndpoint =
                storage.publicEndpoint() == null || storage.publicEndpoint().isBlank()
                        ? storage.endpoint()
                        : storage.publicEndpoint();
        this.presigner =
                S3Presigner.builder()
                        .endpointOverride(URI.create(publicEndpoint))
                        .region(region)
                        .credentialsProvider(credentials)
                        .serviceConfiguration(s3)
                        .build();
        if (storage.autoCreateBucket()) {
            ensureBucket();
        }
    }

    private void ensureBucket() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException missing) {
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                RequestBody.fromBytes(content));
    }

    @Override
    public void delete(String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public String presignedGetUrl(String key, Duration ttl) {
        return presigner
                .presignGetObject(
                        GetObjectPresignRequest.builder()
                                .signatureDuration(ttl)
                                .getObjectRequest(
                                        GetObjectRequest.builder().bucket(bucket).key(key).build())
                                .build())
                .url()
                .toString();
    }

    @Override
    public void close() {
        client.close();
        presigner.close();
    }
}
