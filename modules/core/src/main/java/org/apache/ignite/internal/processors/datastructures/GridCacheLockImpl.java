/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.datastructures;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteCondition;
import org.apache.ignite.IgniteException;
import org.apache.ignite.IgniteInterruptedException;
import org.apache.ignite.IgniteLock;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.internal.GridKernalContext;
import org.apache.ignite.internal.processors.cache.GridCacheContext;
import org.apache.ignite.internal.processors.cache.IgniteInternalCache;
import org.apache.ignite.internal.processors.cache.transactions.IgniteInternalTx;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.X;
import org.apache.ignite.internal.util.typedef.internal.CU;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteBiTuple;
import org.apache.ignite.transactions.TransactionRollbackException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static org.apache.ignite.internal.processors.cache.GridCacheUtils.retryTopologySafe;
import static org.apache.ignite.transactions.TransactionConcurrency.PESSIMISTIC;
import static org.apache.ignite.transactions.TransactionIsolation.REPEATABLE_READ;

/**
 * Cache reentrant lock implementation based on AbstractQueuedSynchronizer.
 */
public final class GridCacheLockImpl implements GridCacheLockEx, Externalizable {
    /** */
    private static final long serialVersionUID = 0L;

    /** Deserialization stash. */
    private static final ThreadLocal<IgniteBiTuple<GridKernalContext, String>> stash =
        new ThreadLocal<IgniteBiTuple<GridKernalContext, String>>() {
            @Override protected IgniteBiTuple<GridKernalContext, String> initialValue() {
                return F.t2();
            }
        };

    /** Logger. */
    private IgniteLogger log;

    /** Reentrant lock name. */
    private String name;

    /** Removed flag. */
    private volatile boolean rmvd;

    /** Reentrant lock key. */
    private GridCacheInternalKey key;

    /** Reentrant lock projection. */
    private IgniteInternalCache<GridCacheInternalKey, GridCacheLockState> lockView;

    /** Cache context. */
    private GridCacheContext ctx;

    /** Initialization guard. */
    private final AtomicBoolean initGuard = new AtomicBoolean();

    /** Initialization latch. */
    private final CountDownLatch initLatch = new CountDownLatch(1);

    /** Lock that provides non-overlapping processing of updates. */
    private ReentrantLock updateLock = new ReentrantLock();

    /** Internal synchronization object. */
    private Sync sync;

    /** Flag indicating that every operation on this lock should be interrupted. */
    private volatile boolean interruptAll = false;

    /**
     * Empty constructor required by {@link Externalizable}.
     */
    public GridCacheLockImpl() {
        // No-op.
    }

    /**
     * Synchronization implementation for reentrant lock using AbstractQueuedSynchronizer.
     */
    class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = 1192457210091910933L;

        private static final long LOCK_FREE = 0;

        /** Map containing condition objects. */
        private Map<String, ConditionObject> conditionMap;

        /** List of condition signal calls on this node. */
        private Map<String, Integer> outgoingSignals;

        /** Last condition waited on. */
        @Nullable
        private volatile String lastCondition;

        /** True if any node owning the lock had failed. */
        private volatile boolean isBroken = false;

        /** UUID of the node that currently owns the lock. */
        private volatile UUID currentOwnerNode;

        /** ID of the thread that currently owns the lock. */
        private volatile long currentOwnerThreadId;

        /** UUID of this node. */
        private final UUID thisNode;

        /** FailoverSafe flag. */
        private final boolean failoverSafe;

        /** Fairness flag. */
        private final boolean fair;

        /** Threads that are waiting on this lock. */
        private Set<Long> waitingThreads;

        protected Sync(GridCacheLockState state) {
            setState(state.get());

            thisNode = ctx.localNodeId();

            currentOwnerNode = state.getId();

            currentOwnerThreadId = state.getThreadId();

            conditionMap = new HashMap<>();

            outgoingSignals = new HashMap<>();

            failoverSafe = state.isFailoverSafe();

            fair = state.isFair();

            waitingThreads = new ConcurrentSkipListSet<Long>();
        }

        /** */
        protected void addOutgoingSignal(String condition) {
            int cnt = 0;
            if (outgoingSignals.containsKey(condition)) {
                cnt = outgoingSignals.get(condition);

                // SignalAll has already been called.
                if (cnt == 0)
                    return;
            }

            outgoingSignals.put(condition, cnt + 1);
        }

        protected void addOutgoingSignalAll(String condition) {
            outgoingSignals.put(condition, 0);
        }

