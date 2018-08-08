/*
 * Copyright (c) 2018 Nike, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nike.cerberus.aws.sts;

import com.amazonaws.services.securitytoken.model.GetCallerIdentityResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import com.nike.backstopper.exception.ApiException;
import com.nike.cerberus.server.config.guice.AwsStsGuiceModule;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Before;
import org.junit.Test;

import javax.net.ssl.SSLException;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("ThrowableResultOfMethodCallIgnored")
public class AwsStsHttpClientTest {

    private ObjectMapper objectMapper;
    private OkHttpClient httpClient;
    private AwsStsHttpClient awsStsHttpClient;

    @Before
    public void setup() {
        AwsStsGuiceModule module = new AwsStsGuiceModule();
        objectMapper = module.getObjectMapper();
        httpClient = mock(OkHttpClient.class);
        awsStsHttpClient = new AwsStsHttpClient(httpClient, objectMapper);
    }

    @Test
    public void test_execute() throws Exception {

        String testArn = "test arn";

        Response response = createFakeResponse(200, testArn);
        Call call = mock(Call.class);
        when(call.execute()).thenReturn(response);
        when(httpClient.newCall(any())).thenReturn(call);

        // invoke method under test
        awsStsHttpClient.execute(null, GetCallerIdentityFullResponse.class);
    }

    @Test(expected = ApiException.class)
    public void test_execute_handles_error() throws Exception {

        Call call = mock(Call.class);
        when(call.execute()).thenReturn(createFakeUnparsableResponse());
        when(httpClient.newCall(any())).thenReturn(call);

        // invoke method under test
        awsStsHttpClient.execute(null, GetCallerIdentityFullResponse.class);
    }

    @Test
    public void test_buildRequest() throws Exception {
        String method = "POST";

        // invoke method under test
        Request request = awsStsHttpClient.buildRequest(Maps.newHashMap());

        assertEquals(2, request.headers().size());
        assertEquals("https://sts.amazonaws.com/", request.url().uri().toString());
        assertEquals(method, request.method());
        assertEquals(43, request.body().contentLength());
    }

    @Test
    public void test_toApiException_ssl_plain_text_error() {
        SSLException sslException = new SSLException("Unrecognized SSL message, plaintext connection?");
        assertTrue(awsStsHttpClient.toApiException(sslException).getMessage().contains("Unrecognized SSL message may be due to a web proxy"));
    }

    @Test
    public void test_toApiException_with_generic_error() {
        assertEquals("I/O error while communicating with AWS STS.", awsStsHttpClient.toApiException(new IOException()).getMessage());
    }

    @Test
    public void test_parseResponseBody() throws Exception {

        String testArn = "test arn";

        Response response = createFakeResponse(200, testArn);

        // invoke method under test
        GetCallerIdentityFullResponse actualResponse = awsStsHttpClient.parseResponseBody(response, GetCallerIdentityFullResponse.class);

        assertEquals(testArn, actualResponse.getGetCallerIdentityResponse().getGetCallerIdentityResult().getArn());
    }


    @Test(expected = ApiException.class)
    public void test_parseResponseBody_malformed_response() throws Exception {
        // invoke method under test
        awsStsHttpClient.parseResponseBody(createFakeUnparsableResponse(), GetCallerIdentityFullResponse.class);
    }

    @Test(expected = ApiException.class)
    public void test_4xx_response() throws Exception {
        Call call = mock(Call.class);
        when(call.execute()).thenReturn(createFakeResponse(400, "test arn"));
        when(httpClient.newCall(any())).thenReturn(call);

        // invoke method under test
        awsStsHttpClient.execute(null, GetCallerIdentityFullResponse.class);
    }


    private Response createFakeResponse(int statusCode, String testArn) throws JsonProcessingException {
        GetCallerIdentityResult result = new GetCallerIdentityResult();
        result.setArn(testArn);
        GetCallerIdentityResponse response = new GetCallerIdentityResponse();
        response.setGetCallerIdentityResult(result);
        GetCallerIdentityFullResponse value = new GetCallerIdentityFullResponse();
        value.setGetCallerIdentityResponse(response);

        String body = objectMapper.writeValueAsString(value);

        return new Response.Builder()
                .request(new Request.Builder().url("https://example.com/fake").build())
                .body(ResponseBody.create(null, body))
                .protocol(Protocol.HTTP_2)
                .code(statusCode)
                .build();
    }

    private Response createFakeUnparsableResponse() {
        String body = "{ \"foo\": \"bar\" ";

        return new Response.Builder()
                .request(new Request.Builder().url("https://example.com/fake").build())
                .body(ResponseBody.create(null, body))
                .protocol(Protocol.HTTP_2)
                .code(200)
                .build();
    }

}