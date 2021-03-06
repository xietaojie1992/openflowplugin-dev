/**
 * Copyright (c) 2015 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.openflowplugin.impl.device;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.CheckedFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import org.opendaylight.controller.md.sal.binding.api.BindingTransactionChain;
import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.controller.md.sal.binding.api.WriteTransaction;
import org.opendaylight.controller.md.sal.common.api.data.AsyncTransaction;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.api.data.TransactionChain;
import org.opendaylight.controller.md.sal.common.api.data.TransactionChainListener;
import org.opendaylight.controller.md.sal.common.api.data.TransactionCommitFailedException;
import org.opendaylight.openflowplugin.api.openflow.device.DeviceState;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.Node;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.NodeKey;
import org.opendaylight.yangtools.yang.binding.DataObject;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.opendaylight.yangtools.yang.binding.KeyedInstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * openflowplugin-impl
 * org.opendaylight.openflowplugin.impl.device
 * <p/>
 * Package protected class for controlling {@link WriteTransaction} life cycle. It is
 * a {@link TransactionChainListener} and provide package protected methods for writeToTransaction
 * method (wrapped {@link WriteTransaction#put(LogicalDatastoreType, InstanceIdentifier, DataObject)})
 * and submitTransaction method (wrapped {@link WriteTransaction#submit()})
 *
 * @author <a href="mailto:vdemcak@cisco.com">Vaclav Demcak</a>
 *         </p>
 *         Created: Apr 2, 2015
 */