        /** Process any condition await calls on this node. */
        private String processAwait() {
            if(lastCondition == null)
                return null;

            String ret = lastCondition;

            lastCondition = null;

            return ret;
        }

        /** */
        private Map<String, Integer> processSignal(){
            Map<String,Integer> ret = new HashMap<>(outgoingSignals);

            outgoingSignals.clear();

            return ret;
        }

        /** Interrupt every thread on this node waiting on this lock. */
        private synchronized void interruptAll(){
            // First release all threads waiting on associated condition queues.
            if(!conditionMap.isEmpty()) {
                // Temporarily obtain ownership of the lock,
                // in order to signal all conditions.
                UUID tempUUID = getOwnerNode();

                long tempThreadID = currentOwnerThreadId;

                this.setCurrentOwnerNode(thisNode);

                this.currentOwnerThreadId = Thread.currentThread().getId();

                for (Condition c : conditionMap.values())
                    c.signalAll();

                // Restore owner node and owner thread.
                this.setCurrentOwnerNode(tempUUID);

                this.currentOwnerThreadId = tempThreadID;
            }

            // Interrupt any future call to acquire/release on this sync object.
            interruptAll = true;

            // Interrupt any ongoing transactions.
            for(Thread t: getQueuedThreads()){
                t.interrupt();
            }
        }

        /** Check if lock is in correct state (i.e. not broken in non-failoversafe mode),
         * if not throw  {@linkplain IgniteInterruptedException} */
        private void validate(final boolean throwInterrupt){
            // Interrupted flag shouldn't be always cleared
            // (e.g. lock() method doesn't throw exception and doesn't clear interrupted)
            // but should be cleared if this method is called after lock breakage or node stop.
            // If interruptAll is set, exception is thrown anyway.
            boolean interrupted = Thread.currentThread().isInterrupted();

            // Clear interrupt flag.
            if(throwInterrupt || interruptAll){
                Thread.interrupted();
            }

            if(interruptAll){
                throw new IgniteException("Lock broken (possible reason: node stopped" +
                    " or node owning lock failed while in non-failoversafe mode).");
            }

            // Global queue should be synchronized only if interrupted exception should be thrown.
            if(fair && (throwInterrupt && interrupted) && !interruptAll){
                this.synchronizeQueue(true, Thread.currentThread());

                throw new IgniteInterruptedException("Lock is interrupted.");
            }
        }

        /**
         * Sets the number of permits currently acquired on this lock. This method should only be used in {@linkplain
         * GridCacheLockImpl#onUpdate(GridCacheLockState)}.
         *
         * @param permits Number of permits acquired at this reentrant lock.
         */
        final synchronized void setPermits(int permits) {
            setState(permits);
        }

        /**
         * Gets the number of permissions currently acquired at this lock.
         *
         * @return Number of permits acquired at this reentrant lock.
         */
        final int getPermits() {
            return getState();
        }

        /**
         * Sets the UUID of the node that currently owns this lock. This method should only be used in {@linkplain
         * GridCacheLockImpl#onUpdate(GridCacheLockState)}.
         *
         * @param ownerNode UUID of the node owning this lock.
         */
        final synchronized void setCurrentOwnerNode(UUID ownerNode) {
            currentOwnerNode = ownerNode;
        }

        /**
         * Gets the UUID of the node that currently owns the lock.
         *
         * @return UUID of the node that currently owns the lock.
         */
        final UUID getOwnerNode() {
            return currentOwnerNode;
        }

        /**
         * Checks if latest call to acquire/release was called on this node.
         * Should only be called from update method.
         *
         * @param newOwnerID ID of the node that is about to acquire this lock (or null).
         * @return true if acquire/release that triggered last update came from this node.
         */
        protected boolean isLockedLocally(UUID newOwnerID){
            if(thisNode.equals(getOwnerNode()) || thisNode.equals(newOwnerID)){
                return true;
            }

            return false;
        }

        protected void setCurrentOwnerThread(long newOwnerThreadId){
            this.currentOwnerThreadId = newOwnerThreadId;
        }

        /**
         * Returns true if node that owned the locked failed before call to unlock.
         *
         * @return true if any node failed while owning the lock.
         */
        protected boolean isBroken() {
            return isBroken;
        }

        /** */
        protected void setBroken(boolean isBroken) {
            this.isBroken = isBroken;
        }

