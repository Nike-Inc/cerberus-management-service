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

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;

import java.util.Map;

import static com.nike.cerberus.aws.sts.AwsStsHttpHeaders.*;

/**
 * POJO representing AWS Signature Version 4 headers
 */
public final class AwsStsHttpHeader {
    private String date;
    private String amzDate;
    private String amzSecurityToken;
    private String authorization;

    public AwsStsHttpHeader(String date, String amzDate, String amzSecurityToken, String authorization) {
        Preconditions.checkNotNull(date);
        Preconditions.checkNotNull(amzDate);
        Preconditions.checkNotNull(amzSecurityToken);
        Preconditions.checkNotNull(authorization);

        this.date = date;
        this.amzDate = amzDate;
        this.amzSecurityToken = amzSecurityToken;
        this.authorization = authorization;
    }

    public Map<String, String> generateHeaders() {
        Map<String, String> headers = Maps.newHashMap();
        headers.put(HEADER_DATE, date);
        headers.put(HEADER_AUTHORIZATION, authorization);
        headers.put(HEADER_X_AMZ_DATE, amzDate);
        headers.put(HEADER_X_AMZ_SECURITY_TOKEN, amzSecurityToken);
        return headers;
    }

    public String getDate() {
        return date;
    }

    public AwsStsHttpHeader setDate(String date) {
        this.date = date;
        return this;
    }

    public String getAmzDate() {
        return amzDate;
    }

    public AwsStsHttpHeader setAmzDate(String amzDate) {
        this.amzDate = amzDate;
        return this;
    }

    public String getAmzSecurityToken() {
        return amzSecurityToken;
    }

    public AwsStsHttpHeader setAmzSecurityToken(String amzSecurityToken) {
        this.amzSecurityToken = amzSecurityToken;
        return this;
    }

    public String getAuthorization() {
        return authorization;
    }

    public AwsStsHttpHeader setAuthorization(String authorization) {
        this.authorization = authorization;
        return this;
    }
}