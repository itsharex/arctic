/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.server.optimizing;

import static com.netease.arctic.server.optimizing.OptimizerGroupMetrics.GROUP_TAG;
import static com.netease.arctic.server.optimizing.OptimizerGroupMetrics.OPTIMIZER_GROUP_EXECUTING_TABLES;
import static com.netease.arctic.server.optimizing.OptimizerGroupMetrics.OPTIMIZER_GROUP_EXECUTING_TASKS;
import static com.netease.arctic.server.optimizing.OptimizerGroupMetrics.OPTIMIZER_GROUP_MEMORY_BYTES_ALLOCATED;
import static com.netease.arctic.server.optimizing.OptimizerGroupMetrics.OPTIMIZER_GROUP_OPTIMIZER_INSTANCES;
import static com.netease.arctic.server.optimizing.OptimizerGroupMetrics.OPTIMIZER_GROUP_PENDING_TABLES;
import static com.netease.arctic.server.optimizing.OptimizerGroupMetrics.OPTIMIZER_GROUP_PENDING_TASKS;
import static com.netease.arctic.server.optimizing.OptimizerGroupMetrics.OPTIMIZER_GROUP_PLANING_TABLES;
import static com.netease.arctic.server.optimizing.OptimizerGroupMetrics.OPTIMIZER_GROUP_THREADS;

import com.google.common.collect.ImmutableMap;
import com.netease.arctic.BasicTableTestHelper;
import com.netease.arctic.TableTestHelper;
import com.netease.arctic.ams.api.OptimizerRegisterInfo;
import com.netease.arctic.ams.api.OptimizingTaskId;
import com.netease.arctic.ams.api.OptimizingTaskResult;
import com.netease.arctic.ams.api.TableFormat;
import com.netease.arctic.ams.api.metrics.Gauge;
import com.netease.arctic.ams.api.metrics.MetricKey;
import com.netease.arctic.ams.api.resource.ResourceGroup;
import com.netease.arctic.catalog.BasicCatalogTestHelper;
import com.netease.arctic.catalog.CatalogTestHelper;
import com.netease.arctic.io.MixedDataTestHelpers;
import com.netease.arctic.optimizing.RewriteFilesOutput;
import com.netease.arctic.optimizing.TableOptimizing;
import com.netease.arctic.server.manager.MetricManager;
import com.netease.arctic.server.metrics.MetricRegistry;
import com.netease.arctic.server.resource.OptimizerInstance;
import com.netease.arctic.server.resource.OptimizerThread;
import com.netease.arctic.server.resource.QuotaProvider;
import com.netease.arctic.server.table.AMSTableTestBase;
import com.netease.arctic.server.table.TableConfiguration;
import com.netease.arctic.server.table.TableRuntime;
import com.netease.arctic.server.table.TableRuntimeMeta;
import com.netease.arctic.table.ArcticTable;
import com.netease.arctic.table.TableProperties;
import com.netease.arctic.table.UnkeyedTable;
import com.netease.arctic.utils.SerializationUtil;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@RunWith(Parameterized.class)
public class TestOptimizingQueue extends AMSTableTestBase {

  private final Executor planExecutor = Executors.newSingleThreadExecutor();
  private final QuotaProvider quotaProvider = resourceGroup -> 1;
  private final long MAX_POLLING_TIME = 5000;

  private final OptimizerThread optimizerThread =
      new OptimizerThread(1, null) {

        @Override
        public String getToken() {
          return "aah";
        }
      };

  public TestOptimizingQueue(CatalogTestHelper catalogTestHelper, TableTestHelper tableTestHelper) {
    super(catalogTestHelper, tableTestHelper, true);
  }

  @Parameterized.Parameters(name = "{0}, {1}")
  public static Object[] parameters() {
    return new Object[][] {
      {new BasicCatalogTestHelper(TableFormat.ICEBERG), new BasicTableTestHelper(false, true)}
    };
  }