class TransactionChainManager implements TransactionChainListener, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionChainManager.class);

    private final Object txLock = new Object();

    private final DataBroker dataBroker;
    private final DeviceState deviceState;
    @GuardedBy("txLock")
    private WriteTransaction wTx;
    @GuardedBy("txLock")
    private BindingTransactionChain txChainFactory;
    @GuardedBy("txLock")
    private boolean submitIsEnabled;
    @GuardedBy("txLock")
    private ListenableFuture<Void> lastSubmittedFuture;

    public TransactionChainManagerStatus getTransactionChainManagerStatus() {
        return transactionChainManagerStatus;
    }

    @GuardedBy("txLock")
    private TransactionChainManagerStatus transactionChainManagerStatus;
    private final KeyedInstanceIdentifier<Node, NodeKey> nodeII;

    TransactionChainManager(@Nonnull final DataBroker dataBroker,
                            @Nonnull final DeviceState deviceState) {
        this.dataBroker = Preconditions.checkNotNull(dataBroker);
        this.deviceState = Preconditions.checkNotNull(deviceState);
        this.nodeII = Preconditions.checkNotNull(deviceState.getNodeInstanceIdentifier());
        this.transactionChainManagerStatus = TransactionChainManagerStatus.SLEEPING;
        lastSubmittedFuture = Futures.immediateFuture(null);
        LOG.debug("created txChainManager");
    }

    @GuardedBy("txLock")
    private void createTxChain() {
        if (txChainFactory != null) {
            txChainFactory.close();
        }
        txChainFactory = dataBroker.createTransactionChain(TransactionChainManager.this);
    }

    void initialSubmitWriteTransaction() {
        enableSubmit();
        submitWriteTransaction();
    }

    /**
     * Method change status for TxChainManager to {@link TransactionChainManagerStatus#WORKING} and it has to make
     * registration for this class instance as {@link TransactionChainListener} to provide possibility a make DS
     * transactions. Call this method for MASTER role only.
     */
    public void activateTransactionManager() {
        LOG.trace("activateTransactionManager for node {} transaction submit is set to {}", deviceState.getNodeId(), this.submitIsEnabled);
        synchronized (txLock) {
            if (TransactionChainManagerStatus.SLEEPING.equals(transactionChainManagerStatus)) {
                LOG.debug("Transaction Factory create {}", deviceState.getNodeId());
                Preconditions.checkState(txChainFactory == null, "TxChainFactory survive last close.");
                Preconditions.checkState(wTx == null, "We have some unexpected WriteTransaction.");
                this.transactionChainManagerStatus = TransactionChainManagerStatus.WORKING;
                this.submitIsEnabled = false;
                createTxChain();
            } else {
                LOG.debug("Transaction is active {}", deviceState.getNodeId());
            }
        }
    }

    /**
     * Method change status for TxChainManger to {@link TransactionChainManagerStatus#SLEEPING} and it unregisters
     * this class instance as {@link TransactionChainListener} so it broke a possibility to write something to DS.
     * Call this method for SLAVE only.
     * @return Future
     */
    public ListenableFuture<Void> deactivateTransactionManager() {
        final ListenableFuture<Void> future;
        synchronized (txLock) {
            if (TransactionChainManagerStatus.WORKING.equals(transactionChainManagerStatus)) {
                LOG.debug("Submitting all transactions if we were in status WORKING for Node", deviceState.getNodeId());
                transactionChainManagerStatus = TransactionChainManagerStatus.SLEEPING;
                future = txChainShuttingDown();
                Preconditions.checkState(wTx == null, "We have some unexpected WriteTransaction.");
                LOG.debug("Transaction Factory deactivate for Node {}", deviceState.getNodeId());
                Futures.addCallback(future, new FutureCallback<Void>() {
                    @Override
                    public void onSuccess(final Void result) {
                        txChainFactory.close();
                        txChainFactory = null;
                    }

                    @Override
                    public void onFailure(final Throwable t) {
                        txChainFactory.close();
                        txChainFactory = null;
                    }
                });
            } else {
                // TODO : ignoring redundant deactivate invocation
                future = Futures.immediateCheckedFuture(null);
            }
        }
        return future;
    }

    boolean submitWriteTransaction() {
        synchronized (txLock) {
            if (!submitIsEnabled) {
                LOG.trace("transaction not committed - submit block issued");
                return false;
            }
            if (wTx == null) {
                LOG.trace("nothing to commit - submit returns true");
                return true;
            }
            Preconditions.checkState(TransactionChainManagerStatus.WORKING.equals(transactionChainManagerStatus),
                    "we have here Uncompleted Transaction for node {} and we are not MASTER", nodeII);
            final CheckedFuture<Void, TransactionCommitFailedException> submitFuture = wTx.submit();
            Futures.addCallback(submitFuture, new FutureCallback<Void>() {
                @Override
                public void onSuccess(final Void result) {
                    //no action required
                }

                @Override
                public void onFailure(final Throwable t) {
                    if (t instanceof TransactionCommitFailedException) {
                        LOG.error("Transaction commit failed. {}", t);
                    } else {
                        LOG.error("Exception during transaction submitting. {}", t);
                    }
                }
            });
            lastSubmittedFuture = submitFuture;
            wTx = null;
        }
        return true;
    }

    <T extends DataObject> void addDeleteOperationTotTxChain(final LogicalDatastoreType store,
                                                             final InstanceIdentifier<T> path) throws Exception {
        final WriteTransaction writeTx = getTransactionSafely();
        if (writeTx != null) {
            LOG.trace("addDeleteOperation called with path {} ", path);
            writeTx.delete(store, path);
        } else {
            LOG.debug("WriteTx is null for node {}. Delete {} was not realized.", nodeII, path);
            throw new Exception("Cannot write into transaction.");
        }
    }

    <T extends DataObject> void writeToTransaction(final LogicalDatastoreType store,
                                                   final InstanceIdentifier<T> path, final T data) throws Exception {
        final WriteTransaction writeTx = getTransactionSafely();
        if (writeTx != null) {
            LOG.trace("writeToTransaction called with path {} ", path);
            writeTx.put(store, path, data);
        } else {
            LOG.debug("WriteTx is null for node {}. Write data for {} was not realized.", nodeII, path);
            throw new Exception("Cannot write into transaction.");
        }
    }

    @Override
    public void onTransactionChainFailed(final TransactionChain<?, ?> chain,
                                         final AsyncTransaction<?, ?> transaction, final Throwable cause) {
        if (transactionChainManagerStatus.equals(TransactionChainManagerStatus.WORKING)) {
            LOG.warn("txChain failed -> recreating", cause);
            recreateTxChain();
        }
    }

    @Override
    public void onTransactionChainSuccessful(final TransactionChain<?, ?> chain) {
        // NOOP
    }

    private void recreateTxChain() {
        synchronized (txLock) {
            createTxChain();
            wTx = null;
        }
    }

    @Nullable
    private WriteTransaction getTransactionSafely() {
        if (wTx == null && TransactionChainManagerStatus.WORKING.equals(transactionChainManagerStatus)) {
            synchronized (txLock) {
                if (wTx == null && TransactionChainManagerStatus.WORKING.equals(transactionChainManagerStatus)) {
                    if (wTx == null && txChainFactory != null) {
                        wTx = txChainFactory.newWriteOnlyTransaction();
                    }
                }
            }
        }
        return wTx;
    }

    @VisibleForTesting
    void enableSubmit() {
        synchronized (txLock) {
            /* !!!IMPORTANT: never set true without txChainFactory */
            submitIsEnabled = txChainFactory != null;
        }
    }

    ListenableFuture<Void> shuttingDown() {
        LOG.debug("TxManager is going SHUTTING_DOWN for node {}", nodeII);
        ListenableFuture<Void> future;
        synchronized (txLock) {
            this.transactionChainManagerStatus = TransactionChainManagerStatus.SHUTTING_DOWN;
            future = txChainShuttingDown();
        }
        return future;
    }

    @GuardedBy("txLock")
    private ListenableFuture<Void> txChainShuttingDown() {
        submitIsEnabled = false;
        ListenableFuture<Void> future;
        if (txChainFactory == null) {
            // stay with actual thread
            future = Futures.immediateCheckedFuture(null);
        } else if (wTx == null) {
            // hijack md-sal thread
            future = lastSubmittedFuture;
        } else {
            // hijack md-sal thread
            future = wTx.submit();
            wTx = null;
        }
        return future;
    }

    @Override
    public void close() {
        LOG.debug("Setting transactionChainManagerStatus to SHUTTING_DOWN, will wait for ownershipservice to notify", nodeII);
        Preconditions.checkState(TransactionChainManagerStatus.SHUTTING_DOWN.equals(transactionChainManagerStatus));
        Preconditions.checkState(wTx == null);
        synchronized (txLock) {
            if (txChainFactory != null) {
                txChainFactory.close();
                txChainFactory = null;
            }
        }
        Preconditions.checkState(txChainFactory == null);
    }

    public enum TransactionChainManagerStatus {
        /** txChainManager is sleeping - is not active (SLAVE or default init value) */
        WORKING,
        /** txChainManager is working - is active (MASTER) */
        SLEEPING,
        /** txChainManager is trying to be closed - device disconnecting */
        SHUTTING_DOWN;
    }
}
