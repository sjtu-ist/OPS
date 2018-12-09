/*
 * Copyright 2018 SJTU IST Lab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.edu.sjtu.ist.ops;

import org.apache.commons.io.FileUtils;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.google.common.io.ByteSink;
import com.google.common.io.FileWriteMode;
import com.google.common.io.Files;
import com.google.gson.Gson;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.edu.sjtu.ist.ops.common.JobConf;
import cn.edu.sjtu.ist.ops.common.OpsConf;
import cn.edu.sjtu.ist.ops.common.OpsNode;
import cn.edu.sjtu.ist.ops.common.ShuffleConf;
import cn.edu.sjtu.ist.ops.common.MapConf;
import cn.edu.sjtu.ist.ops.common.TaskPreAlloc;
import cn.edu.sjtu.ist.ops.util.OpsUtils;
import cn.edu.sjtu.ist.ops.util.OpsWatcher;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

public class OpsShuffleHandler extends Thread {

    private static final Logger logger = LoggerFactory.getLogger(OpsShuffleHandler.class);
    private final Server workerServer;
    private final Server hadoopServer;
    private final OpsWatcher jobWatcher = new OpsWatcher(this, OpsUtils.ETCD_JOBS_PATH);
    private volatile boolean stopped = false;
    private final OpsConf opsConf;
    private Set<ShuffleConf> pendingShuffles = new HashSet<>();
    private Set<MapConf> pendingTasks = new HashSet<>();
    private HashMap<String, JobConf> jobs = new HashMap<>();
    private final Random random = new Random();
    private Gson gson = new Gson();

    private final ManagedChannel masterChannel;
    private final OpsInternalGrpc.OpsInternalStub masterStub;

    public OpsShuffleHandler(OpsConf opsConf) {
        this.opsConf = opsConf;
        OpsUtils.initLocalDir(this.opsConf.getDir());

        this.masterChannel = ManagedChannelBuilder.forAddress(opsConf.getMaster().getIp(), opsConf.getPortMasterGRPC())
                .usePlaintext().build();
        this.masterStub = OpsInternalGrpc.newStub(masterChannel);

        this.workerServer = ServerBuilder.forPort(this.opsConf.getPortWorkerGRPC()).addService(new OpsInternalService())
                .build();
        this.hadoopServer = ServerBuilder.forPort(this.opsConf.getPortHadoopGRPC()).addService(new OpsHadoopService())
                .build();
    }

    public void shutdown() throws InterruptedException {
        masterChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Override
    public void run() {
        this.setName("ops-shuffle-handler");
        try {
            this.workerServer.start();
            this.hadoopServer.start();
            logger.info("gRPC workerServer started, listening on " + this.opsConf.getPortWorkerGRPC());
            logger.info("gRPC hadoopServer started, listening on " + this.opsConf.getPortHadoopGRPC());
            this.jobWatcher.start();

            while (!stopped && !Thread.currentThread().isInterrupted()) {
                MapConf task = null;
                task = this.getPendingTask();
                this.taskComplete(task);
            }
            // workerServer.awaitTermination();
            // masterChannel.wait();
        } catch (Exception e) {
            // TODO: handle exception
        }
    }

    public synchronized void watcherPut(String key, String value) {
        if (key == OpsUtils.ETCD_JOBS_PATH) {
            JobConf job = gson.fromJson(value, JobConf.class);
            this.jobs.put(job.getJobId(), job);
            logger.info("Get new job: " + job.getJobId());
        }
    }

    public synchronized ShuffleConf getPendingShuffle() throws InterruptedException {
        while (pendingShuffles.isEmpty()) {
            wait();
        }

        ShuffleConf shuffle = null;
        Iterator<ShuffleConf> iter = pendingShuffles.iterator();
        int numToPick = random.nextInt(pendingShuffles.size());
        for (int i = 0; i <= numToPick; ++i) {
            shuffle = iter.next();
        }

        pendingShuffles.remove(shuffle);

        logger.debug("Get pendingShuffle " + shuffle.toString());
        return shuffle;
    }

    public synchronized MapConf getPendingTask() throws InterruptedException {
        while (pendingTasks.isEmpty()) {
            wait();
        }

        MapConf task = null;

        Iterator<MapConf> iter = pendingTasks.iterator();
        int numToPick = random.nextInt(pendingTasks.size());
        for (int i = 0; i <= numToPick; ++i) {
            task = iter.next();
        }
        pendingTasks.remove(task);

        logger.debug("Get pendingTask " + task.toString());
        return task;
    }

    public JobConf getJob(String jobId) {
        return this.jobs.get(jobId);
    }

    public synchronized void addpendingShuffles(ShuffleConf shuffle) {
        pendingShuffles.add(shuffle);
        logger.debug("Add pendingShuffles task " + shuffle.getTask().getTaskId() + " to node "
                + shuffle.getDstNode().getIp());
        notifyAll();
    }

    public synchronized void addpendingTasks(MapConf task) {
        pendingTasks.add(task);
        logger.debug("Add pendingTasks task " + task.getTaskId() + " to node " + task.getOpsNode().getIp());
        notifyAll();
    }

    public void taskComplete(MapConf task) {
        StreamObserver<MapMessage> requestObserver = masterStub.onMapComplete(new StreamObserver<MapMessage>() {
            @Override
            public void onNext(MapMessage msg) {
                logger.debug("ShuffleHandler: " + msg.getMapConf());
            }

            @Override
            public void onError(Throwable t) {

            }

            @Override
            public void onCompleted() {

            }
        });

        try {
            Gson gson = new Gson();
            MapMessage message = MapMessage.newBuilder().setMapConf(gson.toJson(task)).build();
            requestObserver.onNext(message);
        } catch (RuntimeException e) {
            // Cancel RPC
            requestObserver.onError(e);
            throw e;
        }
        // Mark the end of requests
        requestObserver.onCompleted();
    }

    private class OpsHadoopService extends HadoopOpsGrpc.HadoopOpsImplBase {
        @Override
        public void notify(HadoopMessage request, StreamObserver<Empty> responseObserver) {

            OpsNode node = new OpsNode(request.getIp());
            MapConf task = new MapConf(request.getTaskId(), request.getJobId(), node, request.getPath(),
                    request.getIndexPath());

            addpendingTasks(task);

            Empty empty = Empty.newBuilder().build();
            responseObserver.onNext(empty);
            responseObserver.onCompleted();
        }
    }

    private class OpsInternalService extends OpsInternalGrpc.OpsInternalImplBase {
        @Override
        public StreamObserver<Chunk> transfer(StreamObserver<StatusMessage> responseObserver) {
            return new StreamObserver<Chunk>() {
                @Override
                public void onNext(Chunk chunk) {
                    try {
                        String path = chunk.getPath();
                        File file = new File(opsConf.getDir(), path);
                        if (!file.exists()) {
                            FileUtils.forceMkdirParent(file);
                            file.createNewFile();
                            logger.debug("mkdir & create file for shuffle data: " + file.toString());
                        }
                        ByteSink byteSink = Files.asByteSink(file, FileWriteMode.APPEND);
                        byteSink.write(chunk.getContent().toByteArray());
                        logger.debug("Receive chunk: {Path: " + file.toString() + ", Length: " + file.length() + "}");
                    } catch (Exception e) {
                        e.printStackTrace();
                        // TODO: handle exception
                    }
                }

                @Override
                public void onError(Throwable t) {
                    logger.warn("Encountered error in exchange", t);
                }

                @Override
                public void onCompleted() {
                    responseObserver.onCompleted();
                }
            };
        }

        @Override
        public StreamObserver<ShuffleMessage> onShuffle(StreamObserver<ShuffleMessage> responseObserver) {
            return new StreamObserver<ShuffleMessage>() {
                @Override
                public void onNext(ShuffleMessage request) {
                    // responseObserver.onNext(ShuffleMessage.newBuilder().setMsg("ShuffleMessage").build());

                    Gson gson = new Gson();
                    MapConf task = gson.fromJson(request.getMapConf(), MapConf.class);
                    if (!jobs.containsKey(task.getJobId())) {
                        logger.error("JobId not found: " + task.getJobId());
                        return;
                    }
                    JobConf job = jobs.get(task.getJobId());
                    TaskPreAlloc preAlloc = job.getReducePreAlloc();
                    for (OpsNode node : preAlloc.getNodesMap().values()) {
                        for (Integer num : preAlloc.getTaskOrder(node.getIp())) {
                            ShuffleConf shuffle = new ShuffleConf(task, node, num);
                            addpendingShuffles(shuffle);
                        }
                    }
                }

                @Override
                public void onError(Throwable t) {
                    logger.warn("Encountered error in exchange", t);
                }

                @Override
                public void onCompleted() {
                    responseObserver.onCompleted();
                }
            };
        }
    }
}