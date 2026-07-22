package com.jobmatchai.backend.service.storage;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

// Selected when app.storage.type=s3 (see application-production.properties). Credentials are
// NEVER read from application properties/config here - S3Client.builder() below relies entirely
// on the AWS SDK's own default credential provider chain (environment variables, then an
// EC2/Elastic Beanstalk instance IAM role, then a few other standard sources), resolved
// automatically with zero code difference depending on which one is actually in play. The
// recommended production setup is an EB instance role scoped to just this one bucket - see
// DEPLOYMENT.md - which needs no AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY env vars at all; static
// keys are documented there only as a fallback for local testing against a real bucket.
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
public class S3FileStorageService implements FileStorageService {

    @Value("${app.s3.bucket-name}")
    private String bucketName;

    @Value("${app.s3.region}")
    private String region;

    private S3Client client;

    // Built once at startup (not lazily on first use) specifically so the singleton bean never
    // races multiple request threads over initializing the same field.
    @PostConstruct
    private void init() {
        client = S3Client.builder().region(Region.of(region)).build();
    }

    @Override
    public void store(File source, String key) throws IOException {
        client.putObject(
                PutObjectRequest.builder().bucket(bucketName).key(key).build(),
                RequestBody.fromFile(source));
    }

    @Override
    public boolean exists(String key) {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(key).build());
        } catch (S3Exception e) {
            // See FileStorageService#delete's own comment - best-effort, never fails the caller.
        }
    }

    @Override
    public Resource loadAsResource(String key) throws IOException {
        InputStream stream = client.getObject(GetObjectRequest.builder().bucket(bucketName).key(key).build());
        // InputStreamResource's own getFilename() returns null by default (it has no filename
        // concept for a bare stream) - CVController's serveCvFile uses this purely as a fallback
        // display name when the candidate never had an originalFileName on record, so it still
        // needs to resolve to something rather than null.
        return new InputStreamResource(stream) {
            @Override
            public String getFilename() {
                return key;
            }
        };
    }

    @Override
    public <T> T withLocalFile(String key, FileFunction<T> action) throws IOException {
        Path tempFile = Files.createTempFile("cv-", "-" + key);
        try {
            client.getObject(GetObjectRequest.builder().bucket(bucketName).key(key).build(), tempFile);
            return action.apply(tempFile.toFile());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
