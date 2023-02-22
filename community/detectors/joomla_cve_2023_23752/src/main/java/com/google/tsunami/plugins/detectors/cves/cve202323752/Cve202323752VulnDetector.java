/*
 * Copyright 2020 Google LLC
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
package com.google.tsunami.plugins.detectors.cves.cve202323752;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.net.HttpHeaders.*;
import static com.google.tsunami.common.data.NetworkEndpointUtils.toUriAuthority;
import static com.google.tsunami.common.net.http.HttpRequest.get;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.common.net.MediaType;
import com.google.protobuf.util.Timestamps;
import com.google.tsunami.common.data.NetworkServiceUtils;
import com.google.tsunami.common.net.http.HttpClient;
import com.google.tsunami.common.net.http.HttpHeaders;
import com.google.tsunami.common.net.http.HttpResponse;
import com.google.tsunami.common.net.http.HttpStatus;
import com.google.tsunami.common.time.UtcClock;
import com.google.tsunami.plugin.PluginType;
import com.google.tsunami.plugin.VulnDetector;
import com.google.tsunami.plugin.annotations.PluginInfo;
import com.google.tsunami.proto.AdditionalDetail;
import com.google.tsunami.proto.DetectionReport;
import com.google.tsunami.proto.DetectionReportList;
import com.google.tsunami.proto.DetectionStatus;
import com.google.tsunami.proto.NetworkService;
import com.google.tsunami.proto.Severity;
import com.google.tsunami.proto.TargetInfo;
import com.google.tsunami.proto.TextData;
import com.google.tsunami.proto.Vulnerability;
import com.google.tsunami.proto.VulnerabilityId;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import javax.inject.Inject;

/** A {@link VulnDetector} that detects the CVE-2023-23752 vulnerability. */
@PluginInfo(
    type = PluginType.VULN_DETECTION,
    name = "Cve202323752VulnDetector",
    version = "0.1",
    description =
        "CVE-2023-23752: An improper access check allows unauthorized access to webservice"
            + " endpoints",
    author = "Amammad",
    bootstrapModule = Cve202323752DetectorBootstrapModule.class)
public final class Cve202323752VulnDetector implements VulnDetector {
    private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

    @VisibleForTesting
    static final String VULNERABLE_PATH = "api/index.php/v1/config/application?public=true";

    @VisibleForTesting static final String DETECTION_STRING_1 = "\"links\":";
    @VisibleForTesting static final String DETECTION_STRING_2 = "\"attributes\":";
    @VisibleForTesting static final String DETECTION_STRING_BY_HEADER_1 = "application/json";
    @VisibleForTesting static final String DETECTION_STRING_BY_HEADER_2 = "application/vnd.api+json";
    @VisibleForTesting static final int DETECTION_STRING_BY_STATUS = HttpStatus.OK.code();
    private final HttpClient httpClient;

    private final Clock utcClock;

    @Inject
    Cve202323752VulnDetector(@UtcClock Clock utcClock, HttpClient httpClient) {
        this.httpClient = checkNotNull(httpClient);
        this.utcClock = checkNotNull(utcClock);
    }

    private static StringBuilder buildTarget(NetworkService networkService) {
        StringBuilder targetUrlBuilder = new StringBuilder();
        if (NetworkServiceUtils.isWebService(networkService)) {
            targetUrlBuilder.append(NetworkServiceUtils.buildWebApplicationRootUrl(networkService));
        } else {
            targetUrlBuilder
                .append("http://")
                .append(toUriAuthority(networkService.getNetworkEndpoint()))
                .append("/");
        }
        return targetUrlBuilder;
    }

    @Override
    public DetectionReportList detect(
        TargetInfo targetInfo, ImmutableList<NetworkService> matchedServices) {
        logger.atInfo().log("CVE-2023-23752 starts detecting.");

        return DetectionReportList.newBuilder()
            .addAllDetectionReports(
                matchedServices.stream()
                    .filter(NetworkServiceUtils::isWebService)
                    .filter(this::isServiceVulnerable)
                    .map(networkService -> buildDetectionReport(targetInfo, networkService))
                    .collect(toImmutableList()))
            .build();
    }

    private boolean isServiceVulnerable(NetworkService networkService) {

        HttpHeaders httpHeaders =
            HttpHeaders.builder()
                .addHeader(CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8.type())
                .addHeader(
                    ACCEPT,
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .addHeader(UPGRADE_INSECURE_REQUESTS, "1")
                .addHeader(ACCEPT_LANGUAGE, "Accept-Language: en-US,en;q=0.5")
                .addHeader(ACCEPT_ENCODING, "gzip, deflate")
                .build();

        String targetVulnerabilityUrl = buildTarget(networkService).append(VULNERABLE_PATH).toString();
        try {
            HttpResponse httpResponse =
                httpClient.send(
                    get(targetVulnerabilityUrl).setHeaders(httpHeaders).build(), networkService);
            if (httpResponse.status().code() != DETECTION_STRING_BY_STATUS
                || !httpResponse.bodyString().isPresent()) {
                return false;
            }
            String content_type_value = "";
            if (httpResponse.headers().get("Content-Type").isPresent()) {
                content_type_value = httpResponse.headers().get("Content-Type").toString();

            } else if (httpResponse.headers().get("content-type").isPresent()) {
                content_type_value = httpResponse.headers().get("Content-Type").toString();
            } else {
                return false;
            }
            if (!content_type_value.contains(DETECTION_STRING_BY_HEADER_1)
                && !content_type_value.contains(DETECTION_STRING_BY_HEADER_2)) {
                return false;
            }
            if (httpResponse.status().code() == 200
                && httpResponse.bodyString().get().contains(DETECTION_STRING_1)
                && httpResponse.bodyString().get().contains(DETECTION_STRING_2)) {
                return true;
            }
        } catch (IOException | AssertionError e) {
            logger.atWarning().withCause(e).log("Request to target %s failed", networkService);
            return false;
        }
        return false;
    }

    private DetectionReport buildDetectionReport(
        TargetInfo targetInfo, NetworkService vulnerableNetworkService) {
        return DetectionReport.newBuilder()
            .setTargetInfo(targetInfo)
            .setNetworkService(vulnerableNetworkService)
            .setDetectionTimestamp(Timestamps.fromMillis(Instant.now(utcClock).toEpochMilli()))
            .setDetectionStatus(DetectionStatus.VULNERABILITY_VERIFIED)
            .setVulnerability(
                Vulnerability.newBuilder()
                    .setMainId(
                        VulnerabilityId.newBuilder()
                            .setPublisher("TSUNAMI_COMMUNITY")
                            .setValue("CVE_2023_23752"))
                    .setSeverity(Severity.CRITICAL)
                    .setTitle("Joomla unauthorized access to webservice endpoints")
                    .setDescription(
                        "CVE-2023-23752: An improper access check allows unauthorized access to"
                            + " webservice endpoints")
                    .setRecommendation("Upgrade to version 4.2.8 and higher")
                    .addAdditionalDetails(
                        AdditionalDetail.newBuilder()
                            .setTextData(
                                TextData.newBuilder()
                                    .setText(
                                        "attacker can get critical information of database and"
                                            + " webserver like passwords by this vulnerability"))))
            .build();
    }
}