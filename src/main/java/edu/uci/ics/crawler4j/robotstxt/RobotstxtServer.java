/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.uci.ics.crawler4j.robotstxt;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.http.HttpStatus;

import edu.uci.ics.crawler4j.crawler.Page;
import edu.uci.ics.crawler4j.fetcher.PageFetchResult;
import edu.uci.ics.crawler4j.fetcher.PageFetcher;
import edu.uci.ics.crawler4j.url.WebURL;
import edu.uci.ics.crawler4j.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Yasser Ganjisaffar <lastname at gmail dot com>
 */
public class RobotstxtServer {

  private static final Logger logger = LoggerFactory.getLogger(RobotstxtServer.class);

  protected RobotstxtConfig config;

  protected final Map<String, HostDirectives []> host2directivesCache = new HashMap<>();

  protected PageFetcher pageFetcher;

  public RobotstxtServer(RobotstxtConfig config, PageFetcher pageFetcher) {
    this.config = config;
    this.pageFetcher = pageFetcher;
  }

  private static String getHost(URL url) {
    return url.getHost().toLowerCase();
  }

  public boolean allows(WebURL webURL) {
    if (!config.isEnabled()) {
      return true;
    }
    try {
      URL url = new URL(webURL.getURL());
      String host = getHost(url);
      String path = url.getPath();

      HostDirectives directives [] = host2directivesCache.get(host);

      if (directives != null && directives[0].needsRefetch()) {
        synchronized (host2directivesCache) {
          host2directivesCache.remove(host);
          directives = null;
        }
      }

      if (directives == null) {
        directives = fetchDirectives(url);
      }
      return directives[1].allows(path) || directives[0].allows(path);
    } catch (MalformedURLException e) {
      e.printStackTrace();
    }
    return true;
  }

  private HostDirectives [] fetchDirectives(URL url) {
    WebURL robotsTxtUrl = new WebURL();
    String host = getHost(url);
    String port = (url.getPort() == url.getDefaultPort() || url.getPort() == -1) ? "" : ":" + url.getPort();
    robotsTxtUrl.setURL("http://" + host + port + "/robots.txt");
    HostDirectives [] directives = null;
    PageFetchResult fetchResult = null;
    try {
      fetchResult = pageFetcher.fetchHeader(robotsTxtUrl);
      if (fetchResult.getStatusCode() == HttpStatus.SC_OK) {
        Page page = new Page(robotsTxtUrl);
        fetchResult.fetchContent(page, 16384);
        if (Util.hasPlainTextContent(page.getContentType())) {
          try {
            String content = "";
            if (page.getContentData() != null) {
              if (page.getContentCharset() == null && page.getContentData() != null) {
                content = new String(page.getContentData());
              } else {
                content = new String(page.getContentData(), page.getContentCharset());
              }
              directives = RobotstxtParser.parse(content, config.getUserAgentName());
            } else {
              logger.info("No data received for robots.txt retrieved from URL: {}", robotsTxtUrl.getURL());
            }
          } catch (Exception e) {
            logger.error("Error occurred while fetching (robots) url: " + robotsTxtUrl.getURL(), e);
          }
        }
      }
    } finally {
      if (fetchResult != null) {
        fetchResult.discardContentIfNotConsumed();
      }
    }
    if (directives == null) {
      // We still need to have this object to keep track of the time we
      // fetched it
      HostDirectives result [] =  {new HostDirectives(), new HostDirectives()};
      directives = result;
    }
    synchronized (host2directivesCache) {
      if (host2directivesCache.size() == config.getCacheSize()) {
        String minHost = null;
        long minAccessTime = Long.MAX_VALUE;
        for (Entry<String, HostDirectives []> entry : host2directivesCache.entrySet()) {
          long entryAccessTime = Math.max(entry.getValue()[0].getLastAccessTime(), entry.getValue()[1].getLastAccessTime());
          if (entryAccessTime < minAccessTime) {
            minAccessTime = entryAccessTime;
            minHost = entry.getKey();
          }
        }
        host2directivesCache.remove(minHost);
      }
      host2directivesCache.put(host, directives);
    }
    return directives;
  }
}