  protected static ResourceGroup testResourceGroup() {
    return new ResourceGroup.Builder("test", "local").build();
  }

  protected OptimizingQueue buildOptimizingGroupService(TableRuntimeMeta tableRuntimeMeta) {
    return new OptimizingQueue(
        tableService(),
        testResourceGroup(),
        quotaProvider,
        planExecutor,
        Collections.singletonList(tableRuntimeMeta),
        1);
  }

  private OptimizingQueue buildOptimizingGroupService() {
    return new OptimizingQueue(
        tableService(),
        testResourceGroup(),
        quotaProvider,
        planExecutor,
        Collections.emptyList(),
        1);
  }

  @Test
  public void testPollNoTask() {
    TableRuntimeMeta tableRuntimeMeta =
        buildTableRuntimeMeta(OptimizingStatus.PENDING, defaultResourceGroup());
    OptimizingQueue queue = buildOptimizingGroupService(tableRuntimeMeta);
    Assert.assertNull(queue.pollTask(0));
    queue.dispose();
  }

  @Test
  public void testRefreshAndReleaseTable() {
    OptimizingQueue queue = buildOptimizingGroupService();
    Assert.assertEquals(0, queue.getSchedulingPolicy().getTableRuntimeMap().size());
    TableRuntimeMeta tableRuntimeMeta =
        buildTableRuntimeMeta(OptimizingStatus.IDLE, defaultResourceGroup());
    queue.refreshTable(tableRuntimeMeta.getTableRuntime());
    Assert.assertEquals(1, queue.getSchedulingPolicy().getTableRuntimeMap().size());
    Assert.assertTrue(
        queue.getSchedulingPolicy().getTableRuntimeMap().containsKey(serverTableIdentifier()));

    queue.releaseTable(tableRuntimeMeta.getTableRuntime());
    Assert.assertEquals(0, queue.getSchedulingPolicy().getTableRuntimeMap().size());

    queue.refreshTable(tableRuntimeMeta.getTableRuntime());
    Assert.assertEquals(1, queue.getSchedulingPolicy().getTableRuntimeMap().size());
    queue.dispose();
  }

  @Test
  public void testPollTask() {
    TableRuntimeMeta tableRuntimeMeta = initTableWithFiles();
    OptimizingQueue queue = buildOptimizingGroupService(tableRuntimeMeta);

    // 1.poll task
    TaskRuntime task = queue.pollTask(MAX_POLLING_TIME);

    Assert.assertNotNull(task);
    Assert.assertEquals(TaskRuntime.Status.PLANNED, task.getStatus());
    Assert.assertNull(queue.pollTask(0));
    queue.dispose();
  }

  @Test
  public void testRetryTask() {
    TableRuntimeMeta tableRuntimeMeta = initTableWithFiles();
    OptimizingQueue queue = buildOptimizingGroupService(tableRuntimeMeta);

    // 1.poll task
    TaskRuntime task = queue.pollTask(MAX_POLLING_TIME);
    Assert.assertNotNull(task);

    for (int i = 0; i < TableProperties.SELF_OPTIMIZING_EXECUTE_RETRY_NUMBER_DEFAULT; i++) {
      queue.retryTask(task);
      TaskRuntime retryTask = queue.pollTask(MAX_POLLING_TIME);
      Assert.assertEquals(retryTask.getTaskId(), task.getTaskId());
      retryTask.schedule(optimizerThread);
      retryTask.ack(optimizerThread);
      retryTask.complete(
          optimizerThread,
          buildOptimizingTaskFailed(task.getTaskId(), optimizerThread.getThreadId()));
      Assert.assertEquals(TaskRuntime.Status.PLANNED, task.getStatus());
    }

    queue.retryTask(task);
    TaskRuntime retryTask = queue.pollTask(MAX_POLLING_TIME);
    Assert.assertEquals(retryTask.getTaskId(), task.getTaskId());
    retryTask.schedule(optimizerThread);
    retryTask.ack(optimizerThread);
    retryTask.complete(
        optimizerThread,
        buildOptimizingTaskFailed(task.getTaskId(), optimizerThread.getThreadId()));
    Assert.assertEquals(TaskRuntime.Status.FAILED, task.getStatus());
    queue.dispose();
  }

