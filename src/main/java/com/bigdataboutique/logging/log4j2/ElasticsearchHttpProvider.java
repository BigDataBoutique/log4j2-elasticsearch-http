/**
 *    Copyright 2014-2018 Jörg Prante and Itamar Syn-Hershko
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
package com.bigdataboutique.logging.log4j2;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.nosql.appender.NoSqlProvider;
import org.apache.logging.log4j.status.StatusLogger;
import org.apache.logging.log4j.util.Strings;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Plugin(name = "Elasticsearch", category = "Core", printObject = true)
public class ElasticsearchHttpProvider implements NoSqlProvider<ElasticsearchHttpConnection> {

    private final ElasticsearchHttpClient client;

    private static final Logger LOGGER = StatusLogger.getLogger();

    private final String description;

    private ElasticsearchHttpProvider(final ElasticsearchHttpClient client, final String description) {
        this.client = client;
        this.description = "elasticsearch-http{ " + description + " }";
    }

    @Override
    public ElasticsearchHttpConnection getConnection() {
        return new ElasticsearchHttpConnection(client);
    }

    @Override
    public String toString() {
        return description;
    }

    /**
     * Factory method for creating an Elasticsearch provider within the plugin manager.
     *
     * @param url     The URL of a host in an Elasticsearch cluster to which log event documents will be written.
     * @param index   The index that Elasticsearch shall use for indexing
     * @param type    The type of the index Elasticsearch shall use for indexing
     * @param flushRateSeconds  How often to execute flushing the buffer to Elasticsearch, in seconds
     * @param maxActionsPerBulkRequest maximum number of actions per bulk request
     * @param logResponses true if responses should be logged
     * @return a new Elasticsearch provider
     */
    @PluginFactory
    public static ElasticsearchHttpProvider createNoSqlProvider(
            @PluginAttribute("url") String url,
            @PluginAttribute(value = "index", defaultString = "log4j") String index,
            @PluginAttribute(value = "type", defaultString = "doc") String type,
            @PluginAttribute(value = "tags") String tags,
            @PluginAttribute(value = "flushRateSeconds", defaultInt = 10) Integer flushRateSeconds,
            @PluginAttribute(value = "maxActionsPerBulkRequest", defaultInt = 1000) Integer maxActionsPerBulkRequest,
            @PluginAttribute(value = "logResponses", defaultBoolean = false) Boolean logResponses) {

        if (url == null || url.isEmpty()) {
            // TODO test why it's not working with StrSubtitor
            url = "http://localhost:9200/_bulk";
        }

        if (maxActionsPerBulkRequest < 1) {
            maxActionsPerBulkRequest = 1000;
        }

        if (flushRateSeconds < 1) {
            flushRateSeconds = 10;
        }

        // Parse the tags list and prepare an easy to use dictionary for it
        final Map<String, List<String>> applyTags = createTagsMap(tags);

        String description = "url=" + url + ",index=" + index + ",type=" + type;
        ElasticsearchHttpClient elasticsearchClient;
        LOGGER.info("Elasticsearch appender " + description + " defined");
        try {
            elasticsearchClient = new ElasticsearchHttpClient(url, index, type, applyTags,
                    maxActionsPerBulkRequest, flushRateSeconds, logResponses);
        } catch (MalformedURLException e) {
            LOGGER.error(e);
            return null;
        }
        return new ElasticsearchHttpProvider(elasticsearchClient, description);
    }

    public static Map<String, List<String>> createTagsMap(final String definitions) {
        if (Strings.isBlank(definitions)) {
            return Collections.emptyMap();
        }

        final Map<String, List<String>> applyTags = new HashMap<>();
        final String[] tagsArray = definitions.split(",");
        for (final String tag : tagsArray) {
            if (Strings.isNotBlank(tag)) {
                String[] kv = tag.split(":");
                final String key, value;
                if (kv.length > 1) {
                    key = kv[0];
                    value = kv[1];
                } else {
                    key = "tags";
                    value = kv[0];
                }

                if (applyTags.computeIfPresent(key, (k, v) -> { v.add(value); return v; }) == null) {
                    applyTags.putIfAbsent(key, new ArrayList<String>() {{
                        add(value);
                    }});
                }
            }
        }

        return applyTags;
    }
}
