/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package fr.pilato.talk.elasticsearch.tests.integration;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.main.MainResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

import java.io.IOException;
import java.util.Properties;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

public class ElasticsearchIT {

    private static final Logger logger = LogManager.getLogger(ElasticsearchIT.class);
    private static RestHighLevelClient client;
    private static final String INDEX = "scenario1";
    private static ElasticsearchContainer container;

    @BeforeClass
    public static void startElasticsearchRestClient() throws IOException {
        int testClusterPort = Integer.parseInt(System.getProperty("tests.cluster.port", "9200"));
        String testClusterHost = System.getProperty("tests.cluster.host", "localhost");
        String testClusterScheme = System.getProperty("tests.cluster.scheme", "http");
        String testClusterUser = System.getProperty("tests.cluster.user", "elastic");
        String testClusterPass = System.getProperty("tests.cluster.pass", "changeme");

        logger.info("Starting a client on {}://{}:{}", testClusterScheme, testClusterHost, testClusterPort);

        // We start a client
        RestClientBuilder builder = getClientBuilder(new HttpHost(testClusterHost, testClusterPort, testClusterScheme),
                testClusterUser, testClusterPass);

        // We check that the client is running
        try (RestHighLevelClient elasticsearchClientTemporary = new RestHighLevelClient(builder)) {
            elasticsearchClientTemporary.info(RequestOptions.DEFAULT);
            logger.info("A node is already running. No need to start a Docker instance.");
        } catch (IOException e) {
            logger.info("No node running. We need to start a Docker instance.");
            Properties properties = new Properties();
            properties.load(ElasticsearchIT.class.getClassLoader().getResourceAsStream("elasticsearch.version.properties"));
            container = new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:" + properties.getProperty("version"));
            container.start();
            logger.info("Docker instance started.");
            testClusterHost = container.getContainerIpAddress();
            testClusterPort = container.getFirstMappedPort();
        }

        // We build the elasticsearch High Level Client based on the parameters
        builder = getClientBuilder(new HttpHost(testClusterHost, testClusterPort, testClusterScheme),
                testClusterUser, testClusterPass);
        client = new RestHighLevelClient(builder);

        // We make sure the cluster is running
        MainResponse info = client.info(RequestOptions.DEFAULT);
        logger.info("Client is running against an elasticsearch cluster {}.", info.getVersion().toString());
    }

    @AfterClass
    public static void stopElasticsearchRestClient() throws IOException {
        if (client != null) {
            logger.info("Closing elasticsearch client.");
            client.close();
        }
        if (container != null) {
            logger.info("Stopping Docker instance.");
            container.close();
        }
    }

    private static RestClientBuilder getClientBuilder(HttpHost host, String username, String password) {
        final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(username, password));

        return RestClient.builder(host)
                .setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
    }

    @Test
    public void testAScenario() throws IOException {
        // We remove any existing index
        try {
            logger.info("-> Removing index {}.", INDEX);
            client.indices().delete(new DeleteIndexRequest(INDEX), RequestOptions.DEFAULT);
        } catch (ElasticsearchStatusException e) {
            assertThat(e.status().getStatus(), is(404));
        }

        // We create a new index
        logger.info("-> Creating index {}.", INDEX);
        client.indices().create(new CreateIndexRequest(INDEX), RequestOptions.DEFAULT);

        // We index some documents
        logger.info("-> Indexing one document in {}.", INDEX);
        IndexResponse ir = client.index(new IndexRequest(INDEX).source(
                jsonBuilder()
                        .startObject()
                        .field("foo", "bar")
                        .endObject()
        ).setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE), RequestOptions.DEFAULT);
        logger.info("-> Document indexed with _id {}.", ir.getId());

        // We search
        SearchResponse sr = client.search(new SearchRequest(INDEX), RequestOptions.DEFAULT);
        logger.info("{}", sr);
        assertThat(sr.getHits().getTotalHits().value, is(1L));
    }
}
