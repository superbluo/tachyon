package tachyon;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListener;
import org.apache.curator.framework.recipes.leader.Participant;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.log4j.Logger;

/**
 * Masters use this client to elect a leader.
 */
public class LeaderSelectorClient implements Closeable, LeaderSelectorListener {
  private final Logger LOG = Logger.getLogger(Constants.LOGGER_TYPE);

  private final String ZOOKEEPER_ADDRESS;
  private final String ELECTION_PATH;
  private final String LEADER_FOLDER;
  private final String NAME;
  private final LeaderSelector LEADER_SELECTOR;

  private AtomicBoolean mIsLeader = new AtomicBoolean(false);
  private volatile Thread mCurrentMasterThread = null;

  public LeaderSelectorClient(String zookeeperAddress, String electionPath, String leaderPath,
      String name) {
    ZOOKEEPER_ADDRESS = zookeeperAddress;
    ELECTION_PATH = electionPath;
    if (leaderPath.endsWith("/")) {
      LEADER_FOLDER = leaderPath;
    } else {
      LEADER_FOLDER = leaderPath + "/";
    }
    NAME = name;

    // create a leader selector using the given path for management
    // all participants in a given leader selection must use the same path
    // ExampleClient here is also a LeaderSelectorListener but this isn't required
    CuratorFramework client = CuratorFrameworkFactory.newClient(
        ZOOKEEPER_ADDRESS, new ExponentialBackoffRetry(1000, 3));
    client.start();
    LEADER_SELECTOR = new LeaderSelector(client, ELECTION_PATH, this);
    LEADER_SELECTOR.setId(name);

    // for most cases you will want your instance to requeue when it relinquishes leadership
    LEADER_SELECTOR.autoRequeue();
  }

  public void start() throws IOException { 
    LEADER_SELECTOR.start();
  }

  public boolean isLeader() {
    return mIsLeader.get();
  }

  public String getName() {
    return NAME;
  }

  public List<String> getParticipants() {
    try {
      List<Participant> participants = 
          new ArrayList<Participant>(LEADER_SELECTOR.getParticipants());
      List<String> results = new ArrayList<String>();
      for (Participant part : participants) {
        results.add(part.getId());
      }
      return results;
    } catch (Exception e) {
      LOG.error(e.getMessage(), e);
      return null;
    }
  }

  @Override
  public void close() throws IOException {
    if (mCurrentMasterThread != null) {
      mCurrentMasterThread.interrupt();
    }

    LEADER_SELECTOR.close();
  }

  @Override
  public void takeLeadership(CuratorFramework client) throws Exception {
    mIsLeader.set(true);
    if (client.checkExists().forPath(LEADER_FOLDER + NAME) != null) {
      client.delete().forPath(LEADER_FOLDER + NAME);
    }
    client.create().creatingParentsIfNeeded().forPath(LEADER_FOLDER + NAME);
    LOG.info(NAME + " is now the leader.");
    try {
      while (true) {
        Thread.sleep(TimeUnit.SECONDS.toMillis(5));
      }
    } catch (InterruptedException e) {
      LOG.error(NAME + " was interrupted.", e);
      Thread.currentThread().interrupt();
    } finally {
      mCurrentMasterThread = null;
      LOG.warn(NAME + " relinquishing leadership.");
    }
    LOG.info("The current leader is " + LEADER_SELECTOR.getLeader().getId());
    LOG.info("All partitations: " + LEADER_SELECTOR.getParticipants());
    client.delete().forPath(LEADER_FOLDER + NAME);
  }

  @Override
  public void stateChanged(CuratorFramework client, ConnectionState newState) {
    mIsLeader.set(false);

    if ((newState == ConnectionState.LOST) || (newState == ConnectionState.SUSPENDED)) {
      if (mCurrentMasterThread != null) {
        mCurrentMasterThread.interrupt();
      }
    } else {
      try {
        LOG.info("The current leader is " + LEADER_SELECTOR.getLeader().getId());
      } catch (Exception e) {
        LOG.error(e.getMessage(), e);
      }
    }
  }

  /**
   * Set the current master thread.
   * @param currentMasterThread
   */
  public void setCurrentMasterThread(Thread currentMasterThread) {
    mCurrentMasterThread = currentMasterThread;
  }
}
