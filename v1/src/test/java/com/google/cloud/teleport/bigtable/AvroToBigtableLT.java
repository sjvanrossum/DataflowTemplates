/*
 * Copyright (C) 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.teleport.bigtable;

import static com.google.cloud.teleport.it.common.matchers.TemplateAsserts.assertThatPipeline;
import static com.google.cloud.teleport.it.common.matchers.TemplateAsserts.assertThatResult;
import static com.google.cloud.teleport.it.gcp.artifacts.utils.ArtifactUtils.createStorageClient;
import static com.google.cloud.teleport.it.gcp.artifacts.utils.ArtifactUtils.getFullGcsPath;
import static com.google.cloud.teleport.it.gcp.bigtable.BigtableResourceManagerUtils.generateTableId;
import static com.google.common.truth.Truth.assertThat;

import com.google.cloud.storage.Storage;
import com.google.cloud.teleport.it.common.PipelineLauncher.LaunchConfig;
import com.google.cloud.teleport.it.common.PipelineLauncher.LaunchInfo;
import com.google.cloud.teleport.it.common.PipelineOperator.Result;
import com.google.cloud.teleport.it.common.TestProperties;
import com.google.cloud.teleport.it.common.utils.ResourceManagerUtils;
import com.google.cloud.teleport.it.gcp.TemplateLoadTestBase;
import com.google.cloud.teleport.it.gcp.artifacts.ArtifactClient;
import com.google.cloud.teleport.it.gcp.artifacts.GcsArtifactClient;
import com.google.cloud.teleport.it.gcp.bigtable.BigtableResourceManager;
import com.google.cloud.teleport.it.gcp.datagenerator.DataGenerator;
import com.google.cloud.teleport.metadata.TemplateLoadTest;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Resources;
import java.io.IOException;
import java.text.ParseException;
import java.time.Duration;
import java.util.function.Function;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Performance tests for {@link AvroToBigtable GCS Avro to Bigtable} template. */
@Category(TemplateLoadTest.class)
@TemplateLoadTest(AvroToBigtable.class)
@RunWith(JUnit4.class)
public class AvroToBigtableLT extends TemplateLoadTestBase {
  private static final String SPEC_PATH =
      MoreObjects.firstNonNull(
          TestProperties.specPath(), "gs://dataflow-templates/latest/GCS_Avro_to_Cloud_Bigtable");
  private static final String ARTIFACT_BUCKET = TestProperties.artifactBucket();
  private static final String TEST_ROOT_DIR = AvroToBigtableLT.class.getSimpleName().toLowerCase();
  // 56,000,000 messages of the given schema make up approximately 10GB
  private static final String NUM_MESSAGES = "56000000";
  private static final String INPUT_PCOLLECTION =
      "Read from Avro/Read/ParDo(BoundedSourceAsSDFWrapper)/ParMultiDo(BoundedSourceAsSDFWrapper).out0";
  private static final String OUTPUT_PCOLLECTION =
      "Transform to Bigtable/ParMultiDo(AvroToBigtable).out0";
  private static BigtableResourceManager bigtableResourceManager;
  private static ArtifactClient gcsClient;
  private static String generatorSchemaPath;
  private static String avroSchemaPath;

  @Before
  public void setup() throws IOException {
    // Set up resource managers
    bigtableResourceManager = BigtableResourceManager.builder(testName, project).build();
    Storage storageClient = createStorageClient(CREDENTIALS);
    gcsClient = GcsArtifactClient.builder(storageClient, ARTIFACT_BUCKET, TEST_ROOT_DIR).build();
    // upload schema files and save path
    generatorSchemaPath =
        getFullGcsPath(
            ARTIFACT_BUCKET,
            gcsClient
                .uploadArtifact(
                    "input/schema.json",
                    Resources.getResource("AvroToBigtableLT/schema.json").getPath())
                .name());
    avroSchemaPath =
        getFullGcsPath(
            ARTIFACT_BUCKET,
            gcsClient
                .uploadArtifact(
                    "input/bigtable.avsc",
                    Resources.getResource("schema/avro/bigtable.avsc").getPath())
                .name());
  }

  @After
  public void teardown() {
    ResourceManagerUtils.cleanResources(bigtableResourceManager, gcsClient);
  }

  @Test
  public void testBacklog10gb() throws IOException, ParseException, InterruptedException {
    testBacklog(Function.identity());
  }

  public void testBacklog(Function<LaunchConfig.Builder, LaunchConfig.Builder> paramsAdder)
      throws IOException, ParseException, InterruptedException {
    // Arrange
    String tableId = generateTableId(testName);
    // The generated data contains only 1 column family named "SystemMetrics"
    bigtableResourceManager.createTable(tableId, ImmutableList.of("SystemMetrics"));
    // Bigtable expects Avro files to meet a specific schema
    DataGenerator dataGenerator =
        DataGenerator.builderWithSchemaLocation(testName, generatorSchemaPath)
            .setQPS("1000000")
            .setMessagesLimit(NUM_MESSAGES)
            .setSinkType("GCS")
            .setOutputDirectory(getTestMethodDirPath())
            .setOutputType("AVRO")
            .setAvroSchemaLocation(avroSchemaPath)
            .setNumShards("20")
            .setNumWorkers("20")
            .setMaxNumWorkers("100")
            .build();
    dataGenerator.execute(Duration.ofMinutes(30));
    LaunchConfig options =
        paramsAdder
            .apply(
                LaunchConfig.builder(testName, SPEC_PATH)
                    .addParameter("bigtableProjectId", project)
                    .addParameter("bigtableInstanceId", bigtableResourceManager.getInstanceId())
                    .addParameter("bigtableTableId", tableId)
                    .addParameter("inputFilePattern", getTestMethodDirPath() + "/*"))
            .build();

    // Act
    LaunchInfo info = pipelineLauncher.launch(project, region, options);
    assertThatPipeline(info).isRunning();
    Result result = pipelineOperator.waitUntilDone(createConfig(info, Duration.ofMinutes(60)));

    // Assert
    assertThatResult(result).isLaunchFinished();
    // check to see if messages reached the output bigtable table
    // TODO(pranavbhandari): Check if there is a way to make this assertion stronger.
    assertThat(bigtableResourceManager.readTable(tableId, 5L)).isNotEmpty();

    // export results
    exportMetricsToBigQuery(info, getMetrics(info, INPUT_PCOLLECTION, OUTPUT_PCOLLECTION));
  }

  private String getTestMethodDirPath() {
    return getFullGcsPath(ARTIFACT_BUCKET, TEST_ROOT_DIR, gcsClient.runId(), testName);
  }
}
