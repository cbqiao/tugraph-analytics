/*
 * Copyright 2023 AntGroup CO., Ltd.
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
 */

package com.antgroup.geaflow.cluster.k8s.entrypoint;

import static com.antgroup.geaflow.common.config.keys.ExecutionConfigKeys.CLUSTER_ID;

import com.antgroup.geaflow.cluster.clustermanager.ClusterInfo;
import com.antgroup.geaflow.cluster.k8s.clustermanager.GeaflowKubeClient;
import com.antgroup.geaflow.cluster.k8s.clustermanager.KubernetesClusterManager;
import com.antgroup.geaflow.cluster.k8s.clustermanager.KubernetesResourceBuilder;
import com.antgroup.geaflow.cluster.k8s.config.K8SConstants;
import com.antgroup.geaflow.cluster.k8s.config.KubernetesConfig;
import com.antgroup.geaflow.cluster.k8s.config.KubernetesMasterParam;
import com.antgroup.geaflow.cluster.k8s.utils.KubernetesUtils;
import com.antgroup.geaflow.cluster.master.AbstractMaster;
import com.antgroup.geaflow.cluster.master.MasterContext;
import com.antgroup.geaflow.cluster.master.MasterFactory;
import com.antgroup.geaflow.cluster.rpc.RpcAddress;
import com.antgroup.geaflow.common.config.Configuration;
import io.fabric8.kubernetes.api.model.ConfigMap;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is an adaptation of Flink's org.apache.flink.kubernetes.taskmanager.KubernetesTaskExecutorRunner.
 */
public class KubernetesMasterRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(KubernetesMasterRunner.class);
    private static final Map<String, String> ENV = System.getenv();
    private final Configuration config;
    private AbstractMaster master;

    public KubernetesMasterRunner(Configuration config) {
        this.config = config;
    }

    public void init() {
        master = MasterFactory.create(config);
        MasterContext context = new MasterContext(config);
        context.setClusterManager(new KubernetesClusterManager());
        context.load();
        master.init(context);
        ClusterInfo clusterInfo = master.startCluster();

        Map<String, String> updatedConfig = new HashMap<>();
        RpcAddress masterAddress = clusterInfo.getMasterAddress();
        updatedConfig.put(KubernetesConfig.MASTER_EXPOSED_ADDRESS, masterAddress.toString());

        Map<String, RpcAddress> driverAddresses = clusterInfo.getDriverAddresses();
        updatedConfig.put(KubernetesConfig.DRIVER_EXPOSED_ADDRESS,
            KubernetesUtils.encodeRpcAddressMap(driverAddresses));

        GeaflowKubeClient client = new GeaflowKubeClient(config);
        String clusterId = config.getString(CLUSTER_ID);

        KubernetesMasterParam masterParam = new KubernetesMasterParam(config);
        ConfigMap configMap = client.getConfigMap(masterParam.getConfigMapName(clusterId));
        ConfigMap updatedConfigMap = KubernetesResourceBuilder.updateConfigMap(configMap,
            updatedConfig);
        client.createOrReplaceConfigMap(updatedConfigMap);
        LOGGER.info("updated master configmap: {}", config);
    }

    private void waitForTermination() {
        LOGGER.info("waiting for finishing...");
        master.waitTermination();
    }

    public static void main(String[] args) throws IOException {
        try {
            final long startTime = System.currentTimeMillis();
            Configuration config = KubernetesUtils.loadConfigurationFromFile();
            String masterId = KubernetesUtils.getEnvValue(ENV, K8SConstants.ENV_MASTER_ID);
            config.setMasterId(masterId);
            KubernetesMasterRunner masterRunner = new KubernetesMasterRunner(config);
            masterRunner.init();
            LOGGER.info("Completed master init in {} ms", System.currentTimeMillis() - startTime);
            masterRunner.waitForTermination();
        } catch (Throwable e) {
            LOGGER.error("init master failed: {}", e.getMessage(), e);
            throw e;
        }
    }

}
