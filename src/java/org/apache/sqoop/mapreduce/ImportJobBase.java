/**
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

package org.apache.sqoop.mapreduce;

import java.io.IOException;

import org.apache.avro.file.DataFileConstants;
import org.apache.avro.mapred.AvroJob;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.GzipCodec;
import org.apache.hadoop.mapreduce.Counters;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.sqoop.util.PerfCounters;
import com.cloudera.sqoop.SqoopOptions;
import com.cloudera.sqoop.config.ConfigurationHelper;
import com.cloudera.sqoop.io.CodecMap;
import com.cloudera.sqoop.manager.ImportJobContext;
import com.cloudera.sqoop.mapreduce.JobBase;
import com.cloudera.sqoop.orm.TableClassName;
import com.cloudera.sqoop.util.ImportException;

/**
 * Base class for running an import MapReduce job.
 * Allows dependency injection, etc, for easy customization of import job types.
 */
public class ImportJobBase extends JobBase {

  private ImportJobContext context;

  public static final Log LOG = LogFactory.getLog(
      ImportJobBase.class.getName());

  public ImportJobBase() {
    this(null);
  }

  public ImportJobBase(final SqoopOptions opts) {
    this(opts, null, null, null, null);
  }

  public ImportJobBase(final SqoopOptions opts,
      final Class<? extends Mapper> mapperClass,
      final Class<? extends InputFormat> inputFormatClass,
      final Class<? extends OutputFormat> outputFormatClass,
      final ImportJobContext context) {
    super(opts, mapperClass, inputFormatClass, outputFormatClass);
    this.context = context;
  }

  /**
   * Configure the output format to use for the job.
   */
  @Override
  protected void configureOutputFormat(Job job, String tableName,
      String tableClassName) throws ClassNotFoundException, IOException {

    job.setOutputFormatClass(getOutputFormatClass());

    if (options.getFileLayout() == SqoopOptions.FileLayout.SequenceFile) {
      job.getConfiguration().set("mapred.output.value.class", tableClassName);
    }

    if (options.shouldUseCompression()) {
      FileOutputFormat.setCompressOutput(job, true);

      String codecName = options.getCompressionCodec();
      Class<? extends CompressionCodec> codecClass;
      if (codecName == null) {
        codecClass = GzipCodec.class;
      } else {
        Configuration conf = job.getConfiguration();
        codecClass = CodecMap.getCodec(codecName, conf).getClass();
      }
      FileOutputFormat.setOutputCompressorClass(job, codecClass);

      if (options.getFileLayout() == SqoopOptions.FileLayout.SequenceFile) {
        SequenceFileOutputFormat.setOutputCompressionType(job,
          CompressionType.BLOCK);
      }

      // SQOOP-428: Avro expects not a fully qualified class name but a "short"
      // name instead (e.g. "snappy") and it needs to be set in a custom
      // configuration option called "avro.output.codec".
      // The default codec is "deflate".
      if (options.getFileLayout() == SqoopOptions.FileLayout.AvroDataFile) {
        if (codecName != null) {
          String shortName =
            CodecMap.getCodecShortNameByName(codecName, job.getConfiguration());
          // Avro only knows about "deflate" and not "default"
          if (shortName.equalsIgnoreCase("default")) {
            shortName = "deflate";
          }
          job.getConfiguration().set(AvroJob.OUTPUT_CODEC, shortName);
        } else {
          job.getConfiguration()
            .set(AvroJob.OUTPUT_CODEC, DataFileConstants.DEFLATE_CODEC);
        }
      }
    }

    Path outputPath = context.getDestination();
    FileOutputFormat.setOutputPath(job, outputPath);
  }

  /**
   * Actually run the MapReduce job.
   */
  @Override
  protected boolean runJob(Job job) throws ClassNotFoundException, IOException,
      InterruptedException {

    PerfCounters perfCounters = new PerfCounters();
    perfCounters.startClock();

    boolean success = doSubmitJob(job);
    perfCounters.stopClock();

    Counters jobCounters = job.getCounters();
    // If the job has been retired, these may be unavailable.
    if (null == jobCounters) {
      displayRetiredJobNotice(LOG);
    } else {
      perfCounters.addBytes(jobCounters.getGroup("FileSystemCounters")
        .findCounter("HDFS_BYTES_WRITTEN").getValue());
      LOG.info("Transferred " + perfCounters.toString());
      long numRecords = ConfigurationHelper.getNumMapOutputRecords(job);
      LOG.info("Retrieved " + numRecords + " records.");
    }
    return success;
  }

  /**
   * Submit the Map Reduce Job.
   */
  protected boolean doSubmitJob(Job job)
    throws IOException, InterruptedException, ClassNotFoundException {
    return job.waitForCompletion(true);
  }

  /**
   * Run an import job to read a table in to HDFS.
   *
   * @param tableName  the database table to read; may be null if a free-form
   * query is specified in the SqoopOptions, and the ImportJobBase subclass
   * supports free-form queries.
   * @param ormJarFile the Jar file to insert into the dcache classpath.
   * (may be null)
   * @param splitByCol the column of the database table to use to split
   * the import
   * @param conf A fresh Hadoop Configuration to use to build an MR job.
   * @throws IOException if the job encountered an IO problem
   * @throws ImportException if the job failed unexpectedly or was
   * misconfigured.
   */
  public void runImport(String tableName, String ormJarFile, String splitByCol,
      Configuration conf) throws IOException, ImportException {

    if (null != tableName) {
      LOG.info("Beginning import of " + tableName);
    } else {
      LOG.info("Beginning query import.");
    }

    String tableClassName =
        new TableClassName(options).getClassForTable(tableName);
    loadJars(conf, ormJarFile, tableClassName);

    Job job = new Job(conf);
    try {
      // Set the external jar to use for the job.
      job.getConfiguration().set("mapred.jar", ormJarFile);
      if (options.getMapreduceJobName() != null) {
        job.setJobName(options.getMapreduceJobName());
      }

      propagateOptionsToJob(job);
      configureInputFormat(job, tableName, tableClassName, splitByCol);
      configureOutputFormat(job, tableName, tableClassName);
      configureMapper(job, tableName, tableClassName);
      configureNumTasks(job);
      cacheJars(job, getContext().getConnManager());

      jobSetup(job);
      setJob(job);
      boolean success = runJob(job);
      if (!success) {
        throw new ImportException("Import job failed!");
      }
    } catch (InterruptedException ie) {
      throw new IOException(ie);
    } catch (ClassNotFoundException cnfe) {
      throw new IOException(cnfe);
    } finally {
      unloadJars();
      jobTeardown(job);
    }
  }

  /**
   * Open-ended "setup" routine that is called after the job is configured
   * but just before it is submitted to MapReduce. Subclasses may override
   * if necessary.
   */
  protected void jobSetup(Job job) throws IOException, ImportException {
  }

  /**
   * Open-ended "teardown" routine that is called after the job is executed.
   * Subclasses may override if necessary.
   */
  protected void jobTeardown(Job job) throws IOException, ImportException {
  }

  protected ImportJobContext getContext() {
    return context;
  }
}