        /** */
        protected synchronized boolean hasPredecessor(LinkedList<UUID> nodes){
            if(!fair)
                return false;

            for (Iterator<UUID> it = nodes.iterator(); it.hasNext(); ) {
                UUID node = it.next();

                if (ctx.discovery().node(node) == null) {
                    it.remove();

                    continue;
                }

                if (node.equals(thisNode)) {
                    return false;
                }

                return true;
            }

            return false;
        }

        /**
         * Performs tryLock.
         * @param acquires Number of permits to acquire.
         * @param fair Fairness parameter.
         * @result true if succeeded, false otherwise.
         */
        final boolean tryAcquire(final int acquires, final boolean fair) {
            // If broken in non-failoversafe mode, exit immediately.
            if(interruptAll){
                return true;
            }

            final Thread current = Thread.currentThread();

            boolean failed = false;

            int c = getState();

            // Wait for lock to reach stable state.
            while(c != 0){
                UUID currentOwner = this.currentOwnerNode;

                if(currentOwner != null) {
                    failed = ctx.discovery().node(currentOwner) == null;

                    break;
                }

                c = getState();
            }

            // Check if lock is released or current owner failed.
            if(c == 0 || failed){
                if (compareAndSetGlobalState(0, acquires, current, fair)){

                    // Not used for synchronization (we use ThreadID), but updated anyway.
                    setExclusiveOwnerThread(current);

                    while(!isHeldExclusively() && !interruptAll)
                        Thread.yield();

                    return true;
                }
            }
            else if (isHeldExclusively()) {
                int nextc = c + acquires;

                if (nextc < 0) // overflow
                    throw new Error("Maximum lock count exceeded");

                setState(nextc);

                return true;
            }

            if(fair && !isQueued(current)){
                synchronizeQueue(false, current);
            }

            return false;
        }

        /**
         * Performs lock.
         */
        final void lock() {
            acquire(1);
        }

        /** {@inheritDoc} */
        protected final boolean tryAcquire(int acquires) {
            return tryAcquire(acquires, fair);
        }

        /** {@inheritDoc} */
        protected final boolean tryRelease(int releases) {
            // This method is called with release==0 only when trying to wake through update,
            // to check if some other node released the lock.
            if(releases == 0) {
                return true;
            }

            // If broken in non-failoversafe mode, exit immediately.
            if(interruptAll) {
                return true;
            }

            int c = getState() - releases;

            if (!isHeldExclusively()) {
                log.error("Lock.unlock() is called in illegal state [callerNodeId=" + thisNode + ", ownerNodeId="
                    + currentOwnerNode + ", callerThreadId=" + Thread.currentThread().getId() + ", ownerThreadId="
                    + currentOwnerThreadId + ", lockState=" + getState() + "]");
                throw new IllegalMonitorStateException();
            }

            boolean free = false;

            if (c == 0){
                free = true;

                setGlobalState(0, processAwait(), processSignal());

                while(isHeldExclusively() && !interruptAll)
                    Thread.yield();
            }
            else
                setState(c);

            return free;
        }


        /** {@inheritDoc} */
        protected final boolean isHeldExclusively() {
            // While we must in general read state before owner,
            // we don't need to do so to check if current thread is owner

            return this.currentOwnerThreadId == Thread.currentThread().getId() && thisNode.equals(currentOwnerNode);
        }

        /** {@inheritDoc} */
        final synchronized IgniteCondition newCondition(String name) {
            if(conditionMap.containsKey(name))
                return new IgniteConditionObject(name, conditionMap.get(name));

            ConditionObject cond = new ConditionObject();

            conditionMap.put(name, cond);

            return new IgniteConditionObject(name, cond);
        }

        // Methods relayed from outer class

        final int getHoldCount() {
            return isHeldExclusively() ? getState() : 0;
        }

        final boolean isLocked() throws IgniteCheckedException {
            return getState() != 0 || lockView.get(key).get() != 0;
        }

