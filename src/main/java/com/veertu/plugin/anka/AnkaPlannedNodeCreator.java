package com.veertu.plugin.anka;

import com.veertu.ankaMgmtSdk.AnkaVmInfo;
import com.veertu.ankaMgmtSdk.AnkaVmInstance;
import com.veertu.ankaMgmtSdk.exceptions.AnkaMgmtException;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.slaves.NodeProvisioner;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by asafgur on 16/11/2016.
 */
public class AnkaPlannedNodeCreator {

    private static int connectionAttemps = 10;

    private static final transient Logger LOGGER = Logger.getLogger(AnkaPlannedNodeCreator.class.getName());


    public static NodeProvisioner.PlannedNode createPlannedNode(final AnkaMgmtCloud cloud, final AnkaCloudSlaveTemplate template, final AbstractAnkaSlave slave) {
        return new NodeProvisioner.PlannedNode(template.getDisplayName(),
                Computer.threadPoolForRemoting.submit(new Callable<Node>() {


                    public Node call() throws Exception {
                        return waitAndConnect(cloud, template, slave);
                    }
                })
                , template.getNumberOfExecutors());
    }

    public static Node waitAndConnect(final AnkaMgmtCloud cloud, final AnkaCloudSlaveTemplate template, final AbstractAnkaSlave slave) throws AnkaMgmtException, InterruptedException {
        final long timeStarted = System.currentTimeMillis();
        while (true) {
            String instanceId = slave.getInstanceId();

            AnkaVmInstance instance = cloud.showInstance(instanceId);
            if (instance == null) {
                LOGGER.log(Level.WARNING, "instance `{0}` not found in cloud {1}. Terminate provisioning ",
                        new Object[]{instanceId, cloud.getCloudName()});
                return null;
            }

            if (instance.isStarted()) {
                AnkaVmInfo vmInfo = instance.getVmInfo();
                if (vmInfo == null) { // shouldn't happen if vm is Started
                    Thread.sleep(5000);
                    continue;
                }
                String hostIp = vmInfo.getHostIp();
                if (hostIp == null || hostIp.isEmpty()) { // the node doesn't have an ip yet
                    Thread.sleep(2000);
                    continue;
                }
                try {
                    for (int i = 0 ; i < connectionAttemps; i++){
                        Computer computer = slave.toComputer();
                        if ( computer != null ) {
                            AnkaCloudComputer ankaComputer = (AnkaCloudComputer) computer;
                            Thread.sleep(2000);
                            Future<?> connect = ankaComputer.connect(false);
                            try {
                                connect.get();
                                break;
                            } catch (ExecutionException e) {
                                LOGGER.log(Level.INFO, "instance `{0}` failed to connect #{1} ",
                                        new Object[]{instanceId, i});
                                Thread.sleep(1500);
                            }

                        }
                    }
                } finally {
                    AnkaCloudComputer ankaComputer = (AnkaCloudComputer)slave.toComputer();
                    if (ankaComputer != null) {
                        ankaComputer.firstConnectionAttempted();
                    }
                }

                return slave;

            }

            if (instance.isPulling()) {
                Thread.sleep(5000);
                continue;
            }

            if (instance.isScheduling()) {
                final long sinceStarted = System.currentTimeMillis() - timeStarted;
//                int secondsScheduling = (int)((System.currentTimeMillis() - timeStarted) * 1000);

                int schedulingTimeout = template.getSchedulingTimeout();
                long schedulingTimeoutMillis = TimeUnit.SECONDS.toMillis(schedulingTimeout);
                LOGGER.log(Level.INFO,"Instance {0} is scheduling for {1} seconds",
                            new Object[]{instanceId, sinceStarted * 1000});
                if (sinceStarted > schedulingTimeoutMillis) {
                    LOGGER.log(Level.WARNING,"Instance {0} reached it's scheduling timeout of {1} seconds, terminating provisioning",
                            new Object[]{instanceId, schedulingTimeout});
                    cloud.terminateVMInstance(instanceId);
                    return null;
                }
                Thread.sleep(5000);
                continue;
            }

            if (instance.isTerminatingOrTerminated() || instance.isInError()) {
                LOGGER.log(Level.WARNING,"Instance {0} is in unexpected state {1}",
                        new Object[]{instanceId, instance.getSessionState()});
                cloud.terminateVMInstance(instanceId);
                return null;
            }
        }
    }
}