  @Test
  public void testCommitTask() {
    TableRuntimeMeta tableRuntimeMeta = initTableWithFiles();
    OptimizingQueue queue = buildOptimizingGroupService(tableRuntimeMeta);
    Assert.assertEquals(0, queue.collectTasks().size());

    TaskRuntime task = queue.pollTask(MAX_POLLING_TIME);
    task.schedule(optimizerThread);
    task.ack(optimizerThread);
    Assert.assertEquals(
        1, queue.collectTasks(t -> t.getStatus() == TaskRuntime.Status.ACKED).size());
    Assert.assertNotNull(task);
    task.complete(
        optimizerThread,
        buildOptimizingTaskResult(task.getTaskId(), optimizerThread.getThreadId()));
    Assert.assertEquals(TaskRuntime.Status.SUCCESS, task.getStatus());

    // 7.commit
    OptimizingProcess optimizingProcess = tableRuntimeMeta.getTableRuntime().getOptimizingProcess();
    Assert.assertEquals(OptimizingProcess.Status.RUNNING, optimizingProcess.getStatus());
    optimizingProcess.commit();
    Assert.assertEquals(OptimizingProcess.Status.SUCCESS, optimizingProcess.getStatus());
    Assert.assertNull(tableRuntimeMeta.getTableRuntime().getOptimizingProcess());

    // 8.commit again
    optimizingProcess.commit();
    Assert.assertEquals(OptimizingProcess.Status.FAILED, optimizingProcess.getStatus());

    // 9.close
    optimizingProcess.close();
    Assert.assertEquals(OptimizingProcess.Status.CLOSED, optimizingProcess.getStatus());

    Assert.assertEquals(0, queue.collectTasks().size());
    queue.dispose();
  }

  @Test
  public void testCollectingTasks() {
    TableRuntimeMeta tableRuntimeMeta = initTableWithFiles();
    OptimizingQueue queue = buildOptimizingGroupService(tableRuntimeMeta);
    Assert.assertEquals(0, queue.collectTasks().size());

    TaskRuntime task = queue.pollTask(MAX_POLLING_TIME);
    Assert.assertNotNull(task);
    task.schedule(optimizerThread);
    Assert.assertEquals(1, queue.collectTasks().size());
    Assert.assertEquals(
        1, queue.collectTasks(t -> t.getStatus() == TaskRuntime.Status.SCHEDULED).size());
    queue.dispose();
  }

