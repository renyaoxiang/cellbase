/*
 * Copyright 2015 OpenCB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opencb.cellbase.client.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.apache.commons.lang3.StringUtils;
import org.opencb.cellbase.client.config.ClientConfiguration;
import org.opencb.commons.datastore.core.Query;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.datastore.core.QueryResponse;
import org.opencb.commons.datastore.core.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Created by imedina on 12/05/16.
 */
public class ParentRestClient<T> {

    protected String species;
    protected Client client;

    protected String category;
    protected String subcategory;

    protected Class<T> clazz;

    protected ClientConfiguration configuration;

    protected static ObjectMapper jsonObjectMapper;
    protected static Logger logger;

    public static final int LIMIT = 1000;
    public static final int REST_CALL_BATCH_SIZE = 200;
    public static final int DEFAULT_NUM_THREADS = 4;


    @Deprecated
    public ParentRestClient(ClientConfiguration configuration) {
        this(configuration.getDefaultSpecies(), configuration);
    }

    public ParentRestClient(String species, ClientConfiguration configuration) {
        this.species = species;
        this.configuration = configuration;

        this.client = ClientBuilder.newClient();
        jsonObjectMapper = new ObjectMapper();

        logger = LoggerFactory.getLogger(this.getClass().toString());
    }


    public QueryResponse<Long> count(Query query) throws IOException {
        return execute("count", query, new QueryOptions(), Long.class);
    }

    public QueryResponse<T> first() throws IOException {
        return execute("first", new Query(), new QueryOptions(), clazz);
    }

    public QueryResponse<T> get(List<String> id, QueryOptions queryOptions) throws IOException {
        return execute(id, "info", queryOptions, clazz);
    }


    protected <U> QueryResponse<U> execute(String action, Query query, QueryOptions queryOptions, Class<U> clazz) throws IOException {
        if (query != null && queryOptions != null) {
            queryOptions.putAll(query);
        }
        return execute("", action, queryOptions, clazz);
    }

    protected <U> QueryResponse<U> execute(String ids, String resource, QueryOptions queryOptions, Class<U> clazz) throws IOException {
        return execute(Arrays.asList(ids.split(",")), resource, queryOptions, clazz);
    }

    protected <U> QueryResponse<U> execute(List<String> idList, String resource, QueryOptions options, Class<U> clazz) throws IOException {

        if (idList == null || idList.isEmpty()) {
            return new QueryResponse<>();
        }

        // If the list contain less than REST_CALL_BATCH_SIZE variants then we can make a normal REST call.
        if (idList.size() <= REST_CALL_BATCH_SIZE) {
            return fetchData(idList, resource, options, clazz);
        }

        // But if there are more than REST_CALL_BATCH_SIZE variants then we launch several threads to increase performance.
        int numThreads = (options != null)
                ? options.getInt("numThreads", DEFAULT_NUM_THREADS)
                : DEFAULT_NUM_THREADS;

        ExecutorService executorService = Executors.newFixedThreadPool(numThreads);
        List<Future<QueryResponse<U>>> futureList = new ArrayList<>((idList.size() / REST_CALL_BATCH_SIZE) + 1);
        for (int i = 0; i < idList.size(); i += REST_CALL_BATCH_SIZE) {
            final int from = i;
            final int to = (from + REST_CALL_BATCH_SIZE > idList.size())
                    ? idList.size()
                    : from + REST_CALL_BATCH_SIZE;
            futureList.add(executorService.submit(() ->
                    fetchData(idList.subList(from, to), resource, options, clazz)
            ));
        }

        List<QueryResult<U>> queryResults = new ArrayList<>(idList.size());
        for (Future<QueryResponse<U>> responseFuture : futureList) {
            try {
                while (!responseFuture.isDone()) {
                    Thread.sleep(5);
                }
                queryResults.addAll(responseFuture.get().getResponse());
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }

        QueryResponse<U> finalResponse = new QueryResponse<>();
        finalResponse.setResponse(queryResults);
        executorService.shutdown();

        return finalResponse;
    }

    private <U> QueryResponse<U> fetchData(List<String> idList, String resource, QueryOptions options, Class<U> clazz) throws IOException {

        if (options == null) {
            options = new QueryOptions();
        }
        options.putIfAbsent("limit", LIMIT);

        String ids = "";
        if (idList != null && !idList.isEmpty()) {
            ids = StringUtils.join(idList, ',');
        }

        Map<Integer, Integer> idMap = new HashMap<>();
        List<String> prevIdList = idList;
        List<String> newIdsList = null;
        boolean call = true;
        int skip = 0;
        QueryResponse<U> queryResponse = null;
        QueryResponse<U> finalQueryResponse = null;
        while (call) {
            queryResponse = restCall(configuration.getRest().getHosts(), configuration.getVersion(), ids, resource, options, clazz);

            // First iteration we set the response object, no merge needed
            if (finalQueryResponse == null) {
                finalQueryResponse = queryResponse;
            } else {    // merge query responses
                if (newIdsList != null && newIdsList.size() > 0) {
                    for (int i = 0; i < newIdsList.size(); i++) {
                        finalQueryResponse.getResponse().get(idMap.get(i)).getResult()
                                .addAll(queryResponse.getResponse().get(i).getResult());
                    }
                }
            }

            // check if we need to call again
            if (newIdsList != null) {
                prevIdList = newIdsList;
            }
            newIdsList = new ArrayList<>();
            idMap = new HashMap<>();
            for (int i = 0; i < queryResponse.getResponse().size(); i++) {
                if (queryResponse.getResponse().get(i).getNumResults() == LIMIT) {
                    idMap.put(newIdsList.size(), i);
                    newIdsList.add(prevIdList.get(i));
                }
            }

            if (newIdsList.isEmpty()) {
                // this breaks the while condition
                call = false;
            } else {
                ids = StringUtils.join(newIdsList, ',');
                skip += LIMIT;
                options.put("skip", skip);
            }
        }

        logger.debug("queryResponse = " + queryResponse);
        return finalQueryResponse;
    }

    private <U> QueryResponse<U> restCall(List<String> hosts, String version, String ids, String resource, QueryOptions queryOptions,
                                          Class<U> clazz) throws IOException {

        WebTarget path = client
                .target(hosts.get(0))
                .path("webservices/rest/" + version)
                .path(species)
                .path(category)
                .path(subcategory);

        WebTarget callUrl = path;
        if (ids != null && !ids.isEmpty()) {
            callUrl = path.path(ids);
        }

        // Add the last URL part, the 'action' or 'resource'
        callUrl = callUrl.path(resource);

        if (queryOptions != null) {
            for (String s : queryOptions.keySet()) {
                callUrl = callUrl.queryParam(s, queryOptions.get(s));
            }
        }

        logger.debug("Calling to REST URL: {}", callUrl.getUri().toURL());
        String jsonString = callUrl.request().get(String.class);
        return parseResult(jsonString, clazz);
    }

    private static <U> QueryResponse<U> parseResult(String json, Class<U> clazz) throws IOException {
        ObjectReader reader = jsonObjectMapper
                .readerFor(jsonObjectMapper.getTypeFactory().constructParametrizedType(QueryResponse.class, QueryResult.class, clazz));
        return reader.readValue(json);
    }

}
