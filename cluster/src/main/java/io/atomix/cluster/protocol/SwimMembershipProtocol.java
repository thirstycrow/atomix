/*
 * Copyright 2018-present Open Networking Foundation
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
package io.atomix.cluster.protocol;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.atomix.cluster.BootstrapService;
import io.atomix.cluster.Member;
import io.atomix.cluster.MemberId;
import io.atomix.cluster.Node;
import io.atomix.cluster.discovery.NodeDiscoveryEvent;
import io.atomix.cluster.discovery.NodeDiscoveryEventListener;
import io.atomix.cluster.discovery.NodeDiscoveryService;
import io.atomix.cluster.impl.AddressSerializer;
import io.atomix.utils.Version;
import io.atomix.utils.event.AbstractListenerManager;
import io.atomix.utils.net.Address;
import io.atomix.utils.serializer.Namespace;
import io.atomix.utils.serializer.Namespaces;
import io.atomix.utils.serializer.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static com.google.common.base.MoreObjects.toStringHelper;
import static io.atomix.utils.concurrent.Threads.namedThreads;

/**
 * SWIM group membership protocol implementation.
 */
public class SwimMembershipProtocol
    extends AbstractListenerManager<GroupMembershipEvent, GroupMembershipEventListener>
    implements GroupMembershipProtocol {

  public static final Type TYPE = new Type();

  /**
   * Creates a new bootstrap provider builder.
   *
   * @return a new bootstrap provider builder
   */
  public static SwimMembershipProtocolBuilder builder() {
    return new SwimMembershipProtocolBuilder();
  }

  /**
   * Bootstrap member location provider type.
   */
  public static class Type implements GroupMembershipProtocol.Type<SwimMembershipProtocolConfig> {
    private static final String NAME = "bootstrap";

    @Override
    public String name() {
      return NAME;
    }

    @Override
    public SwimMembershipProtocolConfig newConfig() {
      return new SwimMembershipProtocolConfig();
    }

    @Override
    public GroupMembershipProtocol newProtocol(SwimMembershipProtocolConfig config) {
      return new SwimMembershipProtocol(config);
    }
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(SwimMembershipProtocol.class);

  private static final String MEMBERSHIP_GOSSIP = "atomix-membership-gossip";
  private static final String MEMBERSHIP_PROBE = "atomix-membership-probe";
  private static final String MEMBERSHIP_PROBE_REQUEST = "atomix-membership-probe-request";

  private static final Serializer SERIALIZER = Serializer.using(
      Namespace.builder()
          .register(Namespaces.BASIC)
          .nextId(Namespaces.BEGIN_USER_CUSTOM_ID)
          .register(MemberId.class)
          .register(new AddressSerializer(), Address.class)
          .register(ImmutableMember.class)
          .register(State.class)
          .build("ClusterMembershipService"));

  private final BiFunction<Address, byte[], byte[]> probeHandler = (address, payload) ->
      SERIALIZER.encode(handleProbe(SERIALIZER.decode(payload)));
  private final BiFunction<Address, byte[], CompletableFuture<byte[]>> probeRequestHandler = (address, payload) ->
      handleProbeRequest(SERIALIZER.decode(payload))
          .thenApply(SERIALIZER::encode);
  private final BiConsumer<Address, byte[]> gossipListener = (address, payload) ->
      handleGossipUpdates(SERIALIZER.decode(payload));

  private final SwimMembershipProtocolConfig config;
  private NodeDiscoveryService discoveryService;
  private BootstrapService bootstrapService;

  private final AtomicBoolean started = new AtomicBoolean();
  private SwimMember localMember;
  private volatile Properties localProperties = new Properties();
  private final Map<MemberId, SwimMember> members = Maps.newConcurrentMap();
  private List<SwimMember> randomMembers = Lists.newCopyOnWriteArrayList();
  private final NodeDiscoveryEventListener discoveryEventListener = this::handleDiscoveryEvent;
  private final List<ImmutableMember> updates = Lists.newCopyOnWriteArrayList();

  private final ScheduledExecutorService swimScheduler = Executors.newSingleThreadScheduledExecutor(
      namedThreads("atomix-cluster-heartbeat-sender", LOGGER));
  private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor(
      namedThreads("atomix-cluster-events", LOGGER));
  private ScheduledFuture<?> gossipFuture;
  private ScheduledFuture<?> probeFuture;

  private final AtomicInteger probeCounter = new AtomicInteger();

  SwimMembershipProtocol(SwimMembershipProtocolConfig config) {
    this.config = config;
  }

  @Override
  public SwimMembershipProtocolConfig config() {
    return config;
  }

  @Override
  public Set<Member> getMembers() {
    return ImmutableSet.copyOf(members.values());
  }

  @Override
  public Member getMember(MemberId memberId) {
    return members.get(memberId);
  }

  @Override
  protected void post(GroupMembershipEvent event) {
    eventExecutor.execute(() -> super.post(event));
  }

  /**
   * Checks the local member metadata for changes.
   */
  private void checkMetadata() {
    if (!localMember.properties().equals(localProperties)) {
      localProperties = new Properties();
      localProperties.putAll(localMember.properties());
      LOGGER.debug("{} - Detected local properties change {}", localMember.id(), localProperties);
      localMember.setTerm(localMember.getTerm() + 1);
      post(new GroupMembershipEvent(GroupMembershipEvent.Type.METADATA_CHANGED, localMember));
      recordUpdate(localMember.copy());
    }
  }

  /**
   * Updates the state for the given member.
   *
   * @param member the member for which to update the state
   * @return whether the state for the member was updated
   */
  private boolean updateState(ImmutableMember member) {
    // If the member matches the local member, ignore the update.
    if (member.id().equals(localMember.id())) {
      return false;
    }

    SwimMember swimMember = members.get(member.id());

    // If the local member is not present, add the member in the ALIVE state.
    if (swimMember == null) {
      swimMember = new SwimMember(member);
      members.put(swimMember.id(), swimMember);
      randomMembers.add(swimMember);
      Collections.shuffle(randomMembers);
      LOGGER.debug("{} - Member added {}", this.localMember.id(), swimMember);
      swimMember.setState(State.ALIVE);
      post(new GroupMembershipEvent(GroupMembershipEvent.Type.MEMBER_ADDED, swimMember.copy()));
      recordUpdate(swimMember.copy());
      return true;
    }
    // If the term has been increased, update the member and record a gossip event.
    else if (member.term() > swimMember.getTerm()) {
      // If the member's version has changed, remove the old member and add the new member.
      if (!Objects.equals(member.version(), swimMember.version())) {
        members.remove(member.id());
        randomMembers.remove(swimMember);
        post(new GroupMembershipEvent(GroupMembershipEvent.Type.MEMBER_REMOVED, swimMember.copy()));
        swimMember = new SwimMember(member);
        swimMember.setState(State.ALIVE);
        members.put(member.id(), swimMember);
        randomMembers.add(swimMember);
        Collections.shuffle(randomMembers);
        LOGGER.debug("{} - Evicted member for new version {}", swimMember);
        post(new GroupMembershipEvent(GroupMembershipEvent.Type.MEMBER_ADDED, swimMember.copy()));
        recordUpdate(swimMember.copy());
      } else {
        // Update the term for the local member.
        swimMember.setTerm(member.term());

        // If the state has been changed to ALIVE, trigger a REACHABILITY_CHANGED event and then update metadata.
        if (member.state() == State.ALIVE && swimMember.getState() != State.ALIVE) {
          swimMember.setState(State.ALIVE);
          LOGGER.debug("{} - Member reachable {}", this.localMember.id(), swimMember);
          post(new GroupMembershipEvent(GroupMembershipEvent.Type.REACHABILITY_CHANGED, swimMember.copy()));
          if (!Objects.equals(member.properties(), swimMember.properties())) {
            swimMember.properties().putAll(member.properties());
            LOGGER.debug("{} - Member metadata changed {}", this.localMember.id(), swimMember);
            post(new GroupMembershipEvent(GroupMembershipEvent.Type.METADATA_CHANGED, swimMember.copy()));
          }
        }
        // If the state has been changed to SUSPECT, update metadata and then trigger a REACHABILITY_CHANGED event.
        else if (member.state() == State.SUSPECT && swimMember.getState() != State.SUSPECT) {
          if (!Objects.equals(member.properties(), swimMember.properties())) {
            swimMember.properties().putAll(member.properties());
            LOGGER.debug("{} - Member metadata changed {}", this.localMember.id(), swimMember);
            post(new GroupMembershipEvent(GroupMembershipEvent.Type.METADATA_CHANGED, swimMember.copy()));
          }
          swimMember.setState(State.SUSPECT);
          LOGGER.debug("{} - Member unreachable {}", this.localMember.id(), swimMember);
          post(new GroupMembershipEvent(GroupMembershipEvent.Type.REACHABILITY_CHANGED, swimMember.copy()));
          if (config.isNotifySuspect()) {
            gossip(swimMember, Lists.newArrayList(swimMember.copy()));
          }
        }
        // If the state has been changed to DEAD, trigger a REACHABILITY_CHANGED event if necessary and then remove
        // the member from the members list and trigger a MEMBER_REMOVED event.
        else if (member.state() == State.DEAD && swimMember.getState() != State.DEAD) {
          if (swimMember.getState() == State.ALIVE) {
            swimMember.setState(State.SUSPECT);
            LOGGER.debug("{} - Member unreachable {}", this.localMember.id(), swimMember);
            post(new GroupMembershipEvent(GroupMembershipEvent.Type.REACHABILITY_CHANGED, swimMember.copy()));
          }
          swimMember.setState(State.DEAD);
          members.remove(swimMember.id());
          randomMembers.remove(swimMember);
          Collections.shuffle(randomMembers);
          LOGGER.debug("{} - Member removed {}", this.localMember.id(), swimMember);
          post(new GroupMembershipEvent(GroupMembershipEvent.Type.MEMBER_REMOVED, swimMember.copy()));
        } else if (!Objects.equals(member.properties(), swimMember.properties())) {
          swimMember.properties().putAll(member.properties());
          LOGGER.debug("{} - Member metadata changed {}", this.localMember.id(), swimMember);
          post(new GroupMembershipEvent(GroupMembershipEvent.Type.METADATA_CHANGED, swimMember.copy()));
        }

        // Always enqueue an update for gossip when the term changes.
        recordUpdate(swimMember.copy());
        return true;
      }
    }
    // If the term remained the same but the state has progressed, update the state and trigger events.
    else if (member.term() == swimMember.getTerm() && member.state().ordinal() > swimMember.getState().ordinal()) {
      swimMember.setState(member.state());

      // If the updated state is SUSPECT, post a REACHABILITY_CHANGED event and record an update.
      if (member.state() == State.SUSPECT) {
        LOGGER.debug("{} - Member unreachable {}", this.localMember.id(), swimMember);
        post(new GroupMembershipEvent(GroupMembershipEvent.Type.REACHABILITY_CHANGED, swimMember.copy()));
        if (config.isNotifySuspect()) {
          gossip(swimMember, Lists.newArrayList(swimMember.copy()));
        }
      }
      // If the updated state is DEAD, post a REACHABILITY_CHANGED event if necessary, then post a MEMBER_REMOVED
      // event and record an update.
      else if (member.state() == State.DEAD) {
        members.remove(swimMember.id());
        randomMembers.remove(swimMember);
        Collections.shuffle(randomMembers);
        LOGGER.debug("{} - Member removed {}", this.localMember.id(), swimMember);
        post(new GroupMembershipEvent(GroupMembershipEvent.Type.MEMBER_REMOVED, swimMember.copy()));
      }
      recordUpdate(swimMember.copy());
      return true;
    }
    return false;
  }

  /**
   * Records an update as an immutable member.
   *
   * @param member the updated member
   */
  private void recordUpdate(ImmutableMember member) {
    updates.add(member);
  }

  /**
   * Checks suspect nodes for failures.
   */
  private void checkFailures() {
    for (SwimMember member : members.values()) {
      if (member.getState() == State.SUSPECT && System.currentTimeMillis() - member.getTimestamp() > config.getFailureTimeout().toMillis()) {
        member.setState(State.DEAD);
        members.remove(member.id());
        randomMembers.remove(member);
        Collections.shuffle(randomMembers);
        LOGGER.debug("{} - Member removed {}", this.localMember.id(), member);
        post(new GroupMembershipEvent(GroupMembershipEvent.Type.MEMBER_REMOVED, member.copy()));
      }
    }
  }

  /**
   * Sends probes to all known members.
   */
  private void probeAll() {
    probe(true);
  }

  /**
   * Probes a random member.
   */
  private void probe() {
    probe(false);
  }

  /**
   * Sends probes to all members or to the next member in round robin fashion.
   *
   * @param all whether to send probes to all members
   */
  private void probe(boolean all) {
    // First get a sorted list of discovery service nodes that are not present in the SWIM members.
    // This is necessary to ensure we attempt to probe all nodes that are provided by the discovery provider.
    List<SwimMember> probeMembers = Lists.newArrayList(discoveryService.getNodes().stream()
        .map(node -> new SwimMember(MemberId.from(node.id().id()), node.address()))
        .filter(member -> !members.containsKey(member.id()))
        .filter(member -> !member.id().equals(localMember.id()))
        .sorted(Comparator.comparing(Member::id))
        .collect(Collectors.toList()));

    // Then add the randomly sorted list of SWIM members.
    probeMembers.addAll(randomMembers);

    // If there are members to probe, select the next member to probe using a counter for round robin probes.
    if (!probeMembers.isEmpty()) {
      if (all) {
        for (SwimMember member : probeMembers) {
          probe(member.copy());
        }
      } else {
        SwimMember probeMember = probeMembers.get(Math.abs(probeCounter.incrementAndGet() % probeMembers.size()));
        probe(probeMember.copy());
      }
    }
  }

  /**
   * Probes the given member.
   *
   * @param member the member to probe
   */
  private void probe(ImmutableMember member) {
    LOGGER.trace("{} - Probing {}", localMember.id(), member);
    bootstrapService.getMessagingService().sendAndReceive(member.address(), MEMBERSHIP_PROBE, SERIALIZER.encode(member))
        .whenCompleteAsync((response, error) -> {
          if (error == null) {
            updateState(SERIALIZER.decode(response));
          } else {
            // Verify that the local member term has not changed and request probes from peers.
            SwimMember swimMember = members.get(member.id());
            if (swimMember != null && swimMember.getTerm() == member.term()) {
              LOGGER.debug("{} - Failed to probe {}", this.localMember.id(), member);
              requestProbes(swimMember.copy());
            }
          }
        }, swimScheduler);
  }

  /**
   * Handles a probe from another peer.
   *
   * @param member the probing member
   * @return the current term
   */
  private ImmutableMember handleProbe(ImmutableMember member) {
    LOGGER.trace("{} - Received probe {}", localMember.id(), member);
    // If the probe indicates a term greater than the local term, update the local term, increment and respond.
    if (member.term() > localMember.getTerm()) {
      localMember.setTerm(member.term() + 1);
      if (config.isBroadcastDisputes()) {
        broadcast(localMember.copy());
      }
    }
    // If the probe indicates this member is suspect, increment the local term and respond.
    else if (member.state() == State.SUSPECT) {
      localMember.setTerm(localMember.getTerm() + 1);
      if (config.isBroadcastDisputes()) {
        broadcast(localMember.copy());
      }
    }
    return localMember.copy();
  }

  /**
   * Requests probes from n peers.
   */
  private void requestProbes(ImmutableMember suspect) {
    Collection<SwimMember> members = selectRandomMembers(config.getSuspectProbes(), suspect);
    AtomicInteger counter = new AtomicInteger();
    AtomicBoolean succeeded = new AtomicBoolean();
    for (SwimMember member : members) {
      requestProbe(member, suspect).whenCompleteAsync((success, error) -> {
        int count = counter.incrementAndGet();
        if (error == null && success) {
          succeeded.set(true);
        }
        // If the count is equal to the number of probe peers and no probe has succeeded, the node is unreachable.
        else if (count == members.size() && !succeeded.get()) {
          // Broadcast the unreachable change to all peers if necessary.
          SwimMember swimMember = new SwimMember(suspect);
          LOGGER.debug("{} - Failed all probes of {}", localMember.id(), swimMember);
          swimMember.setState(State.SUSPECT);
          if (updateState(swimMember.copy()) && config.isBroadcastUpdates()) {
            broadcast(swimMember.copy());
          }
        }
      }, swimScheduler);
    }
  }

  /**
   * Requests a probe of the given suspect from the given member.
   *
   * @param member the member to perform the probe
   * @param suspect the suspect member to probe
   */
  private CompletableFuture<Boolean> requestProbe(SwimMember member, ImmutableMember suspect) {
    LOGGER.debug("{} - Requesting probe of {} from {}", this.localMember.id(), suspect, member);
    return bootstrapService.getMessagingService().sendAndReceive(member.address(), MEMBERSHIP_PROBE_REQUEST, SERIALIZER.encode(suspect))
        .thenApply(response -> {
          boolean succeeded = SERIALIZER.decode(response);
          LOGGER.debug("{} - Probe request of {} from {} {}", localMember.id(), suspect, member, succeeded ? "succeeded" : "failed");
          return succeeded;
        });
  }

  /**
   * Selects a set of random members, excluding the local member and a given member.
   *
   * @param count count the number of random members to select
   * @param exclude the member to exclude
   * @return members a set of random members
   */
  private Collection<SwimMember> selectRandomMembers(int count, ImmutableMember exclude) {
    List<SwimMember> members = this.members.values().stream()
        .filter(member -> !member.id().equals(localMember.id()) && !member.id().equals(exclude.id()))
        .collect(Collectors.toList());
    Collections.shuffle(members);
    return members.subList(0, Math.min(members.size(), count));
  }

  /**
   * Handles a probe request.
   *
   * @param member the member to probe
   */
  private CompletableFuture<Boolean> handleProbeRequest(ImmutableMember member) {
    CompletableFuture<Boolean> future = new CompletableFuture<>();
    swimScheduler.execute(() -> {
      LOGGER.trace("{} - Probing {}", localMember.id(), member);
      bootstrapService.getMessagingService().sendAndReceive(member.address(), MEMBERSHIP_PROBE, SERIALIZER.encode(member))
          .whenCompleteAsync((response, error) -> {
            if (error != null) {
              LOGGER.debug("{} - Failed to probe {}", localMember.id(), member);
              future.complete(false);
            } else {
              future.complete(true);
            }
          }, swimScheduler);
    });
    return future;
  }

  /**
   * Broadcasts the given update to all peers.
   *
   * @param update the update to broadcast
   */
  private void broadcast(ImmutableMember update) {
    for (SwimMember member : members.values()) {
      if (!localMember.id().equals(member.id())) {
        unicast(member, update);
      }
    }
  }

  /**
   * Unicasts the given update to the given member.
   *
   * @param member the member to which to unicast the update
   * @param update the update to unicast
   */
  private void unicast(SwimMember member, ImmutableMember update) {
    bootstrapService.getUnicastService().unicast(
        member.address(),
        MEMBERSHIP_GOSSIP,
        SERIALIZER.encode(Lists.newArrayList(update)));
  }

  /**
   * Gossips pending updates to the cluster.
   */
  private void gossip() {
    // Check suspect nodes for failure timeouts.
    checkFailures();

    // Check local metadata for changes.
    checkMetadata();

    // Copy and clear the list of pending updates.
    if (!updates.isEmpty()) {
      List<ImmutableMember> updates = Lists.newArrayList(this.updates);
      this.updates.clear();

      // Gossip the pending updates to peers.
      gossip(updates);
    }
  }

  /**
   * Gossips this node's pending updates with a random set of peers.
   *
   * @param updates a collection of updated to gossip
   */
  private void gossip(Collection<ImmutableMember> updates) {
    // Get a list of available peers. If peers are available, randomize the peer list and select a subset of
    // peers with which to gossip updates.
    List<SwimMember> members = Lists.newArrayList(randomMembers);
    if (!members.isEmpty()) {
      Collections.shuffle(members);
      for (int i = 0; i < Math.min(members.size(), config.getGossipFanout()); i++) {
        gossip(members.get(i), updates);
      }
    }
  }

  /**
   * Gossips this node's pending updates with the given peer.
   *
   * @param member the peer with which to gossip this node's updates
   * @param updates the updated members to gossip
   */
  private void gossip(SwimMember member, Collection<ImmutableMember> updates) {
    LOGGER.trace("{} - Gossipping updates {} to {}", localMember.id(), updates, member);
    bootstrapService.getUnicastService().unicast(
        member.address(),
        MEMBERSHIP_GOSSIP,
        SERIALIZER.encode(updates));
  }

  /**
   * Handles a gossip message from a peer.
   */
  private void handleGossipUpdates(Collection<ImmutableMember> updates) {
    for (ImmutableMember update : updates) {
      updateState(update);
    }
  }

  /**
   * Handles a member location event.
   *
   * @param event the member location event
   */
  private void handleDiscoveryEvent(NodeDiscoveryEvent event) {
    switch (event.type()) {
      case JOIN:
        handleJoinEvent(event.subject());
        break;
      case LEAVE:
        handleLeaveEvent(event.subject());
        break;
      default:
        throw new AssertionError();
    }
  }

  /**
   * Handles a node join event.
   */
  private void handleJoinEvent(Node node) {
    SwimMember member = new SwimMember(MemberId.from(node.id().id()), node.address());
    if (!members.containsKey(member.id())) {
      probe(member.copy());
    }
  }

  /**
   * Handles a node leave event.
   */
  private void handleLeaveEvent(Node node) {
    SwimMember member = members.get(MemberId.from(node.id().id()));
    if (member != null && !member.isActive()) {
      members.remove(member.id());
    }
  }

  /**
   * Registers message handlers for the SWIM protocol.
   */
  private void registerHandlers() {
    // Register TCP message handlers.
    bootstrapService.getMessagingService().registerHandler(MEMBERSHIP_PROBE, probeHandler, swimScheduler);
    bootstrapService.getMessagingService().registerHandler(MEMBERSHIP_PROBE_REQUEST, probeRequestHandler);

    // Register UDP message listeners.
    bootstrapService.getUnicastService().addListener(MEMBERSHIP_GOSSIP, gossipListener, swimScheduler);
  }

  /**
   * Unregisters handlers for the SWIM protocol.
   */
  private void unregisterHandlers() {
    // Unregister TCP message handlers.
    bootstrapService.getMessagingService().unregisterHandler(MEMBERSHIP_PROBE);
    bootstrapService.getMessagingService().unregisterHandler(MEMBERSHIP_PROBE_REQUEST);

    // Unregister UDP message listeners.
    bootstrapService.getUnicastService().removeListener(MEMBERSHIP_GOSSIP, gossipListener);
  }

  @Override
  public CompletableFuture<Void> join(BootstrapService bootstrap, NodeDiscoveryService discovery, Member member) {
    if (started.compareAndSet(false, true)) {
      this.bootstrapService = bootstrap;
      this.discoveryService = discovery;
      this.localMember = new SwimMember(
          member.id(),
          member.address(),
          member.zone(),
          member.rack(),
          member.host(),
          member.properties(),
          member.version());
      this.localProperties.putAll(localMember.properties());
      discoveryService.addListener(discoveryEventListener);

      LOGGER.info("{} - Member activated: {}", localMember.id(), localMember);
      localMember.setState(State.ALIVE);
      members.put(localMember.id(), localMember);
      post(new GroupMembershipEvent(GroupMembershipEvent.Type.MEMBER_ADDED, localMember));

      registerHandlers();
      gossipFuture = swimScheduler.scheduleAtFixedRate(
          this::gossip, 0, config.getGossipInterval().toMillis(), TimeUnit.MILLISECONDS);
      probeFuture = swimScheduler.scheduleAtFixedRate(
          this::probe, 0, config.getProbeInterval().toMillis(), TimeUnit.MILLISECONDS);
      swimScheduler.execute(this::probeAll);
      LOGGER.info("Started");
    }
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletableFuture<Void> leave(Member member) {
    if (started.compareAndSet(true, false)) {
      discoveryService.removeListener(discoveryEventListener);
      gossipFuture.cancel(false);
      probeFuture.cancel(false);
      swimScheduler.shutdownNow();
      eventExecutor.shutdownNow();
      LOGGER.info("{} - Member deactivated: {}", localMember.id(), localMember);
      localMember.setState(State.DEAD);
      members.clear();
      unregisterHandlers();
      LOGGER.info("Stopped");
    }
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Member states.
   */
  enum State {
    ALIVE(true, true),
    SUSPECT(true, false),
    DEAD(false, false);

    private final boolean active;
    private final boolean reachable;

    State(boolean active, boolean reachable) {
      this.active = active;
      this.reachable = reachable;
    }

    boolean isActive() {
      return active;
    }

    boolean isReachable() {
      return reachable;
    }
  }

  /**
   * Immutable member.
   */
  static class ImmutableMember extends Member {
    private final Version version;
    private final State state;
    private final long term;

    ImmutableMember(MemberId id, Address address, String zone, String rack, String host, Properties properties, Version version, State state, long term) {
      super(id, address, zone, rack, host, properties);
      this.version = version;
      this.state = state;
      this.term = term;
    }

    /**
     * Returns the member's version.
     *
     * @return the member's version
     */
    public Version version() {
      return version;
    }

    /**
     * Returns the member's state.
     *
     * @return the member's state
     */
    public State state() {
      return state;
    }

    /**
     * Returns the member's term.
     *
     * @return the member's term
     */
    public long term() {
      return term;
    }

    @Override
    public String toString() {
      return toStringHelper(Member.class)
          .add("id", id())
          .add("address", address())
          .add("properties", properties())
          .add("state", state())
          .add("term", term())
          .toString();
    }
  }

  /**
   * Swim member.
   */
  static class SwimMember extends Member {
    private final Version version;
    private volatile State state;
    private volatile long term;
    private volatile long timestamp;

    SwimMember(MemberId id, Address address) {
      super(id, address);
      this.version = null;
    }

    SwimMember(
        MemberId id,
        Address address,
        String zone,
        String rack,
        String host,
        Properties properties,
        Version version) {
      super(id, address, zone, rack, host, properties);
      this.version = version;
      term = System.currentTimeMillis();
    }

    SwimMember(ImmutableMember member) {
      super(member.id(), member.address(), member.zone(), member.rack(), member.host(), member.properties());
      this.version = member.version;
      this.state = member.state;
      this.term = member.term;
    }

    @Override
    public Version version() {
      return version;
    }

    /**
     * Changes the member's state.
     *
     * @param state the member's state
     */
    void setState(State state) {
      if (this.state != state) {
        this.state = state;
        setTimestamp(System.currentTimeMillis());
      }
    }

    /**
     * Returns the member's state.
     *
     * @return the member's state
     */
    State getState() {
      return state;
    }

    @Override
    public boolean isActive() {
      return state.isActive();
    }

    @Override
    public boolean isReachable() {
      return state.isReachable();
    }

    /**
     * Returns the member logical timestamp.
     *
     * @return the member logical timestamp
     */
    long getTerm() {
      return term;
    }

    /**
     * Sets the member's logical timestamp.
     *
     * @param term the member's logical timestamp
     */
    void setTerm(long term) {
      this.term = term;
    }

    /**
     * Returns the wall clock timestamp.
     *
     * @return the wall clock timestamp
     */
    long getTimestamp() {
      return timestamp;
    }

    /**
     * Sets the wall clock timestamp.
     *
     * @param timestamp the wall clock timestamp
     */
    void setTimestamp(long timestamp) {
      this.timestamp = timestamp;
    }

    /**
     * Copies the member's state to a new object.
     *
     * @return the copied object
     */
    ImmutableMember copy() {
      return new ImmutableMember(
          id(),
          address(),
          zone(),
          rack(),
          host(),
          properties(),
          version(),
          state,
          term);
    }
  }
}