  @Test
  public void testTaskAndTableMetrics() {
    TableRuntimeMeta tableRuntimeMeta = initTableWithFiles();
    OptimizingQueue queue = buildOptimizingGroupService(tableRuntimeMeta);
    MetricRegistry registry = MetricManager.getInstance().getGlobalRegistry();
    Map<String, String> tagValues = ImmutableMap.of(GROUP_TAG, testResourceGroup().getName());

    Gauge<Integer> queueTasksGauge =
        (Gauge<Integer>)
            registry.getMetrics().get(new MetricKey(OPTIMIZER_GROUP_PENDING_TASKS, tagValues));
    Gauge<Integer> executingTasksGauge =
        (Gauge<Integer>)
            registry.getMetrics().get(new MetricKey(OPTIMIZER_GROUP_EXECUTING_TASKS, tagValues));
    Gauge<Long> planingTablesGauge =
        (Gauge<Long>)
            registry.getMetrics().get(new MetricKey(OPTIMIZER_GROUP_PLANING_TABLES, tagValues));
    Gauge<Long> pendingTablesGauge =
        (Gauge<Long>)
            registry.getMetrics().get(new MetricKey(OPTIMIZER_GROUP_PENDING_TABLES, tagValues));
    Gauge<Long> executingTablesGauge =
        (Gauge<Long>)
            registry.getMetrics().get(new MetricKey(OPTIMIZER_GROUP_EXECUTING_TABLES, tagValues));

    Assert.assertEquals(0, queueTasksGauge.getValue().longValue());
    Assert.assertEquals(0, executingTasksGauge.getValue().longValue());
    Assert.assertEquals(0, planingTablesGauge.getValue().longValue());
    Assert.assertEquals(1, pendingTablesGauge.getValue().longValue());
    Assert.assertEquals(0, executingTablesGauge.getValue().longValue());

    TaskRuntime task = queue.pollTask(MAX_POLLING_TIME);
    Assert.assertNotNull(task);
    task.schedule(optimizerThread);
    Assert.assertEquals(1, queueTasksGauge.getValue().longValue());
    Assert.assertEquals(0, executingTasksGauge.getValue().longValue());
    Assert.assertEquals(0, planingTablesGauge.getValue().longValue());
    Assert.assertEquals(0, pendingTablesGauge.getValue().longValue());
    Assert.assertEquals(1, executingTablesGauge.getValue().longValue());

    task.ack(optimizerThread);
    Assert.assertEquals(0, queueTasksGauge.getValue().longValue());
    Assert.assertEquals(1, executingTasksGauge.getValue().longValue());
    Assert.assertEquals(0, planingTablesGauge.getValue().longValue());
    Assert.assertEquals(0, pendingTablesGauge.getValue().longValue());
    Assert.assertEquals(1, executingTablesGauge.getValue().longValue());

    task.complete(
        optimizerThread,
        buildOptimizingTaskResult(task.getTaskId(), optimizerThread.getThreadId()));
    Assert.assertEquals(0, queueTasksGauge.getValue().longValue());
    Assert.assertEquals(0, executingTasksGauge.getValue().longValue());
    Assert.assertEquals(0, planingTablesGauge.getValue().longValue());
    Assert.assertEquals(0, pendingTablesGauge.getValue().longValue());
    Assert.assertEquals(1, executingTablesGauge.getValue().longValue());

    OptimizingProcess optimizingProcess = tableRuntimeMeta.getTableRuntime().getOptimizingProcess();
    optimizingProcess.commit();
    Assert.assertEquals(0, queueTasksGauge.getValue().longValue());
    Assert.assertEquals(0, executingTasksGauge.getValue().longValue());
    Assert.assertEquals(0, planingTablesGauge.getValue().longValue());
    Assert.assertEquals(0, pendingTablesGauge.getValue().longValue());
    Assert.assertEquals(0, executingTablesGauge.getValue().longValue());
    queue.dispose();
  }

  @Test
  public void testAddAndRemoveOptimizers() {

    OptimizingQueue queue = buildOptimizingGroupService();
    MetricRegistry registry = MetricManager.getInstance().getGlobalRegistry();
    Map<String, String> tagValues = ImmutableMap.of(GROUP_TAG, testResourceGroup().getName());
    OptimizerRegisterInfo optimizerRegisterInfo =
        new OptimizerRegisterInfo(
            2, 2048, System.currentTimeMillis(), testResourceGroup().getName());
    OptimizerInstance optimizer = new OptimizerInstance(optimizerRegisterInfo, "test_container");

    Gauge<Integer> optimizerCountGauge =
        (Gauge<Integer>)
            registry
                .getMetrics()
                .get(new MetricKey(OPTIMIZER_GROUP_OPTIMIZER_INSTANCES, tagValues));
    Gauge<Long> optimizerMemoryGauge =
        (Gauge<Long>)
            registry
                .getMetrics()
                .get(new MetricKey(OPTIMIZER_GROUP_MEMORY_BYTES_ALLOCATED, tagValues));
    Gauge<Long> optimizerThreadsGauge =
        (Gauge<Long>) registry.getMetrics().get(new MetricKey(OPTIMIZER_GROUP_THREADS, tagValues));

    queue.addOptimizer(optimizer);
    Assert.assertEquals(1, optimizerCountGauge.getValue().longValue());
    Assert.assertEquals((long) 2048 * 1024 * 1024, optimizerMemoryGauge.getValue().longValue());
    Assert.assertEquals(2, optimizerThreadsGauge.getValue().longValue());

    queue.removeOptimizer(optimizer);
    Assert.assertEquals(0, optimizerCountGauge.getValue().longValue());
    Assert.assertEquals(0, optimizerMemoryGauge.getValue().longValue());
    Assert.assertEquals(0, optimizerThreadsGauge.getValue().longValue());
    queue.dispose();
  }

