# Log4j2 Elasticsearch HTTP appender

<a href="https://gitlab.com/synhershko/log4j2-elasticsearch-http/commits/master"><img alt="pipeline status" src="https://gitlab.com/synhershko/log4j2-elasticsearch-http/badges/master/pipeline.svg" /></a> <a href="https://snyk.io/test/github/BigDataBoutique/log4j2-elasticsearch-http"><img src="https://snyk.io/test/github/BigDataBoutique/log4j2-elasticsearch-http/badge.svg" alt="Known Vulnerabilities" data-canonical-src="https://snyk.io/test/github/BigDataBoutique/log4j2-elasticsearch-http" style="max-width:100%;"/></a>

This NoSql Log4j2 appender logs messages to Elasticsearch via HTTP, asynchronously without damaging your application performance, and in bulks. 

It uses the Java JDK HTTP client to connect to the HTTP node bulk endpoint of a running Elasticsearch cluster.

This is a fork of https://github.com/jprante/log4j2-elasticsearch-http which have undergone a rewrite and hardening to ensure application performance is never affected.  

In the `log4j2.xml` configuration file, you can specify the following parameters:

`url` the Elasticsearch HTTP node (default: `http://localhost:9200/`)

`index` the index name of the Elasticsearch cluster to write log messages to (default: `log4j2`)
The index name may be a date format string like 'log4j2-'yyyyMMdd

`type` the type of the Elasticsearch index to write log messages to (default: `doc`; please note Elasticsearch docs are deprecated so you probably shouldn't use this setting)

`maxActionsPerBulkRequest` maximum number of indexing action in a single bulk request (default: `1000`)

`flushRateSeconds` how often to execute the asynchronous flush to Elasticsearch

## Log4j2.xml example

    <configuration status="OFF">
        <appenders>
            <NoSql name="elasticsearchAppender">
                <Elasticsearch url="http://localhost:9200/" index="log4j2"/>
            </NoSql>
            <NoSql name="elasticsearchTimeAppender">
                <Elasticsearch url="http://localhost:9200/" index="'log4j2-'yyyyMMdd"/>
            </NoSql>
        </appenders>
        <loggers>
            <logger name="test">
                <appender-ref ref="elasticsearchAppender"/>
            </logger>
            <logger name="time">
                <appender-ref ref="elasticsearchTimeAppender"/>
            </logger>
            <root level="info">
                <appender-ref ref="elasticsearchAppender"/>
            </root>
        </loggers>
    </configuration>

## Java code example

    Logger logger = LogManager.getLogger("test");
    logger.info("Hello World");

## Indexed log message example

    curl 'localhost:9200/log4j2/_search?pretty'
    {
      "took" : 1,
      "timed_out" : false,
      "_shards" : {
        "total" : 5,
        "successful" : 5,
        "failed" : 0
      },
      "hits" : {
        "total" : 1,
        "max_score" : 1.0,
        "hits" : [ {
          "_index" : "log4j2",
          "_type" : "doc",
          "_id" : "dzvP2kbtS8Sr0uEZojMfKg",
          "_score" : 1.0,
          "_source":{"date":"2014-07-18T06:17:38.896Z","contextStack":[],"level":"INFO",
          "marker":null,"thrown":null,"source":{"fileName":"ElasticsearchAppenderTest.java",
          "methodName":"testLog","className":"ElasticsearchAppenderTest","lineNumber":11},
          "loggerName":"test","message":"Hello World","millis":1405664258896,
          "contextMap":{},"threadName":"main"}
        } ]
      }
    }    


# Versions

| Log4j2 Elasticsearch HTTP appender   | Release date |
| -------------------------------------| -------------|
| 2.5.1                                | Feb 20, 2018 |


# Installation

Maven coordinates:
    
        <dependencies>
            <dependency>
                <groupId>com.bigdataboutique.logging.log4j2</groupId>
                <artifactId>log4j2-elasticsearch-http</artifactId>
                <version>2.5.1</version>
            </dependency>
        </dependencies>

Gradle coordinates:

    compile group: 'com.bigdataboutique.logging.log4j2', name: 'log4j2-elasticsearch-http', version: '2.5.1'

## Issues

All feedback is welcome! If you find issues, please post them at [Github](https://github.com/BigDataBoutique/log4j2-elasticsearch-http/issues)

# License

Log4j2 Elasticsearch Appender

Copyright (C) 2014-2018 Jörg Prante and Itamar Syn-Hershko

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

