package com.oficina.support;

import software.amazon.awssdk.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory SDK transport: optional expired STS response, then stalls until SDK cancellation. */
public final class StallingStsHttpClient implements SdkHttpClient {
    public final AtomicInteger stalledAttempts = new AtomicInteger();
    public final AtomicInteger abortedAttempts = new AtomicInteger();
    public final AtomicInteger interruptedAttempts = new AtomicInteger();
    private boolean returnExpiredCredentials;

    public StallingStsHttpClient(boolean returnExpiredCredentials) {
        this.returnExpiredCredentials = returnExpiredCredentials;
    }

    @Override public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
        return new ExecutableHttpRequest() {
            private final CountDownLatch cancelled = new CountDownLatch(1);
            @Override public HttpExecuteResponse call() throws IOException {
                if (returnExpiredCredentials) {
                    returnExpiredCredentials = false;
                    String xml = """
                            <AssumeRoleWithWebIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
                              <AssumeRoleWithWebIdentityResult><Credentials>
                                <AccessKeyId>synthetic-key</AccessKeyId><SecretAccessKey>synthetic-secret</SecretAccessKey>
                                <SessionToken>synthetic-session</SessionToken><Expiration>%s</Expiration>
                              </Credentials></AssumeRoleWithWebIdentityResult>
                            </AssumeRoleWithWebIdentityResponse>
                            """.formatted(Instant.now().minusSeconds(60));
                    return HttpExecuteResponse.builder().response(SdkHttpResponse.builder().statusCode(200).build())
                            .responseBody(AbortableInputStream.create(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))).build();
                }
                stalledAttempts.incrementAndGet();
                try {
                    // A finite fallback makes a broken SDK timeout fail the test instead of hanging the suite.
                    cancelled.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    interruptedAttempts.incrementAndGet();
                    Thread.currentThread().interrupt();
                }
                throw new IOException("SYNTHETIC_STS_TIMEOUT");
            }
            @Override public void abort() { abortedAttempts.incrementAndGet(); cancelled.countDown(); }
        };
    }

    @Override public void close() {}
}