        /**
         * This method is used for synchronizing the reentrant lock state across all nodes.
         */
        protected boolean compareAndSetGlobalState(final int expVal, final int newVal,
            final Thread newThread, final boolean bargingProhibited) {
            try {
                return CU.outTx(
                    retryTopologySafe(new Callable<Boolean>() {
                        @Override public Boolean call() throws Exception {
                            try (IgniteInternalTx tx = CU.txStartInternal(ctx, lockView, PESSIMISTIC, REPEATABLE_READ)) {

                                GridCacheLockState val = lockView.get(key);

                                if (val == null)
                                    throw new IgniteCheckedException("Failed to find reentrant lock with given name: " + name);

                                final long newThreadID = newThread.getId();

                                LinkedList<UUID> nodes = val.getNodes();

                                // Barging is prohibited in fair mode unless tryLock() is called.
                                if(!(bargingProhibited && hasPredecessor(nodes))){
                                    if (val.get() == expVal || ctx.discovery().node(val.getId()) == null) {
                                        val.set(newVal);

                                        val.setId(thisNode);

                                        val.setThreadId(newThreadID);

                                        val.setSignals(null);

                                        // This node is already in queue, except in cases where this is the only node
                                        // or this is a call to tryLock(), in which case barging is ok.
                                        // Queue is only updated if this is fair lock.
                                        if(val.isFair() && (nodes.isEmpty() || !bargingProhibited)){
                                            nodes.addFirst(thisNode);
                                        }

                                        val.setNodes(nodes);

                                        val.setChanged(true);

                                        lockView.put(key, val);

                                        tx.commit();

                                        return true;
                                    }
                                }

                                return false;
                            }
                            catch (Error | Exception e) {
                                if(interruptAll){
                                    log.info("Node is stopped (or lock is broken in non-failover safe mode)," +
                                        " aborting transaction.");

                                    // Return immediately, exception will be thrown later.
                                    return true;
                                }
                                else{
                                    if(Thread.currentThread().isInterrupted()){
                                        log.info("Thread is interrupted while attempting to acquire lock.");

                                        // Delegate the decision to throw InterruptedException to the AQS.
                                        sync.release(0);

                                        return false;
                                    }

                                    U.error(log, "Failed to compare and set: " + this, e);
                                }

                                throw e;
                            }
                        }
                    }),
                    ctx
                );
            }
            catch (IgniteCheckedException e) {
                throw U.convertException(e);
            }
        }

