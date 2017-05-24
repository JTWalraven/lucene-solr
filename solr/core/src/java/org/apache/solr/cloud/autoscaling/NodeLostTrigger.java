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

package org.apache.solr.cloud.autoscaling;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.lucene.util.IOUtils;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.core.CoreContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trigger for the {@link AutoScaling.EventType#NODELOST} event
 */
public class NodeLostTrigger extends TriggerBase {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final String name;
  private final Map<String, Object> properties;
  private final CoreContainer container;
  private final List<TriggerAction> actions;
  private final AtomicReference<AutoScaling.TriggerListener> listenerRef;
  private final boolean enabled;
  private final int waitForSecond;
  private final AutoScaling.EventType eventType;

  private boolean isClosed = false;

  private Set<String> lastLiveNodes;

  private Map<String, Long> nodeNameVsTimeRemoved = new TreeMap<>();

  public NodeLostTrigger(String name, Map<String, Object> properties,
                         CoreContainer container) {
    super(container.getZkController().getZkClient());
    this.name = name;
    this.properties = properties;
    this.container = container;
    this.listenerRef = new AtomicReference<>();
    List<Map<String, String>> o = (List<Map<String, String>>) properties.get("actions");
    if (o != null && !o.isEmpty()) {
      actions = new ArrayList<>(3);
      for (Map<String, String> map : o) {
        TriggerAction action = container.getResourceLoader().newInstance(map.get("class"), TriggerAction.class);
        actions.add(action);
      }
    } else {
      actions = Collections.emptyList();
    }
    lastLiveNodes = new TreeSet<>(container.getZkController().getZkStateReader().getClusterState().getLiveNodes());
    log.debug("Initial livenodes: {}", lastLiveNodes);
    this.enabled = (boolean) properties.getOrDefault("enabled", true);
    this.waitForSecond = ((Long) properties.getOrDefault("waitFor", -1L)).intValue();
    this.eventType = AutoScaling.EventType.valueOf(properties.get("event").toString().toUpperCase(Locale.ROOT));
  }

  @Override
  public void setListener(AutoScaling.TriggerListener listener) {
    listenerRef.set(listener);
  }

  @Override
  public AutoScaling.TriggerListener getListener() {
    return listenerRef.get();
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public AutoScaling.EventType getEventType() {
    return eventType;
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public int getWaitForSecond() {
    return waitForSecond;
  }

  @Override
  public Map<String, Object> getProperties() {
    return properties;
  }

  @Override
  public List<TriggerAction> getActions() {
    return actions;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof NodeLostTrigger) {
      NodeLostTrigger that = (NodeLostTrigger) obj;
      return this.name.equals(that.name)
          && this.properties.equals(that.properties);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, properties);
  }

  @Override
  public void close() throws IOException {
    synchronized (this) {
      isClosed = true;
      IOUtils.closeWhileHandlingException(actions);
    }
  }

  @Override
  public void restoreState(AutoScaling.Trigger old) {
    assert old.isClosed();
    if (old instanceof NodeLostTrigger) {
      NodeLostTrigger that = (NodeLostTrigger) old;
      assert this.name.equals(that.name);
      this.lastLiveNodes = new TreeSet<>(that.lastLiveNodes);
      this.nodeNameVsTimeRemoved = new TreeMap<>(that.nodeNameVsTimeRemoved);
    } else  {
      throw new SolrException(SolrException.ErrorCode.INVALID_STATE,
          "Unable to restore state from an unknown type of trigger");
    }
  }

  @Override
  protected Map<String, Object> getState() {
    Map<String,Object> state = new HashMap<>();
    state.put("lastLiveNodes", lastLiveNodes);
    state.put("nodeNameVsTimeRemoved", nodeNameVsTimeRemoved);
    return state;
  }

  @Override
  protected void setState(Map<String, Object> state) {
    this.lastLiveNodes.clear();
    this.nodeNameVsTimeRemoved.clear();
    Collection<String> lastLiveNodes = (Collection<String>)state.get("lastLiveNodes");
    if (lastLiveNodes != null) {
      this.lastLiveNodes.addAll(lastLiveNodes);
    }
    Map<String,Long> nodeNameVsTimeRemoved = (Map<String,Long>)state.get("nodeNameVsTimeRemoved");
    if (nodeNameVsTimeRemoved != null) {
      this.nodeNameVsTimeRemoved.putAll(nodeNameVsTimeRemoved);
    }
  }

  @Override
  public void run() {
    try {
      synchronized (this) {
        if (isClosed) {
          log.warn("NodeLostTrigger ran but was already closed");
          throw new RuntimeException("Trigger has been closed");
        }
      }
      log.debug("Running NodeLostTrigger: {}", name);

      ZkStateReader reader = container.getZkController().getZkStateReader();
      Set<String> newLiveNodes = reader.getClusterState().getLiveNodes();
      log.debug("Found livenodes: {}", newLiveNodes);

      // have any nodes that we were tracking been added to the cluster?
      // if so, remove them from the tracking map
      Set<String> trackingKeySet = nodeNameVsTimeRemoved.keySet();
      trackingKeySet.removeAll(newLiveNodes);

      // have any nodes been removed?
      Set<String> copyOfLastLiveNodes = new HashSet<>(lastLiveNodes);
      copyOfLastLiveNodes.removeAll(newLiveNodes);
      copyOfLastLiveNodes.forEach(n -> {
        log.debug("Tracking lost node: {}", n);
        nodeNameVsTimeRemoved.put(n, System.currentTimeMillis());
      });

      // has enough time expired to trigger events for a node?
      for (Map.Entry<String, Long> entry : nodeNameVsTimeRemoved.entrySet()) {
        String nodeName = entry.getKey();
        Long timeRemoved = entry.getValue();
        if (TimeUnit.SECONDS.convert(System.currentTimeMillis() - timeRemoved, TimeUnit.MILLISECONDS) >= getWaitForSecond()) {
          // fire!
          AutoScaling.TriggerListener listener = listenerRef.get();
          if (listener != null) {
            log.debug("NodeLostTrigger firing registered listener");
            if (listener.triggerFired(new NodeLostEvent(getEventType(), getName(), timeRemoved, nodeName)))  {
              trackingKeySet.remove(nodeName);
            }
          } else  {
            trackingKeySet.remove(nodeName);
          }
        }
      }

      lastLiveNodes = new TreeSet<>(newLiveNodes);
    } catch (RuntimeException e) {
      log.error("Unexpected exception in NodeLostTrigger", e);
    }
  }

  @Override
  public boolean isClosed() {
    synchronized (this) {
      return isClosed;
    }
  }

  public static class NodeLostEvent extends TriggerEvent {

    public NodeLostEvent(AutoScaling.EventType eventType, String source, long nodeLostTime, String nodeRemoved) {
      super(eventType, source, nodeLostTime, Collections.singletonMap(NODE_NAME, nodeRemoved));
    }
  }
}
