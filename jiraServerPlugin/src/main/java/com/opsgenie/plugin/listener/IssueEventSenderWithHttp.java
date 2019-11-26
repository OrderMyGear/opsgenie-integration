package com.opsgenie.plugin.listener;

import com.google.common.base.Charsets;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.logging.log4j.core.util.IOUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;

@Component
public class IssueEventSenderWithHttp implements IssueEventSender {


    private HttpClient httpClient;

    public IssueEventSenderWithHttp() {
        httpClient = new HttpClient();
    }

    private final static Integer MAX_RETRY_COUNT = 5;

    private final static Long MAX_RETRY_DURATION = 40_000L;

    @Override
    public SendResult send(String baseUrl, String apiKey, String webhookEventAsJson) {
        PostMethod postMethod = new PostMethod(baseUrl + "?apiKey=" + apiKey);
        StringRequestEntity requestEntity = null;
        try {
            requestEntity = new StringRequestEntity(webhookEventAsJson, "application/json", Charsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            //ignored
        }
        postMethod.setRequestEntity(requestEntity);
        return executeWithRetry(postMethod);
    }

    @Override
    public SendMethod method() {
        return SendMethod.HTTP;
    }

    private SendResult executeWithRetry(PostMethod postMethod) {

        SendResult result = new SendResult();
        result.setSuccess(false);
        int currentRetryCount = 0;
        long retryStartTime = System.currentTimeMillis();
        int statusCode;
        String failReason = null;

        while (retryPresent(currentRetryCount, retryStartTime)) {
            try {
                currentRetryCount++;
                statusCode = httpClient.executeMethod(postMethod);
                if (statusCode >= 200 && statusCode < 300) {
                    result.setSuccess(true);
                    break;
                } else if (!isRetryable(statusCode)) {
                    failReason = buildLogFromHttpResponse(statusCode, postMethod);
                    break;
                } else {
                    failReason = buildLogFromHttpResponse(statusCode, postMethod);
                }
            } catch (HttpException e) {
                //do not retry
                failReason = e.getMessage();
                break;
            } catch (IOException e) {
                //retry
                failReason = e.getMessage();
            }
        }
        postMethod.releaseConnection();
        return result.setNumberOfAttempts(currentRetryCount)
                .setFailReason(failReason);
    }

    private boolean retryPresent(int currentRetryCount, long retryStartTime) {
        long currentTime = System.currentTimeMillis();
        try {
            Thread.sleep(currentRetryCount * 100L);
        } catch (InterruptedException e) {
            //ignored
        }
        return (currentRetryCount < MAX_RETRY_COUNT) && ((currentTime - retryStartTime) <= MAX_RETRY_DURATION);
    }

    private String buildLogFromHttpResponse(int statusCode, PostMethod postMethod) {
        String message;
        try {
            Reader targetReader = new InputStreamReader(postMethod.getResponseBodyAsStream());
            message = IOUtils.toString(targetReader);
            targetReader.close();
        } catch (IOException e) {
            message = "Could not get responseBody";
        }
        return "Status code: " + statusCode + " " + message;
    }

    private boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }
}