        /**
         * This method is used for synchronizing the number of acquire attempts on this lock across all nodes.
         *
         * @param cancelled true if acquire attempt is cancelled, false if acquire attempt should be registered.
         */
        protected boolean synchronizeQueue(final boolean cancelled, final Thread thread) {
            final AtomicBoolean interrupted = new AtomicBoolean(false);

            try {
                return CU.outTx(
                    retryTopologySafe(new Callable<Boolean>() {
                        @Override public Boolean call() throws Exception {
                            try (IgniteInternalTx tx = CU.txStartInternal(ctx, lockView, PESSIMISTIC, REPEATABLE_READ)) {

                                GridCacheLockState val = lockView.get(key);

                                if (val == null)
                                    throw new IgniteCheckedException("Failed to find reentrant lock with given name: " + name);

                                LinkedList<UUID> nodes = val.getNodes();

                                if(!cancelled){
                                    nodes.add(thisNode);

                                    val.setChanged(false);

                                    lockView.put(key, val);

                                    tx.commit();

                                    // Keep track of all threads that are queued in global queue.
                                    // We deliberately don't use #sync.isQueued(), because AQS
                                    // cancel threads immediately after throwing interrupted exception.
                                    sync.waitingThreads.add(thread.getId());

                                    return true;
                                }
                                else {
                                    if (sync.waitingThreads.contains(thread.getId())) {
                                        // Update other nodes if this is the first node in queue.
                                        val.setChanged(nodes.lastIndexOf(thisNode) == 0);

                                        nodes.removeLastOccurrence(thisNode);

                                        lockView.put(key, val);

                                        tx.commit();

                                        sync.waitingThreads.remove(thread.getId());

                                        return true;
                                    }
                                }

                                return false;
                            }
                            catch (Error | Exception e) {
                                if(interruptAll){
                                    log.info("Node is stopped (or lock is broken in non-failover safe mode)," +
                                        " aborting transaction.");

                                    // Abort this attempt to synchronize queue and start another one,
                                    // that will return immediately.
                                    sync.release(0);

                                    return false;
                                }
                                else{
                                    // If thread got interrupted, abort this attempt to synchronize queue,
                                    // clear interrupt flag and try again, and let the AQS decide
                                    // whether to throw an exception or ignore it.
                                    if(Thread.interrupted() || X.hasCause(e, InterruptedException.class)){
                                        interrupted.set(true);

                                        throw new TransactionRollbackException("Thread got interrupted " +
                                            "while synchronizing the global queue, retrying. ");
                                    }

                                    U.error(log, "Failed to synchronize global lock queue: " + this, e);
                                }

                                throw e;
                            }
                        }
                    }),
                    ctx
                );
            }
            catch (IgniteCheckedException e) {
                throw U.convertException(e);
            }
            finally {
                // Restore interrupt flag and let AQS decide what to do with it.
                if(interrupted.get()) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /**
         * Sets the global state across all nodes after releasing the reentrant lock.
         *
         * @param newVal New state.
         * @param lastCondition Id of the condition await is called.
         * @param outgoingSignals Map containing signal calls on this node since the last acquisition of the lock.
         */
        protected boolean setGlobalState(final int newVal, @Nullable final String lastCondition, final Map<String, Integer> outgoingSignals) {
            try {
                return CU.outTx(
                    retryTopologySafe(new Callable<Boolean>() {
                        @Override public Boolean call() throws Exception {
                            try (IgniteInternalTx tx = CU.txStartInternal(ctx, lockView, PESSIMISTIC, REPEATABLE_READ)) {
                                GridCacheLockState val = lockView.get(key);

                                if (val == null)
                                    throw new IgniteCheckedException("Failed to find reentrant lock with given name: " + name);

                                val.set(newVal);

                                if(newVal == 0) {
                                    val.setId(null);

                                    val.setThreadId(LOCK_FREE);
                                }

                                val.setChanged(true);

                                // If this lock is fair, remove this node from queue.
                                if(val.isFair() && newVal == 0) {
                                    UUID removedNode = val.getNodes().removeFirst();

                                    assert(thisNode.equals(removedNode));
                                }

                                // Get global condition queue.
                                Map<String, LinkedList<UUID>> condMap = val.getConditionMap();

                                // Create map containing signals from this node.
                                Map<UUID, LinkedList<String>> signalMap = new HashMap<UUID, LinkedList<String>>();

                                // Put any signal calls on this node to global state.
                                if (!outgoingSignals.isEmpty()) {
                                    for (String condition : outgoingSignals.keySet()) {
                                        int cnt = outgoingSignals.get(condition);

                                        // Get queue for this condition.
                                        List<UUID> list = condMap.get(condition);

                                        if (list != null && !list.isEmpty()) {
                                            // Check if signalAll was called.
                                            if (cnt == 0) {
                                                cnt = list.size();
                                            }

                                            // Remove from global condition queue.
                                            for (int i = 0; i < cnt; i++) {
                                                if(list.isEmpty())
                                                    break;

                                                UUID uuid = list.remove(0);

                                                // Skip if node to be released is not alive anymore.
                                                if(ctx.discovery().node(uuid) == null){
                                                    cnt++;

                                                    continue;
                                                }

                                                LinkedList<String> queue = signalMap.get(uuid);

                                                if (queue == null) {
                                                    queue = new LinkedList<String>();

                                                    signalMap.put(uuid, queue);
                                                }

                                                queue.add(condition);
                                            }
                                        }
                                    }
                                }

                                val.setSignals(signalMap);

                                // Check if this release is called after condition.await() call;
                                // If true, add this node to the global waiting queue.
                                if (lastCondition != null) {
                                    LinkedList<UUID> queue;

                                    if (!condMap.containsKey(lastCondition)) {
                                        // New condition object.
                                        queue = new LinkedList();
                                    }
                                    else {
                                        // Existing condition object.
                                        queue = condMap.get(lastCondition);
                                    }

                                    queue.add(thisNode);

                                    condMap.put(lastCondition, queue);
                                }

                                val.setConditionMap(condMap);

                                lockView.put(key, val);

                                tx.commit();

                                return true;
                            }
                            catch (Error | Exception e) {
                                if(interruptAll){
                                    log.info("Node is stopped (or lock is broken in non-failover safe mode)," +
                                        " aborting transaction.");

                                    return true;
                                }
                                else{
                                    U.error(log, "Failed to release: " + this, e);
                                }

                                throw e;
                            }
                        }
                    }),
                    ctx
                );
            }
            catch (IgniteCheckedException e) {
                throw U.convertException(e);
            }
        }

        protected synchronized boolean checkIncomingSignals(GridCacheLockState state){
            if(state.getSignals() == null)
                return false;

            LinkedList<String> signals = state.getSignals().get(thisNode);

            if(signals == null || signals.isEmpty())
                return false;

            UUID tempUUID = getOwnerNode();

            Thread tempThread = getExclusiveOwnerThread();

            long tempThreadID = currentOwnerThreadId;

            // Temporarily allow current thread to signal condition object.
            // This is safe to do because:
            // 1. if release was called on this node,
            // it was called from currently active thread;
            // 2. if release came from a thread on any other node,
            // all threads on this node are already blocked.
            this.setCurrentOwnerNode(thisNode);

            this.setExclusiveOwnerThread(Thread.currentThread());

            this.currentOwnerThreadId = Thread.currentThread().getId();

            for(String signal: signals){
                conditionMap.get(signal).signal();
            }

            // Restore owner node and owner thread.
            this.setCurrentOwnerNode(tempUUID);

            this.setExclusiveOwnerThread(tempThread);

            this.currentOwnerThreadId = tempThreadID;

            return true;
        }

        /**
         *  Condition implementation for {@linkplain IgniteLock}.
         *
         **/
        public class IgniteConditionObject implements IgniteCondition {

            private final String name;

            private final AbstractQueuedSynchronizer.ConditionObject object;

            protected IgniteConditionObject(String name, ConditionObject object){
                this.name = name;

                this.object = object;
            }

            /**
             * Name of this condition.
             *
             * @return name Name of this condition object.
             */
            @Override public String name() {
                return name;
            }

            /** {@inheritDoc} */
            @Override public void await() throws IgniteInterruptedException {
                ctx.kernalContext().gateway().readLock();

                try {
                    if(!isHeldExclusively())
                        throw new IllegalMonitorStateException();

                    lastCondition = this.name;

                    object.await();

                    sync.validate(true);
                }
                catch (InterruptedException e) {
                    throw new IgniteInterruptedException(e);
                }
                finally {
                    ctx.kernalContext().gateway().readUnlock();
                }
            }

            /** {@inheritDoc} */
            @Override public void awaitUninterruptibly() {
                ctx.kernalContext().gateway().readLock();

                try {
                    if (!isHeldExclusively())
                        throw new IllegalMonitorStateException();

                    lastCondition = this.name;

                    object.awaitUninterruptibly();

                    sync.validate(false);
                }
                finally {
                    ctx.kernalContext().gateway().readUnlock();
                }
            }

            /** {@inheritDoc} */
            @Override public long awaitNanos(long nanosTimeout) throws IgniteInterruptedException {
                ctx.kernalContext().gateway().readLock();

                try {
                    if(!isHeldExclusively())
                        throw new IllegalMonitorStateException();

                    lastCondition = this.name;

                    long result =  object.awaitNanos(nanosTimeout);

                    sync.validate(true);

                    return result;
                }
                catch (InterruptedException e) {
                    throw new IgniteInterruptedException(e);
                }
                finally {
                    ctx.kernalContext().gateway().readUnlock();
                }
            }

            /** {@inheritDoc} */
            @Override public boolean await(long time, TimeUnit unit) throws IgniteInterruptedException {
                ctx.kernalContext().gateway().readLock();

                try {
                    if(!isHeldExclusively())
                        throw new IllegalMonitorStateException();

                    lastCondition = this.name;

                    boolean result = object.await(time, unit);

                    sync.validate(true);

                    return result;
                }
                catch (InterruptedException e) {
                    throw new IgniteInterruptedException(e);
                }
                finally {
                    ctx.kernalContext().gateway().readUnlock();
                }
            }

            /** {@inheritDoc} */
            @Override public boolean awaitUntil(Date deadline) throws IgniteInterruptedException {
                ctx.kernalContext().gateway().readLock();

                try {
                    if(!isHeldExclusively())
                        throw new IllegalMonitorStateException();

                    lastCondition = this.name;

                    boolean result = object.awaitUntil(deadline);

                    sync.validate(true);

                    return result;
                }
                catch (InterruptedException e) {
                    throw new IgniteInterruptedException(e);
                }
                finally {
                    ctx.kernalContext().gateway().readUnlock();
                }
            }

            /** {@inheritDoc} */
            @Override public void signal() {
                ctx.kernalContext().gateway().readLock();

                try {
                    if (!isHeldExclusively())
                        throw new IllegalMonitorStateException();

                    validate(false);

                    addOutgoingSignal(name);
                }
                finally {
                    ctx.kernalContext().gateway().readUnlock();
                }
            }

            /** {@inheritDoc} */
            @Override public void signalAll() {
                ctx.kernalContext().gateway().readLock();

                try {
                    if (!isHeldExclusively())
                        throw new IllegalMonitorStateException();

                    sync.validate(false);

                    addOutgoingSignalAll(name);
                }
                finally {
                    ctx.kernalContext().gateway().readUnlock();
                }
            }
        }
    }

    /**
     * Constructor.
     *
     * @param name Reentrant lock name.
     * @param key Reentrant lock key.
     * @param lockView Reentrant lock projection.
     * @param ctx Cache context.
     */
    public GridCacheLockImpl(String name,
        GridCacheInternalKey key,
        IgniteInternalCache<GridCacheInternalKey, GridCacheLockState> lockView,
        GridCacheContext ctx) {
        assert name != null;
        assert key != null;
        assert ctx != null;
        assert lockView != null;

        this.name = name;
        this.key = key;
        this.lockView = lockView;
        this.ctx = ctx;

        log = ctx.logger(getClass());
    }

    /**
     * @throws IgniteCheckedException If operation failed.
     */
    private void initializeReentrantLock() throws IgniteCheckedException {
        if (initGuard.compareAndSet(false, true)) {
            try {
                sync = CU.outTx(
                    retryTopologySafe(new Callable<Sync>() {
                        @Override public Sync call() throws Exception {
                            try (IgniteInternalTx tx = CU.txStartInternal(ctx, lockView, PESSIMISTIC, REPEATABLE_READ)) {
                                GridCacheLockState val = lockView.get(key);

                                if (val == null) {
                                    if (log.isDebugEnabled())
                                        log.debug("Failed to find reentrant lock with given name: " + name);

                                    return null;
                                }

                                tx.rollback();

                                return new Sync(val);
                            }
                        }
                    }),
                    ctx
                );

                if (log.isDebugEnabled())
                    log.debug("Initialized internal sync structure: " + sync);
            }
            finally {
                initLatch.countDown();
            }
        }
        else {
            U.await(initLatch);

            if (sync == null)
                throw new IgniteCheckedException("Internal reentrant lock has not been properly initialized.");
        }
    }

    /** {@inheritDoc} */
    @Override public void onUpdate(GridCacheLockState val) {
        // Called only on initialization, so it's safe to ignore update.
        if (sync == null)
            return;

        try {
            updateLock.lock();

            // If this update is a result of unsuccessful acquire in fair mode, no local update should be done.
            if(!val.isChanged())
                return;

            // Check if update came from this node.
            boolean local = sync.isLockedLocally(val.getId());

            // Process any incoming signals.
            boolean incomingSignals = sync.checkIncomingSignals(val);

            // Update permission count.
            sync.setPermits(val.get());

            // Update owner's node id.
            sync.setCurrentOwnerNode(val.getId());

            // Update owner's thread id.
            sync.setCurrentOwnerThread(val.getThreadId());

            // Check if any threads waiting on this node need to be notified.
            if ((incomingSignals || sync.getPermits() == 0) && !local) {
                // Try to notify any waiting threads.
                sync.release(0);
            }

        } finally{
            updateLock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override public void onNodeRemoved(UUID nodeId) {
        try {
            updateLock.lock();

            if (nodeId.equals(sync.getOwnerNode())){
                sync.setBroken(true);

                if(!sync.failoverSafe){
                    sync.interruptAll();
                }
            }

            // Try to notify any waiting threads.
            sync.release(0);
        }
        finally {
            updateLock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override public void onStop() {
        if (sync == null) {
            interruptAll = true;

            return;
        }

        sync.setBroken(true);

        sync.interruptAll();

        // Try to notify any waiting threads.
        sync.release(0);
    }

    /** {@inheritDoc} */
    @Override public String name() {
        return name;
    }

    /** {@inheritDoc} */
    @Override public void lock() {
        ctx.kernalContext().gateway().readLock();

        try{
            initializeReentrantLock();

            sync.lock();

            sync.validate(false);
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
        finally {
            ctx.kernalContext().gateway().readUnlock();
        }
    }

    /** {@inheritDoc} */
    @Override public void lockInterruptibly() throws IgniteInterruptedException {
        ctx.kernalContext().gateway().readLock();

        try {
            initializeReentrantLock();

            sync.acquireInterruptibly(1);

            sync.validate(true);
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
        catch (InterruptedException e) {
            if(sync.fair)
                sync.synchronizeQueue(true, Thread.currentThread());

            throw new IgniteInterruptedException(e);
        }
        finally {
            ctx.kernalContext().gateway().readUnlock();
        }
    }

    /** {@inheritDoc} */
    @Override public boolean tryLock() {
        ctx.kernalContext().gateway().readLock();

        try{
            initializeReentrantLock();

            boolean result = sync.tryAcquire(1, false);

            sync.validate(false);

            return result;
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
        finally {
            ctx.kernalContext().gateway().readUnlock();
        }
    }

    /** {@inheritDoc} */
    @Override public boolean tryLock(long timeout, TimeUnit unit) throws IgniteInterruptedException {
        ctx.kernalContext().gateway().readLock();

        try{
            initializeReentrantLock();

            boolean result = sync.tryAcquireNanos(1, unit.toNanos(timeout));

            sync.validate(true);

            return result;
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
        catch (InterruptedException e){
            if(sync.fair)
                sync.synchronizeQueue(true, Thread.currentThread());

            throw new IgniteInterruptedException(e);
        }
        finally {
            ctx.kernalContext().gateway().readUnlock();
        }
    }

    /** {@inheritDoc} */
    @Override public void unlock() {
        ctx.kernalContext().gateway().readLock();

        try{
            initializeReentrantLock();

            // Validate before release.
            sync.validate(false);

            sync.release(1);
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
        finally {
            ctx.kernalContext().gateway().readUnlock();
        }
    }

    @NotNull @Override public Condition newCondition() {
        throw new UnsupportedOperationException("IgniteLock does not allow creation of nameless conditions. ");
    }

    /** {@inheritDoc} */
    @Override public IgniteCondition getOrCreateCondition(String name) {
        ctx.kernalContext().gateway().readLock();

        try{
            initializeReentrantLock();

            IgniteCondition result = sync.newCondition(name);

            sync.validate(false);

            return result;
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
        finally {
            ctx.kernalContext().gateway().readUnlock();
        }
    }

    /** {@inheritDoc} */
    @Override public int getHoldCount() {
        try{
            initializeReentrantLock();

            return sync.getHoldCount();
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public boolean isHeldByCurrentThread() {
        try{
            initializeReentrantLock();

            return sync.isHeldExclusively();
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public boolean isLocked() {
        try{
            initializeReentrantLock();

            return sync.isLocked();
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public boolean hasQueuedThreads() {
        try{
            initializeReentrantLock();

            return sync.hasQueuedThreads();
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public boolean hasQueuedThread(Thread thread) {
        try{
            initializeReentrantLock();

            return sync.isQueued(thread);
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public boolean hasWaiters(IgniteCondition condition) {
        try{
            initializeReentrantLock();

            AbstractQueuedSynchronizer.ConditionObject c = sync.conditionMap.get(condition.name());

            if(c == null)
                throw new IllegalArgumentException();

            return sync.hasWaiters(c);
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public int getWaitQueueLength(IgniteCondition condition) {
        try{
            initializeReentrantLock();

            AbstractQueuedSynchronizer.ConditionObject c = sync.conditionMap.get(condition.name());

            if(c == null)
                throw new IllegalArgumentException();

            return sync.getWaitQueueLength(c);
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
    }

    @Override public boolean isFailoverSafe() {
        return sync.failoverSafe;
    }

    @Override public boolean isFair() {
        return sync.fair;
    }

    /** {@inheritDoc} */
    @Override public boolean isBroken() {
        try{
            initializeReentrantLock();

            return sync.isBroken();
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public GridCacheInternalKey key() {
        return key;
    }

    /** {@inheritDoc} */
    @Override public boolean removed() {
        return rmvd;
    }

    /** {@inheritDoc} */
    @Override public boolean onRemoved() {
        return rmvd = true;
    }

    /** {@inheritDoc} */
    @Override public void needCheckNotRemoved() {

    }

    /** {@inheritDoc} */
    @Override public void writeExternal(ObjectOutput out) throws IOException {
        out.writeObject(ctx.kernalContext());
        out.writeUTF(name);
    }

    /** {@inheritDoc} */
    @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        IgniteBiTuple<GridKernalContext, String> t = stash.get();

        t.set1((GridKernalContext)in.readObject());
        t.set2(in.readUTF());
    }

    /** {@inheritDoc} */
    @Override public void close() {
        if (!rmvd) {
            try {
                boolean force = sync != null ? sync.isBroken() && !sync.failoverSafe : false;

                ctx.kernalContext().dataStructures().removeReentrantLock(name, force);
            }
            catch (IgniteCheckedException e) {
                throw U.convertException(e);
            }
        }
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridCacheLockImpl.class, this);
    }
}