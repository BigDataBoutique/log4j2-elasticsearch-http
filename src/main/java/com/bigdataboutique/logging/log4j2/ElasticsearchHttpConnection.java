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

import org.apache.logging.log4j.core.appender.AppenderLoggingException;
import org.apache.logging.log4j.core.appender.nosql.AbstractNoSqlConnection;
import org.apache.logging.log4j.core.appender.nosql.DefaultNoSqlObject;
import org.apache.logging.log4j.core.appender.nosql.NoSqlObject;

import java.util.Map;

public class ElasticsearchHttpConnection extends AbstractNoSqlConnection<Map<String, Object>, DefaultNoSqlObject> {

    private final ElasticsearchHttpClient client;

    public ElasticsearchHttpConnection(final ElasticsearchHttpClient client) {
        this.client = client;
    }

    @Override
    public DefaultNoSqlObject createObject() {
        return new DefaultNoSqlObject();
    }

    @Override
    public DefaultNoSqlObject[] createList(final int length) {
        return new DefaultNoSqlObject[length];
    }

    @Override
    public void insertObject(final NoSqlObject<Map<String, Object>> object) {
        try {
            client.index(object.unwrap());
        } catch (Exception e) {
            throw new AppenderLoggingException("failed to write log event to Elasticsearch HTTP: " + e.getMessage(), e);
        }
    }

    @Override
    protected void closeImpl() {
        client.close();
    }
}