  protected TableRuntimeMeta initTableWithFiles() {
    ArcticTable arcticTable =
        (ArcticTable) tableService().loadTable(serverTableIdentifier()).originalTable();
    appendData(arcticTable.asUnkeyedTable(), 1);
    appendData(arcticTable.asUnkeyedTable(), 2);
    TableRuntimeMeta tableRuntimeMeta =
        buildTableRuntimeMeta(OptimizingStatus.PENDING, defaultResourceGroup());
    TableRuntime runtime = tableRuntimeMeta.getTableRuntime();

    runtime.refresh(tableService().loadTable(serverTableIdentifier()));
    return tableRuntimeMeta;
  }

  private TableRuntimeMeta buildTableRuntimeMeta(
      OptimizingStatus status, ResourceGroup resourceGroup) {
    ArcticTable arcticTable =
        (ArcticTable) tableService().loadTable(serverTableIdentifier()).originalTable();
    TableRuntimeMeta tableRuntimeMeta = new TableRuntimeMeta();
    tableRuntimeMeta.setCatalogName(serverTableIdentifier().getCatalog());
    tableRuntimeMeta.setDbName(serverTableIdentifier().getDatabase());
    tableRuntimeMeta.setTableName(serverTableIdentifier().getTableName());
    tableRuntimeMeta.setTableId(serverTableIdentifier().getId());
    tableRuntimeMeta.setFormat(TableFormat.ICEBERG);
    tableRuntimeMeta.setTableStatus(status);
    tableRuntimeMeta.setTableConfig(TableConfiguration.parseConfig(arcticTable.properties()));
    tableRuntimeMeta.setOptimizerGroup(resourceGroup.getName());
    tableRuntimeMeta.constructTableRuntime(tableService());
    return tableRuntimeMeta;
  }

  private void appendData(UnkeyedTable table, int id) {
    ArrayList<Record> newRecords =
        Lists.newArrayList(
            MixedDataTestHelpers.createRecord(
                table.schema(), id, "111", 0L, "2022-01-01T12:00:00"));
    List<DataFile> dataFiles = MixedDataTestHelpers.writeBaseStore(table, 0L, newRecords, false);
    AppendFiles appendFiles = table.newAppend();
    dataFiles.forEach(appendFiles::appendFile);
    appendFiles.commit();
  }

  private OptimizingTaskResult buildOptimizingTaskResult(OptimizingTaskId taskId, int threadId) {
    TableOptimizing.OptimizingOutput output = new RewriteFilesOutput(null, null, null);
    OptimizingTaskResult optimizingTaskResult = new OptimizingTaskResult(taskId, threadId);
    optimizingTaskResult.setTaskOutput(SerializationUtil.simpleSerialize(output));
    return optimizingTaskResult;
  }

  private OptimizingTaskResult buildOptimizingTaskFailed(OptimizingTaskId taskId, int threadId) {
    OptimizingTaskResult optimizingTaskResult = new OptimizingTaskResult(taskId, threadId);
    optimizingTaskResult.setErrorMessage("error");
    return optimizingTaskResult;
  }
}
