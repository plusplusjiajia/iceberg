/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.spark;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.ParameterizedTestExtension;
import org.apache.iceberg.Table;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableSet;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.spark.source.SimpleRecord;
import org.apache.iceberg.util.DataFileSet;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.catalyst.analysis.NoSuchTableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ParameterizedTestExtension.class)
public class TestFileRewriteCoordinator extends CatalogTestBase {

  @AfterEach
  public void removeTables() {
    sql("DROP TABLE IF EXISTS %s", tableName);
  }

  @TestTemplate
  public void testBinPackRewrite() throws NoSuchTableException, IOException {
    sql("CREATE TABLE %s (id INT, data STRING) USING iceberg", tableName);

    Dataset<Row> df = newDF(1000);
    df.coalesce(1).writeTo(tableName).append();
    df.coalesce(1).writeTo(tableName).append();
    df.coalesce(1).writeTo(tableName).append();
    df.coalesce(1).writeTo(tableName).append();

    Table table = validationCatalog.loadTable(tableIdent);
    assertThat(table.snapshots()).as("Should produce 4 snapshots").hasSize(4);

    Dataset<Row> fileDF =
        spark.read().format("iceberg").load(tableName(tableIdent.name() + ".files"));
    List<Long> fileSizes = fileDF.select("file_size_in_bytes").as(Encoders.LONG()).collectAsList();
    long avgFileSize = fileSizes.stream().mapToLong(i -> i).sum() / fileSizes.size();

    try (CloseableIterable<FileScanTask> fileScanTasks = table.newScan().planFiles()) {
      String fileSetID = UUID.randomUUID().toString();

      ScanTaskSetManager taskSetManager = ScanTaskSetManager.get();
      taskSetManager.stageTasks(table, fileSetID, Lists.newArrayList(fileScanTasks));

      // read and pack original 4 files into 2 splits
      Dataset<Row> scanDF =
          spark
              .read()
              .format("iceberg")
              .option(SparkReadOptions.SCAN_TASK_SET_ID, fileSetID)
              .option(SparkReadOptions.SPLIT_SIZE, Long.toString(avgFileSize * 2))
              .option(SparkReadOptions.FILE_OPEN_COST, "0")
              .load(tableName);

      // write the packed data into new files where each split becomes a new file
      scanDF
          .writeTo(tableName)
          .option(SparkWriteOptions.REWRITTEN_FILE_SCAN_TASK_SET_ID, fileSetID)
          .append();

      // commit the rewrite
      FileRewriteCoordinator rewriteCoordinator = FileRewriteCoordinator.get();
      Set<DataFile> rewrittenFiles =
          taskSetManager.fetchTasks(table, fileSetID).stream()
              .map(t -> t.asFileScanTask().file())
              .collect(Collectors.toCollection(DataFileSet::create));
      Set<DataFile> addedFiles = rewriteCoordinator.fetchNewFiles(table, fileSetID);
      table.newRewrite().rewriteFiles(rewrittenFiles, addedFiles).commit();
    }

    table.refresh();

    Map<String, String> summary = table.currentSnapshot().summary();
    assertThat(summary)
        .containsEntry("deleted-data-files", "4")
        .containsEntry("added-data-files", "2");

    Object rowCount = scalarSql("SELECT count(*) FROM %s", tableName);
    assertThat(rowCount).as("Row count must match").isEqualTo(4000L);
  }

  @TestTemplate
  public void testSortRewrite() throws NoSuchTableException, IOException {
    sql("CREATE TABLE %s (id INT, data STRING) USING iceberg", tableName);

    Dataset<Row> df = newDF(1000);
    df.coalesce(1).writeTo(tableName).append();
    df.coalesce(1).writeTo(tableName).append();
    df.coalesce(1).writeTo(tableName).append();
    df.coalesce(1).writeTo(tableName).append();

    Table table = validationCatalog.loadTable(tableIdent);
    assertThat(table.snapshots()).as("Should produce 4 snapshots").hasSize(4);

    try (CloseableIterable<FileScanTask> fileScanTasks = table.newScan().planFiles()) {
      String fileSetID = UUID.randomUUID().toString();

      ScanTaskSetManager taskSetManager = ScanTaskSetManager.get();
      taskSetManager.stageTasks(table, fileSetID, Lists.newArrayList(fileScanTasks));

      // read original 4 files as 4 splits
      Dataset<Row> scanDF =
          spark
              .read()
              .format("iceberg")
              .option(SparkReadOptions.SCAN_TASK_SET_ID, fileSetID)
              .option(SparkReadOptions.SPLIT_SIZE, "134217728")
              .option(SparkReadOptions.FILE_OPEN_COST, "134217728")
              .load(tableName);

      // make sure we disable AQE and set the number of shuffle partitions as the target num files
      ImmutableMap<String, String> sqlConf =
          ImmutableMap.of(
              "spark.sql.shuffle.partitions", "2",
              "spark.sql.adaptive.enabled", "false");

      withSQLConf(
          sqlConf,
          () -> {
            try {
              // write new files with sorted records
              scanDF
                  .sort("id")
                  .writeTo(tableName)
                  .option(SparkWriteOptions.REWRITTEN_FILE_SCAN_TASK_SET_ID, fileSetID)
                  .append();
            } catch (NoSuchTableException e) {
              throw new RuntimeException("Could not replace files", e);
            }
          });

      // commit the rewrite
      FileRewriteCoordinator rewriteCoordinator = FileRewriteCoordinator.get();
      Set<DataFile> rewrittenFiles =
          taskSetManager.fetchTasks(table, fileSetID).stream()
              .map(t -> t.asFileScanTask().file())
              .collect(Collectors.toCollection(DataFileSet::create));
      Set<DataFile> addedFiles = rewriteCoordinator.fetchNewFiles(table, fileSetID);
      table.newRewrite().rewriteFiles(rewrittenFiles, addedFiles).commit();
    }

    table.refresh();

    Map<String, String> summary = table.currentSnapshot().summary();
    assertThat(summary)
        .containsEntry("deleted-data-files", "4")
        .containsEntry("added-data-files", "2");

    Object rowCount = scalarSql("SELECT count(*) FROM %s", tableName);
    assertThat(rowCount).as("Row count must match").isEqualTo(4000L);
  }

