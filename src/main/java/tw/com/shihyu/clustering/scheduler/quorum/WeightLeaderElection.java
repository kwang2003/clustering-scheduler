package tw.com.shihyu.clustering.scheduler.quorum;

import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.utils.CloseableUtils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.NoNodeException;

import com.google.common.util.concurrent.AtomicDouble;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * A simple load balance {@link LeaderElection} implementation
 * <p>
 * 
 * Recipe: the higher weight the better, the highest weight is the leader
 * 
 * @author Matt S.Y. Ho
 *
 */
@Slf4j
public class WeightLeaderElection extends BooleanLeaderElection
    implements PathChildrenCacheListener, ConnectionStateListener {

  private @Setter String connectString;
  private @Setter int baseSleepTimeMs = 1000;
  private @Setter int maxRetries = 29; // org.apache.curator.retry.ExponentialBackoffRetry.MAX_RETRIES_LIMIT
  private @Setter String rootPath = "/election";
  private @Setter @Getter String contenderId;
  private CuratorFramework client;
  private PathChildrenCache cache;
  private String contenderSequence;
  private @Setter String contenderPath = "/lb-";
  private AtomicBoolean leader = new AtomicBoolean();
  private AtomicDouble weight = new AtomicDouble();
  private ScheduledExecutorService executor;
  private @Setter int checkWeightDelay = 10;
  private @Setter TimeUnit checkWeightUnit = TimeUnit.SECONDS;

  @Override
  public void afterPropertiesSet() throws Exception {
    if (connectString == null || connectString.isEmpty()) {
      throw new IllegalArgumentException("'connectString' is required");
    }
    if (rootPath == null || rootPath.isEmpty()) {
      throw new IllegalArgumentException("'rootPath' is required");
    } else if (!rootPath.startsWith("/")) {
      rootPath = "/" + rootPath;
    }
    if (contenderId == null || contenderId.isEmpty()) {
      contenderId = InetAddress.getLocalHost() + "/" + UUID.randomUUID();
      log.debug("Generating random UUID [{}] for 'contenderId'", contenderId);
    }

    setBooleanSupplier(leader::get);
    start();
  }

  private synchronized void start() throws Exception {
    client = CuratorFrameworkFactory.newClient(connectString,
        new ExponentialBackoffRetry(baseSleepTimeMs, maxRetries));
    client.start();
    try {
      client.getZookeeperClient().blockUntilConnectedOrTimedOut();
    } catch (InterruptedException e) {
      client.close();
      start();
    }
    client.getConnectionStateListenable().addListener(this);
    contenderSequence = client.create().creatingParentContainersIfNeeded().withProtection()
        .withMode(CreateMode.EPHEMERAL_SEQUENTIAL)
        .forPath(rootPath + contenderPath, contenderId.getBytes("UTF-8"));
    contenderSequence = contenderSequence.replaceFirst(rootPath + "/", "");
    log.debug("Contender node [{}] created", contenderSequence);

    cache = new PathChildrenCache(client, rootPath, true);
    cache.start();
    cache.getListenable().addListener(this);

    executor = Executors.newScheduledThreadPool(1);
    executor.scheduleWithFixedDelay(this::checkWeight, 0, checkWeightDelay, checkWeightUnit);
  }

  private void checkWeight() {
    // TODO: windows doesn't implement this method
    double current = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
    if (weight.get() != current) {
      try {
        client.setData().forPath(rootPath + "/" + contenderSequence,
            (contenderId + "#" + current).getBytes("UTF-8"));
        weight.set(current);
        checkLeadership();
      } catch (Exception e) {
        log.error("{}", e);
      }
    }
  }

  @Override
  public String toString() {
    return this.getClass().getSimpleName() + "{" + "contenderId='" + contenderId + '\''
        + ", isLeader=" + isLeader() + ", weight=" + weight.get() + '}';
  }

  @Override
  public Collection<Contender> getContenders() {
    try {
      Collection<Contender> contenders = new ArrayList<>();
      List<ChildWeight> children = getSortedChildren();
      ChildWeight bestWeight = children.get(0);
      contenders.add(new Contender(bestWeight.id, bestWeight.isAvailable()));
      children.stream().skip(1).forEach(child -> contenders.add(new Contender(child.id, false)));
      return contenders;
    } catch (Exception e) {
      throw new Error(e);
    }
  }

  @Override
  public void childEvent(CuratorFramework client, PathChildrenCacheEvent event) throws Exception {
    switch (event.getType()) {
      case CHILD_REMOVED:
        String removedData = new String(event.getData().getData(), Charset.forName("UTF-8"));
        String removedId = removedData.substring(0, removedData.lastIndexOf("#"));
        if (removedId.equals(contenderId)) {
          close();
          start();
        }
        break;
      case CHILD_UPDATED:
        checkLeadership();
        break;
      default:
        break;
    }
  }

  private void checkLeadership() throws Exception {
    ChildWeight bestWeight = getSortedChildren().get(0);
    if (bestWeight.isAvailable() && bestWeight.id.equals(contenderSequence)) {
      leader.compareAndSet(false, true);
    } else {
      leader.compareAndSet(true, false);
    }
  }

  private List<ChildWeight> getSortedChildren() throws Exception {
    return client.getChildren().forPath(rootPath).stream().map(child -> {
      try {
        String data = new String(client.getData().forPath(rootPath + "/" + child), "UTF-8");
        String weight = data.substring(data.lastIndexOf("#") + 1);
        return new ChildWeight(child, Double.parseDouble(weight));
      } catch (NoNodeException e) {
      } catch (Exception e) {
        log.error("{}", e);
      }
      return new ChildWeight(child, -1d);
    }).sorted().collect(toList());
  }

  @AllArgsConstructor
  private class ChildWeight implements Comparable<ChildWeight> {
    final String id;
    final double weight;

    boolean isAvailable() {
      return weight > 0;
    }

    @Override
    public int compareTo(ChildWeight other) {
      return Double.compare(other.weight, weight); // the higher weight the better
    }
  }

  @Override
  public void close() throws IOException {
    CloseableUtils.closeQuietly(cache);
    CloseableUtils.closeQuietly(client);
    executor.shutdownNow();
  }

  @Override
  public void relinquishLeadership() {
    if (isLeader()) {
      leader.set(false);
      try {
        close();
        start();
      } catch (Exception e) {
        throw new Error(e);
      }
    }
  }

  @Override
  public void stateChanged(CuratorFramework client, ConnectionState newState) {
    switch (newState) {
      case RECONNECTED:
        try {
          checkLeadership();
        } catch (Exception e) {
          log.error("{}", e);
        }
        break;
      case SUSPENDED:
      case LOST:
        leader.set(false);
        break;
      default:
        break;
    }
  }

}
