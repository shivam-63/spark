/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.palantir.shuffle.async.s3a;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.http.conn.ssl.SdkTLSSocketFactory;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.S3ClientOptions;
import com.palantir.logsafe.Preconditions;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.exceptions.SafeIllegalArgumentException;

import java.io.IOException;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Optional;

import javax.net.ssl.SSLContext;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.s3a.Constants;
import org.apache.hadoop.fs.s3a.S3AUtils;
import org.apache.hadoop.fs.s3a.S3ClientFactory;
import org.apache.hadoop.util.VersionInfo;
import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.apache.http.conn.util.PublicSuffixMatcherLoader;
import org.conscrypt.Conscrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mirrors {@link org.apache.hadoop.fs.s3a.DefaultS3ClientFactory}, but forces the usage of
 * Conscrypt for the TLS implementation.
 */
public final class ConscryptS3ClientFactory extends Configured implements S3ClientFactory {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConscryptS3ClientFactory.class);

  @Override
  public AmazonS3 createS3Client(URI name) throws IOException {
    Configuration conf = getConf();
    AWSCredentialsProvider credentials = S3AUtils.createAWSCredentialProviderSet(name, conf);
    final ClientConfiguration awsConf = createAwsConf(getConf());
    AmazonS3 s3 = newAmazonS3Client(credentials, awsConf);
    return createAmazonS3Client(s3, conf);
  }

  /**
   * Create a new {@link ClientConfiguration}.
   *
   * @param conf The Hadoop configuration
   * @return new AWS client configuration
   */
  private static ClientConfiguration createAwsConf(Configuration conf) {
    final ClientConfiguration awsConf = new ClientConfiguration();
    initConnectionSettings(conf, awsConf);
    initProxySupport(conf, awsConf);
    initUserAgent(conf, awsConf);
    SecureRandom rand = awsConf.getSecureRandom();
    SSLContext ctx = getConscryptSslContext(rand).orElseGet(() -> getDefaultSslContext(rand));
    SdkTLSSocketFactory socketFactory = new SdkTLSSocketFactory(
        ctx, new DefaultHostnameVerifier(PublicSuffixMatcherLoader.getDefault()));
    awsConf.getApacheHttpClientConfig().setSslSocketFactory(socketFactory);
    return awsConf;
  }

  private static SSLContext getDefaultSslContext(SecureRandom rand) {
    SSLContext ctx;
    try {
      ctx = SSLContext.getInstance("TLS");
      ctx.init(null, null, rand);
    } catch (KeyManagementException | NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
    return ctx;
  }

  private static Optional<SSLContext> getConscryptSslContext(SecureRandom rand) {
    SSLContext ctx;
    try {
      ctx = SSLContext.getInstance("TLS", Conscrypt.newProvider());
      // Logic taken from ApacheConnectionManagerFactory
      ctx.init(null, null, rand);
    } catch (KeyManagementException | NoSuchAlgorithmException e) {
      LOGGER.warn("Failed to load Conscrypt SSL Context, falling back to the JVM's" +
          " implementation.", e);
      return Optional.empty();
    }
    return Optional.of(ctx);
  }

  /**
   * Wrapper around constructor for {@link AmazonS3} client.  Override this to
   * provide an extended version of the client
   *
   * @param credentials credentials to use
   * @param awsConf     AWS configuration
   * @return new AmazonS3 client
   */
  private AmazonS3 newAmazonS3Client(
      AWSCredentialsProvider credentials, ClientConfiguration awsConf) {
    return new AmazonS3Client(credentials, awsConf);
  }

  /**
   * Initializes all AWS SDK settings related to connection management.
   *
   * @param conf    Hadoop configuration
   * @param awsConf AWS SDK configuration
   */
  private static void initConnectionSettings(Configuration conf,
                                             ClientConfiguration awsConf) {
    awsConf.setMaxConnections(
        intOption(conf, Constants.MAXIMUM_CONNECTIONS,
            Constants.DEFAULT_MAXIMUM_CONNECTIONS, 1));
    boolean secureConnections = conf.getBoolean(Constants.SECURE_CONNECTIONS,
        Constants.DEFAULT_SECURE_CONNECTIONS);
    awsConf.setProtocol(secureConnections ? Protocol.HTTPS : Protocol.HTTP);
    awsConf.setMaxErrorRetry(intOption(conf, Constants.MAX_ERROR_RETRIES,
        Constants.DEFAULT_MAX_ERROR_RETRIES, 0));
    awsConf.setConnectionTimeout(intOption(conf, Constants.ESTABLISH_TIMEOUT,
        Constants.DEFAULT_ESTABLISH_TIMEOUT, 0));
    awsConf.setSocketTimeout(intOption(conf, Constants.SOCKET_TIMEOUT,
        Constants.DEFAULT_SOCKET_TIMEOUT, 0));
    int sockSendBuffer = intOption(conf, Constants.SOCKET_SEND_BUFFER,
        Constants.DEFAULT_SOCKET_SEND_BUFFER, 2048);
    int sockRecvBuffer = intOption(conf, Constants.SOCKET_RECV_BUFFER,
        Constants.DEFAULT_SOCKET_RECV_BUFFER, 2048);
    awsConf.setSocketBufferSizeHints(sockSendBuffer, sockRecvBuffer);
    String signerOverride = conf.getTrimmed(Constants.SIGNING_ALGORITHM, "");
    if (!signerOverride.isEmpty()) {
      awsConf.setSignerOverride(signerOverride);
    }
  }

  /**
   * Initializes AWS SDK proxy support if configured.
   *
   * @param conf    Hadoop configuration
   * @param awsConf AWS SDK configuration
   * @throws IllegalArgumentException if misconfigured
   */
  private static void initProxySupport(
      Configuration conf, ClientConfiguration awsConf) throws IllegalArgumentException {
    String proxyHost = conf.getTrimmed(Constants.PROXY_HOST, "");
    int proxyPort = conf.getInt(Constants.PROXY_PORT, -1);
    if (!proxyHost.isEmpty()) {
      awsConf.setProxyHost(proxyHost);
      if (proxyPort >= 0) {
        awsConf.setProxyPort(proxyPort);
      } else {
        if (conf.getBoolean(Constants.SECURE_CONNECTIONS, Constants.DEFAULT_SECURE_CONNECTIONS)) {
          awsConf.setProxyPort(443);
        } else {
          awsConf.setProxyPort(80);
        }
      }
      String proxyUsername = conf.getTrimmed(Constants.PROXY_USERNAME);
      String proxyPassword = conf.getTrimmed(Constants.PROXY_PASSWORD);
      Preconditions.checkArgument((proxyUsername == null) != (proxyPassword == null),
          "Proxy username or proxy password should not be used without the other.");
      awsConf.setProxyUsername(proxyUsername);
      awsConf.setProxyPassword(proxyPassword);
      awsConf.setProxyDomain(conf.getTrimmed(Constants.PROXY_DOMAIN));
      awsConf.setProxyWorkstation(conf.getTrimmed(Constants.PROXY_WORKSTATION));
    } else if (proxyPort >= 0) {
      throw new SafeIllegalArgumentException("Proxy port set without proxy host.");
    }
  }

  /**
   * Initializes the User-Agent header to send in HTTP requests to the S3
   * back-end.  We always include the Hadoop version number.  The user also
   * may set an optional custom prefix to put in front of the Hadoop version
   * number.  The AWS SDK interally appends its own information, which seems
   * to include the AWS SDK version, OS and JVM version.
   *
   * @param conf    Hadoop configuration
   * @param awsConf AWS SDK configuration
   */
  private static void initUserAgent(Configuration conf,
                                    ClientConfiguration awsConf) {
    String userAgent = String.format("Hadoop %s", VersionInfo.getVersion());
    String userAgentPrefix = conf.getTrimmed(Constants.USER_AGENT_PREFIX, "");
    if (!userAgentPrefix.isEmpty()) {
      userAgent = userAgentPrefix + ", " + userAgent;
    }
    awsConf.setUserAgentPrefix(userAgent);
  }

  /**
   * Creates an {@link AmazonS3Client} from the established configuration.
   *
   * @param conf Hadoop configuration
   * @return S3 client
   * @throws IllegalArgumentException if misconfigured
   */
  private static AmazonS3 createAmazonS3Client(AmazonS3 s3, Configuration conf)
      throws IllegalArgumentException {
    String endPoint = conf.getTrimmed(Constants.ENDPOINT, "");
    if (!endPoint.isEmpty()) {
      try {
        s3.setEndpoint(endPoint);
      } catch (IllegalArgumentException e) {
        String msg = "Incorrect endpoint: " + e.getMessage();
        throw new IllegalArgumentException(msg, e);
      }
    }
    enablePathStyleAccessIfRequired(s3, conf);
    return s3;
  }

  /**
   * Enables path-style access to S3 buckets if configured.  By default, the
   * behavior is to use virtual hosted-style access with URIs of the form
   * http://bucketname.s3.amazonaws.com.  Enabling path-style access and a
   * region-specific endpoint switches the behavior to use URIs of the form
   * http://s3-eu-west-1.amazonaws.com/bucketname.
   *
   * @param s3   S3 client
   * @param conf Hadoop configuration
   */
  private static void enablePathStyleAccessIfRequired(AmazonS3 s3,
                                                      Configuration conf) {
    final boolean pathStyleAccess = conf.getBoolean(Constants.PATH_STYLE_ACCESS, false);
    if (pathStyleAccess) {
      s3.setS3ClientOptions(S3ClientOptions.builder()
          .setPathStyleAccess(true)
          .build());
    }
  }

  /**
   * Get a integer option >= the minimum allowed value.
   * <p>
   * Taken from {@link Constants}.
   *
   * @param conf   configuration
   * @param key    key to look up
   * @param defVal default value
   * @param min    minimum value
   * @return the value
   * @throws IllegalArgumentException if the value is below the minimum
   */
  private static int intOption(Configuration conf, String key, int defVal, int min) {
    int value = conf.getInt(key, defVal);
    Preconditions.checkArgument(value >= min,
        "Configuration value is lower than the required minimum.",
        SafeArg.of("key", key),
        SafeArg.of("min", min),
        SafeArg.of("given", value));
    return value;
  }
}