  @TestTemplate
  public void testCommitMultipleRewrites() throws NoSuchTableException, IOException {
    sql("CREATE TABLE %s (id INT, data STRING) USING iceberg", tableName);

    Dataset<Row> df = newDF(1000);

    // add first two files
    df.coalesce(1).writeTo(tableName).append();
    df.coalesce(1).writeTo(tableName).append();

    Table table = validationCatalog.loadTable(tableIdent);

    String firstFileSetID = UUID.randomUUID().toString();
    long firstFileSetSnapshotId = table.currentSnapshot().snapshotId();

    ScanTaskSetManager taskSetManager = ScanTaskSetManager.get();

    try (CloseableIterable<FileScanTask> tasks = table.newScan().planFiles()) {
      // stage first 2 files for compaction
      taskSetManager.stageTasks(table, firstFileSetID, Lists.newArrayList(tasks));
    }

    // add two more files
    df.coalesce(1).writeTo(tableName).append();
    df.coalesce(1).writeTo(tableName).append();

    table.refresh();

    String secondFileSetID = UUID.randomUUID().toString();

    try (CloseableIterable<FileScanTask> tasks =
        table.newScan().appendsAfter(firstFileSetSnapshotId).planFiles()) {
      // stage 2 more files for compaction
      taskSetManager.stageTasks(table, secondFileSetID, Lists.newArrayList(tasks));
    }

    ImmutableSet<String> fileSetIDs = ImmutableSet.of(firstFileSetID, secondFileSetID);

    for (String fileSetID : fileSetIDs) {
      // read and pack 2 files into 1 split
      Dataset<Row> scanDF =
          spark
              .read()
              .format("iceberg")
              .option(SparkReadOptions.SCAN_TASK_SET_ID, fileSetID)
              .option(SparkReadOptions.SPLIT_SIZE, Long.MAX_VALUE)
              .load(tableName);

      // write the combined data as one file
      scanDF
          .writeTo(tableName)
          .option(SparkWriteOptions.REWRITTEN_FILE_SCAN_TASK_SET_ID, fileSetID)
          .append();
    }

    // commit both rewrites at the same time
    FileRewriteCoordinator rewriteCoordinator = FileRewriteCoordinator.get();
    Set<DataFile> rewrittenFiles =
        fileSetIDs.stream()
            .flatMap(fileSetID -> taskSetManager.fetchTasks(table, fileSetID).stream())
            .map(t -> t.asFileScanTask().file())
            .collect(Collectors.toSet());
    Set<DataFile> addedFiles =
        fileSetIDs.stream()
            .flatMap(fileSetID -> rewriteCoordinator.fetchNewFiles(table, fileSetID).stream())
            .collect(Collectors.toCollection(DataFileSet::create));
    table.newRewrite().rewriteFiles(rewrittenFiles, addedFiles).commit();

    table.refresh();

    assertThat(table.snapshots()).as("Should produce 5 snapshots").hasSize(5);

    Map<String, String> summary = table.currentSnapshot().summary();
    assertThat(summary)
        .containsEntry("deleted-data-files", "4")
        .containsEntry("added-data-files", "2");

    Object rowCount = scalarSql("SELECT count(*) FROM %s", tableName);
    assertThat(rowCount).as("Row count must match").isEqualTo(4000L);
  }

  private Dataset<Row> newDF(int numRecords) {
    List<SimpleRecord> data = Lists.newArrayListWithExpectedSize(numRecords);
    for (int index = 0; index < numRecords; index++) {
      data.add(new SimpleRecord(index, Integer.toString(index)));
    }
    return spark.createDataFrame(data, SimpleRecord.class);
  }
}
