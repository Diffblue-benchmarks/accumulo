/*
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
package org.apache.accumulo.server.conf;

import java.util.Map;
import java.util.function.Predicate;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.clientImpl.Namespace;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.ConfigurationObserver;
import org.apache.accumulo.core.conf.ObservableConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.fate.zookeeper.ZooCache;
import org.apache.accumulo.fate.zookeeper.ZooCacheFactory;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.server.conf.ZooCachePropertyAccessor.PropCacheKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NamespaceConfiguration extends ObservableConfiguration {
  private static final Logger log = LoggerFactory.getLogger(NamespaceConfiguration.class);

  private static final Map<PropCacheKey,ZooCache> propCaches = new java.util.HashMap<>();

  private final AccumuloConfiguration parent;
  private ZooCachePropertyAccessor propCacheAccessor = null;
  protected Namespace.ID namespaceId = null;
  protected ServerContext context;
  private ZooCacheFactory zcf = new ZooCacheFactory();
  private final String path;

  public NamespaceConfiguration(Namespace.ID namespaceId, ServerContext context,
      AccumuloConfiguration parent) {
    this.context = context;
    this.parent = parent;
    this.namespaceId = namespaceId;
    this.path = context.getZooKeeperRoot() + Constants.ZNAMESPACES + "/" + namespaceId
        + Constants.ZNAMESPACE_CONF;
  }

  /**
   * Gets the parent configuration of this configuration.
   *
   * @return parent configuration
   */
  public AccumuloConfiguration getParentConfiguration() {
    return parent;
  }

  void setZooCacheFactory(ZooCacheFactory zcf) {
    this.zcf = zcf;
  }

  private synchronized ZooCachePropertyAccessor getPropCacheAccessor() {
    if (propCacheAccessor == null) {
      synchronized (propCaches) {
        PropCacheKey key = new PropCacheKey(context.getInstanceID(), namespaceId.canonicalID());
        ZooCache propCache = propCaches.get(key);
        if (propCache == null) {
          propCache = zcf.getZooCache(context.getZooKeepers(),
              context.getZooKeepersSessionTimeOut(), new NamespaceConfWatcher(context));
          propCaches.put(key, propCache);
        }
        propCacheAccessor = new ZooCachePropertyAccessor(propCache);
      }
    }
    return propCacheAccessor;
  }

  private String getPath() {
    return path;
  }

  @Override
  public String get(Property property) {
    String key = property.getKey();
    AccumuloConfiguration getParent;
    if (!(namespaceId.equals(Namespace.ID.ACCUMULO) && isIteratorOrConstraint(key))) {
      getParent = parent;
    } else {
      // ignore iterators from parent if system namespace
      getParent = null;
    }
    return getPropCacheAccessor().get(property, getPath(), getParent);
  }

  @Override
  public void getProperties(Map<String,String> props, Predicate<String> filter) {
    Predicate<String> parentFilter = filter;
    // exclude system iterators/constraints from the system namespace
    // so they don't affect the metadata or root tables.
    if (getNamespaceId().equals(Namespace.ID.ACCUMULO))
      parentFilter = key -> isIteratorOrConstraint(key) ? false : filter.test(key);

    getPropCacheAccessor().getProperties(props, getPath(), filter, parent, parentFilter);
  }

  protected Namespace.ID getNamespaceId() {
    return namespaceId;
  }

  @Override
  public void addObserver(ConfigurationObserver co) {
    if (namespaceId == null) {
      String err = "Attempt to add observer for non-namespace configuration";
      log.error(err);
      throw new RuntimeException(err);
    }
    iterator();
    super.addObserver(co);
  }

  @Override
  public void removeObserver(ConfigurationObserver co) {
    if (namespaceId == null) {
      String err = "Attempt to remove observer for non-namespace configuration";
      log.error(err);
      throw new RuntimeException(err);
    }
    super.removeObserver(co);
  }

  static boolean isIteratorOrConstraint(String key) {
    return key.startsWith(Property.TABLE_ITERATOR_PREFIX.getKey())
        || key.startsWith(Property.TABLE_CONSTRAINT_PREFIX.getKey());
  }

  @Override
  public synchronized void invalidateCache() {
    if (propCacheAccessor != null) {
      propCacheAccessor.invalidateCache();
    }
    // Else, if the accessor is null, we could lock and double-check
    // to see if it happened to be created so we could invalidate its cache
    // but I don't see much benefit coming from that extra check.
  }

  @Override
  public long getUpdateCount() {
    return parent.getUpdateCount() + getPropCacheAccessor().getZooCache().getUpdateCount();
  }
}
